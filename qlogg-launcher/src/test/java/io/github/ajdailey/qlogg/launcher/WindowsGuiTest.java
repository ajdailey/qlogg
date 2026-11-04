package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.WindowSet;
import io.github.ajdailey.qlogg.app.ipc.InstanceClient;
import io.github.ajdailey.qlogg.app.ipc.InstanceServer;
import io.github.ajdailey.qlogg.app.log.LogFiles;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A primary instance through {@link Gui}: New Window opens a second window, a handoff opens its
 * file in the window activated last, and quit closes both windows and removes the endpoint.
 */
@Tag("swt")
class WindowsGuiTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(20);
  private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

  @TempDir Path temp;

  @Test
  void aHandoffOpensInTheNewestWindowAndQuitClosesBoth() throws Exception {
    final Path settings = Files.createDirectory(temp.resolve("settings"));
    final Path first = Files.writeString(temp.resolve("first.log"), "one\n");
    final Path handed = Files.writeString(temp.resolve("handed.log"), "two\n");
    InstanceServer server = InstanceServer.claim(settings);
    assertNotNull(server);
    StartupRequest request =
        new StartupRequest(
            List.of(new FileRequest(first, 0)),
            false,
            SessionMode.DEFAULT,
            800,
            500,
            null,
            null,
            null,
            false);
    final List<String> observed = new CopyOnWriteArrayList<String>();
    final Workers[] owned = new Workers[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              MainWindow window, final ApplicationController controller, final Workers workers) {
            owned[0] = workers;
            final WindowSet set = controller.windowSet();
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            // Activation events from opening the windows arrive late under load and would move
            // the handoff target; the user's activation waits until none arrives for QUIET_NANOS.
            final long[] lastActivation = {System.nanoTime()};
            display.addFilter(
                SWT.Activate,
                new Listener() {
                  @Override
                  public void handleEvent(Event event) {
                    lastActivation[0] = System.nanoTime();
                  }
                });
            controller.execute(CommandId.NEW_WINDOW);
            observed.add("windows " + set.windows().size());
            display.timerExec(
                20,
                new Runnable() {
                  @Override
                  public void run() {
                    long now = System.nanoTime();
                    if (now - lastActivation[0] < QUIET_NANOS && now < deadline) {
                      display.timerExec(20, this);
                      return;
                    }
                    // The user activates the second window, as a window manager reports a click.
                    ApplicationController second = set.windows().get(1);
                    second.windowActivated();
                    if (set.target() != second) {
                      observed.add("target is not the second window");
                    }
                    workers.start(
                        Workers.Kind.IPC,
                        new Runnable() {
                          @Override
                          public void run() {
                            try {
                              InstanceClient.handOff(
                                  settings, List.of(new FileRequest(handed, 0)), false);
                              observed.add("handed");
                            } catch (IOException e) {
                              observed.add("handoff failed: " + e.getMessage());
                            }
                          }
                        });
                  }
                });
            display.timerExec(
                20,
                new Runnable() {
                  @Override
                  public void run() {
                    ApplicationController second = set.windows().get(1);
                    boolean ready =
                        second.documents().size() == 1 && controller.documents().size() == 1;
                    if (ready || System.nanoTime() > deadline) {
                      observed.add("second " + second.documents().size());
                      observed.add("first " + controller.documents().size());
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
                new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)),
                Preferences.DEFAULTS,
                err,
                observer,
                server,
                LogFiles.NONE)
            .run());
    assertEquals(List.of("windows 2", "handed", "second 1", "first 1"), observed);
    assertFalse(Files.exists(settings.resolve("instance.endpoint")));
    assertTrue(owned[0].isClosed());
  }
}
