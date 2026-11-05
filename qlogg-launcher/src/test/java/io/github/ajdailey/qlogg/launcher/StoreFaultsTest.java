package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.text.DetectionReason;
import io.github.ajdailey.qlogg.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup over a malformed, oversized or unknown-version session file opens an empty window and
 * logs a WARN; a remembered encoding the runtime does not support opens the file with detection.
 */
@Tag("swt")
class StoreFaultsTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(20);

  @TempDir Path temp;

  private Path settings(String name) throws IOException {
    return Files.createDirectories(temp.resolve(name));
  }

  /** The recent log lines added since {@code from}. */
  private static List<String> recentSince(int from) {
    List<String> lines = LogConfigurator.RECENT.snapshot();
    return new ArrayList<String>(lines.subList(Math.min(from, lines.size()), lines.size()));
  }

  /**
   * Starts the application, waits for the active document to finish indexing when files are given,
   * and returns what the window showed.
   */
  private Observed start(Path directory, final List<FileRequest> files) {
    ConfigStore config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, directory));
    StartupRequest request =
        new StartupRequest(files, false, SessionMode.DEFAULT, 800, 500, null, null, null, false);
    final Observed observed = new Observed();
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              MainWindow window, final ApplicationController controller, Workers workers) {
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            display.timerExec(
                20,
                new Runnable() {
                  @Override
                  public void run() {
                    DocumentController shown = controller.activeDocument();
                    boolean ready =
                        files.isEmpty()
                            ? controller.documents().isEmpty()
                            : shown != null && !shown.state().indexing();
                    if (ready || System.nanoTime() > deadline) {
                      observed.ready = ready;
                      observed.documents = controller.documents().size();
                      if (shown != null) {
                        observed.reason = shown.document().detection().reason();
                        observed.encoding = shown.document().encoding().charset().name();
                      }
                      controller.shutdown();
                    } else {
                      display.timerExec(20, this);
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    observed.exit = new Gui(request, config, config.load(), err, observer).run();
    return observed;
  }

  /** What one run showed. */
  private static final class Observed {
    int exit = -1;
    boolean ready;
    int documents = -1;
    DetectionReason reason;
    String encoding;
  }

  private void assertEmptyStartWithWarn(Path directory, String warning) {
    int from = LogConfigurator.RECENT.snapshot().size();
    Observed observed = start(directory, List.of());
    assertEquals(0, observed.exit);
    assertTrue(observed.ready, "window did not open empty");
    assertEquals(0, observed.documents);
    boolean warned = false;
    for (String line : recentSince(from)) {
      warned |= line.contains(" WARN ") && line.contains(warning);
    }
    assertTrue(warned, recentSince(from).toString());
  }

  @Test
  void aMalformedSessionStartsEmptyWithAWarn() throws IOException {
    Path directory = settings("malformed");
    Files.writeString(
        directory.resolve(SessionStore.FILE),
        "version=1\nwindow.0.active=0\nwindow.0.tab.0.path=\\uZZZZ\n",
        StandardCharsets.ISO_8859_1);
    assertEmptyStartWithWarn(directory, "Malformed file");
    assertTrue(Files.isRegularFile(directory.resolve(SessionStore.FILE + ".invalid")));
  }

  @Test
  void anOversizedSessionStartsEmptyWithAWarn() throws IOException {
    Path directory = settings("oversized");
    StringBuilder text = new StringBuilder("version=1\nwindow.0.active=0\n");
    String path = "/mock/" + "a".repeat(1000) + ".log";
    for (int i = 0; text.length() <= 4 << 20; i++) {
      text.append("window.0.tab.").append(i).append(".path=").append(path).append('\n');
    }
    Files.writeString(directory.resolve(SessionStore.FILE), text, StandardCharsets.ISO_8859_1);
    assertEmptyStartWithWarn(directory, "Cannot read");
  }

  @Test
  void anUnknownSessionVersionStartsEmptyWithAWarn() throws IOException {
    Path directory = settings("version");
    Files.writeString(
        directory.resolve(SessionStore.FILE),
        "version=99\nwindow.0.active=0\nwindow.0.tab.0.path=/mock/a.log\n",
        StandardCharsets.ISO_8859_1);
    assertEmptyStartWithWarn(directory, "Malformed file");
  }

  @Test
  void anUnsupportedRememberedEncodingOpensWithDetection() throws IOException {
    Path directory = settings("encoding");
    Path log = Files.writeString(temp.resolve("app.log"), "mock line\n", StandardCharsets.US_ASCII);
    PositionStore positions =
        new PositionStore(new SettingsLocation(SettingsMode.EXPLICIT, directory));
    positions.setEncoding(log.toAbsolutePath().normalize(), StandardCharsets.UTF_16BE);
    assertTrue(positions.save());
    Path file = directory.resolve(PositionStore.FILE);
    Files.writeString(
        file,
        Files.readString(file, StandardCharsets.ISO_8859_1).replace("UTF-16BE", "x-mock-unknown"),
        StandardCharsets.ISO_8859_1);
    Observed observed = start(directory, List.of(new FileRequest(log, 0)));
    assertEquals(0, observed.exit);
    assertTrue(observed.ready, "file did not open");
    assertNotEquals(DetectionReason.FORCED, observed.reason);
    assertNotEquals("UTF-16BE", observed.encoding);
  }
}
