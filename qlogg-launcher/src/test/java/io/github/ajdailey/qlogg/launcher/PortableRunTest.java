package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import io.github.ajdailey.qlogg.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A portable run writes its settings, session and positions inside the bundle and nothing else: two
 * runs over a bundle with a {@code qlogg.conf} marker, the second restoring the first's session,
 * leave every other file under the test tree as it was and the home directory empty.
 */
@Tag("swt")
class PortableRunTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(20);

  @TempDir Path temp;

  @Test
  void aPortableRunWritesOnlyInsideItsBundle() throws Exception {
    Path bundle = Files.createDirectories(temp.resolve("bundle"));
    Files.createFile(bundle.resolve(SettingsLocation.MARKER));
    Path home = Files.createDirectories(temp.resolve("home"));
    Path log = Files.createDirectories(temp.resolve("logs")).resolve("app.log");
    Files.writeString(log, "one\ntwo\nthree\n");
    SettingsLocation location =
        SettingsLocation.resolve(
            null,
            bundle.toString(),
            OsTraits.of("Linux"),
            Map.<String, String>of(),
            home.toString());
    assertEquals(SettingsMode.PORTABLE, location.mode());
    Map<Path, Long> before = files(temp);
    String userHome = System.getProperty("user.home");
    try {
      PlatformSetup.apply(OsTraits.of("Linux"), location);
      run(location, log, true);
      run(location, log, false);
    } finally {
      System.setProperty("user.home", userHome);
    }
    Map<Path, Long> after = files(temp);
    List<Path> outside = new ArrayList<Path>();
    for (Map.Entry<Path, Long> entry : after.entrySet()) {
      Long old = before.get(entry.getKey());
      if (!entry.getKey().startsWith(bundle) && !entry.getValue().equals(old)) {
        outside.add(entry.getKey());
      }
    }
    assertEquals(List.of(), outside);
    assertEquals(List.of(), List.copyOf(files(home).keySet()));
    assertTrue(Files.isRegularFile(bundle.resolve(SessionStore.FILE)));
    assertTrue(Files.isRegularFile(bundle.resolve(PositionStore.FILE)));
    assertTrue(
        Files.readString(bundle.resolve(SettingsLocation.MARKER), StandardCharsets.ISO_8859_1)
            .contains("favorite.0="));
  }

  /** One run: the first opens the log and changes settings, the second restores the session. */
  private void run(SettingsLocation location, final Path log, final boolean first) {
    ConfigStore config = new ConfigStore(location);
    StartupRequest request =
        new StartupRequest(
            List.of(), false, SessionMode.DEFAULT, 800, 500, null, null, null, false);
    final boolean[] done = new boolean[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              MainWindow window, final ApplicationController controller, Workers workers) {
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            if (first) {
              controller.openFile(log, 0);
            }
            display.timerExec(
                20,
                new Runnable() {
                  @Override
                  public void run() {
                    DocumentController shown = controller.activeDocument();
                    boolean ready = shown != null && !shown.state().indexing();
                    if (ready && first) {
                      controller.execute(CommandId.ZOOM_IN);
                      controller.execute(CommandId.ADD_FAVORITE);
                    }
                    if (ready || System.nanoTime() > deadline) {
                      done[0] = ready;
                      controller.shutdown();
                    } else {
                      display.timerExec(20, this);
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(0, new Gui(request, config, config.load(), err, observer).run());
    assertTrue(done[0], first ? "file not opened" : "session not restored");
  }

  /** Returns every regular file under a directory with its modification time. */
  private static Map<Path, Long> files(Path root) throws IOException {
    final Map<Path, Long> found = new HashMap<Path, Long>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            found.put(file, attributes.lastModifiedTime().toMillis());
            return FileVisitResult.CONTINUE;
          }
        });
    return found;
  }
}
