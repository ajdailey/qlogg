package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.ErrorExitMarker;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import io.github.ajdailey.qlogg.ui.swt.MainWindow;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Startup to shutdown through the composed application, on the test thread as UI thread. */
@Tag("swt")
class GuiTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(10);

  @TempDir Path temp;

  @Test
  void opensFileAtRequestedSizeThenShutsDownWithoutLeakingThreads() throws Exception {
    Path log = Files.createFile(temp.resolve("app.log"));
    StartupRequest request =
        new StartupRequest(
            List.of(new FileRequest(log, 0)),
            false,
            SessionMode.DEFAULT,
            900,
            600,
            50,
            40,
            null,
            true);
    final List<String> observed = new ArrayList<String>();
    final Workers[] owned = new Workers[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            owned[0] = workers;
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            display.timerExec(
                20,
                new Runnable() {
                  @Override
                  public void run() {
                    Rectangle area = window.shell().getClientArea();
                    boolean ready =
                        window.shell().getText().equals("app.log - QLogg")
                            && area.width == 900
                            && area.height == 600;
                    if (ready || System.nanoTime() > deadline) {
                      observed.add(window.shell().getText());
                      observed.add(area.width + "x" + area.height);
                      observed.add(window.shell().getLocation().toString());
                      controller.shutdown();
                    } else {
                      display.timerExec(20, this);
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(
        0,
        new Gui(
                request,
                new ConfigStore(
                    new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
                Preferences.DEFAULTS,
                err,
                observer)
            .run());
    assertEquals(3, observed.size(), observed.toString());
    assertEquals(List.of("app.log - QLogg", "900x600"), observed.subList(0, 2));
    // GTK 4 has no API to move a toplevel window; the shell reads 0,0 there.
    if (!"1".equals(System.getenv("SWT_GTK4"))) {
      assertEquals("Point {50, 40}", observed.get(2));
    }

    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return owned[0].liveThreads() == 0;
              }
            },
            Duration.ofSeconds(10)));
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      assertTrue(!thread.getName().startsWith("qlogg-") || !thread.isAlive(), thread.getName());
    }
  }

  @Test
  void aFailingUiTaskIsLoggedAndTheWindowStaysOpen() {
    StartupRequest request =
        new StartupRequest(List.of(), false, SessionMode.NEW, 640, 480, null, null, null, true);
    final List<Boolean> open = new ArrayList<Boolean>();
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            Display display = window.shell().getDisplay();
            display.asyncExec(new Failure(new IllegalStateException("mock listener failure")));
            display.timerExec(
                200,
                new Runnable() {
                  @Override
                  public void run() {
                    open.add(!window.shell().isDisposed());
                    controller.shutdown();
                  }
                });
          }
        };
    assertEquals(0, run(request, observer));
    assertEquals(List.of(Boolean.TRUE), open);
  }

  @Test
  void moreThanTenUiFailuresInOneSecondQuitWithTheSessionSaved() throws Exception {
    run(UiFailures.LIMIT + 1, new IllegalStateException("mock listener failure"));
  }

  @Test
  void aUiErrorQuitsWithTheSessionSaved() throws Exception {
    run(1, new Error("mock error"));
  }

  /**
   * Opens a file as the session owner, posts {@code failures} tasks that throw {@code thrown}, and
   * checks the quit status and the saved session.
   */
  private void run(final int failures, final Throwable thrown) throws Exception {
    Files.createDirectories(temp.resolve("settings"));
    final Path log = temp.resolve("app.log");
    Files.writeString(log, "one\ntwo\n", StandardCharsets.US_ASCII);
    StartupRequest request =
        new StartupRequest(
            List.of(new FileRequest(log, 0)),
            false,
            SessionMode.LOAD,
            640,
            480,
            null,
            null,
            null,
            false);
    final int[] ran = new int[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            display.timerExec(
                20,
                new Runnable() {
                  @Override
                  public void run() {
                    boolean opened =
                        controller.documents().size() == 1
                            && !controller.documents().get(0).state().indexing();
                    if (!opened && System.nanoTime() < deadline) {
                      display.timerExec(20, this);
                      return;
                    }
                    for (int i = 0; i < failures; i++) {
                      display.asyncExec(
                          new Runnable() {
                            @Override
                            public void run() {
                              ran[0]++;
                              new Failure(thrown).run();
                            }
                          });
                    }
                  }
                });
          }
        };
    ConfigStore config = settings();
    assertEquals(Launcher.EXIT_UI_FAILURE, run(request, observer));
    assertEquals(failures, ran[0]);
    List<Session> saved = config.sessions().loadWindows();
    assertEquals(1, saved.size());
    assertEquals(1, saved.get(0).tabs().size());
    assertEquals(log.toString(), saved.get(0).tabs().get(0).path());
    assertEquals(ErrorExitMarker.State.PENDING, marker().read());
  }

  @Test
  void aRestoringLaunchAfterAnErrorExitSkipsTheSessionTabsOnceAndShowsTheNotice() throws Exception {
    Path named = errorExit();
    List<String> first = launch(SessionMode.LOAD, List.of(new FileRequest(named, 0)));
    assertEquals(List.of("notice", "named.log"), first);
    assertEquals(ErrorExitMarker.State.ABSENT, marker().read());

    List<String> second = launch(SessionMode.LOAD, List.of());
    assertEquals(List.of("named.log"), second);
  }

  @Test
  void aLaunchWithoutRestoreShowsTheNoticeOnceAndTheNextRestoreSkipsTheTabs() throws Exception {
    Path named = errorExit();
    List<String> first = launch(SessionMode.DEFAULT, List.of(new FileRequest(named, 0)));
    assertEquals(List.of("noticeLog", "named.log"), first);
    assertEquals(ErrorExitMarker.State.SHOWN, marker().read());

    List<String> second = launch(SessionMode.DEFAULT, List.of(new FileRequest(named, 0)));
    assertEquals(List.of("named.log"), second);
    assertEquals(ErrorExitMarker.State.SHOWN, marker().read());

    List<String> restoring = launch(SessionMode.LOAD, List.of(new FileRequest(named, 0)));
    assertEquals(List.of("named.log"), restoring);
    assertEquals(ErrorExitMarker.State.ABSENT, marker().read());
  }

  /**
   * Saves a session holding {@code restored.log}, writes the marker as {@link UiFailures} does, and
   * returns {@code named.log} for the command line.
   */
  private Path errorExit() throws Exception {
    Files.createDirectories(temp.resolve("settings"));
    Path restored = temp.resolve("restored.log");
    Path named = temp.resolve("named.log");
    Files.writeString(restored, "one\n", StandardCharsets.US_ASCII);
    Files.writeString(named, "two\n", StandardCharsets.US_ASCII);
    assertTrue(
        settings()
            .sessions()
            .save(
                new Session(
                    List.of(new Session.Tab(restored.toString(), "", false, new long[0])), 0)));
    assertTrue(marker().write());
    return named;
  }

  /**
   * Runs a launch with the files, waits until one tab is indexed and 300 ms more, and returns
   * "notice" or "noticeLog" when an error-exit notice showed at open, then the tab titles.
   */
  private List<String> launch(SessionMode mode, List<FileRequest> files) {
    StartupRequest request =
        new StartupRequest(files, false, mode, 640, 480, null, null, null, false);
    final List<String> observed = new ArrayList<String>();
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            if (showsText(window.shell(), UiText.NOTICE_ERROR_EXIT.text())) {
              observed.add("notice");
            }
            if (showsText(window.shell(), UiText.NOTICE_ERROR_EXIT_LOG.text())) {
              observed.add("noticeLog");
            }
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            display.timerExec(
                20,
                new Runnable() {
                  @Override
                  public void run() {
                    boolean opened =
                        !controller.documents().isEmpty()
                            && !controller.documents().get(0).state().indexing();
                    if (!opened && System.nanoTime() < deadline) {
                      display.timerExec(20, this);
                      return;
                    }
                    display.timerExec(
                        300,
                        new Runnable() {
                          @Override
                          public void run() {
                            for (int i = 0; i < controller.documents().size(); i++) {
                              observed.add(controller.documents().get(i).state().title());
                            }
                            controller.shutdown();
                          }
                        });
                  }
                });
          }
        };
    assertEquals(0, run(request, observer));
    return observed;
  }

  private static boolean showsText(Control control, String text) {
    if (control instanceof Label label && text.equals(label.getText())) {
      return true;
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        if (showsText(child, text)) {
          return true;
        }
      }
    }
    return false;
  }

  private ErrorExitMarker marker() {
    return new ErrorExitMarker(
        new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
  }

  private ConfigStore settings() {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
  }

  private int run(StartupRequest request, Gui.Observer observer) {
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    return new Gui(request, settings(), Preferences.DEFAULTS, err, observer).run();
  }

  /** A UI task that throws. */
  private static final class Failure implements Runnable {
    private final Throwable thrown;

    Failure(Throwable thrown) {
      this.thrown = thrown;
    }

    @Override
    public void run() {
      if (thrown instanceof Error error) {
        throw error;
      }
      throw (RuntimeException) thrown;
    }
  }
}
