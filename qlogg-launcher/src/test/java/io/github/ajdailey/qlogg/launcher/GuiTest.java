package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
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
import org.eclipse.swt.widgets.Display;
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
