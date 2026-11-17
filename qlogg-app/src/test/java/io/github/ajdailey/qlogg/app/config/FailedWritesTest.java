package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A failed session or settings write: a non-empty folder at the file's name fails the write. One
 * WARN per run of failures, without the settings path.
 */
class FailedWritesTest {
  @TempDir Path temp;

  private final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(LogRecord entry) {
          if (entry.getLevel().intValue() >= Level.WARNING.intValue()) {
            Object[] values = entry.getParameters();
            warnings.add(entry.getMessage() + " " + (values == null ? "" : List.of(values)));
          }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  private final List<Logger> loggers =
      List.of(
          Logger.getLogger(SettingsFile.class.getName()),
          Logger.getLogger(ConfigStore.class.getName()));

  @BeforeEach
  void listen() {
    for (Logger logger : loggers) {
      logger.setLevel(Level.WARNING);
      logger.addHandler(capture);
    }
  }

  @AfterEach
  void stopListening() {
    for (Logger logger : loggers) {
      logger.removeHandler(capture);
      logger.setLevel(null);
    }
  }

  private SettingsLocation location() {
    return new SettingsLocation(SettingsMode.EXPLICIT, temp);
  }

  private void block(String name) throws IOException {
    Path blocker = Files.createDirectories(temp.resolve(name));
    Files.writeString(blocker.resolve("keep.txt"), "mock\n");
  }

  private void unblock(String name) throws IOException {
    Files.delete(temp.resolve(name).resolve("keep.txt"));
    Files.delete(temp.resolve(name));
  }

  /** Returns the failed-write WARNs and checks that none names the settings path. */
  private List<String> failedWrites() {
    List<String> saves = new ArrayList<String>();
    synchronized (warnings) {
      for (String warning : warnings) {
        if (warning.startsWith("Cannot save")) {
          assertFalse(warning.contains(temp.toString()), warning);
          saves.add(warning);
        }
      }
    }
    return saves;
  }

  @Test
  void aFailedSessionWriteWarnsOncePerRunOfFailures() throws Exception {
    SessionStore store = new SessionStore(location());
    List<Session> windows = List.of(Session.EMPTY);
    block(SessionStore.FILE);
    assertEquals(SaveResult.FAILED, store.writeWindows(windows));
    assertEquals(SaveResult.FAILED, store.writeWindows(windows));
    assertEquals(1, failedWrites().size(), warnings.toString());
    unblock(SessionStore.FILE);
    assertEquals(SaveResult.WRITTEN, store.writeWindows(windows));
    Files.delete(temp.resolve(SessionStore.FILE));
    block(SessionStore.FILE);
    assertEquals(SaveResult.FAILED, store.writeWindows(windows));
    assertEquals(2, failedWrites().size(), warnings.toString());
  }

  @Test
  void aFailedSettingsWriteWarnsOncePerRunOfFailures() throws Exception {
    ConfigStore store = new ConfigStore(location());
    Preferences changed = Preferences.DEFAULTS.withSplitPercent(42);
    block(SettingsLocation.MARKER);
    assertEquals(SaveResult.FAILED, store.write(Preferences.DEFAULTS, changed));
    assertEquals(SaveResult.FAILED, store.write(Preferences.DEFAULTS, changed));
    assertEquals(1, failedWrites().size(), warnings.toString());
    unblock(SettingsLocation.MARKER);
    assertEquals(SaveResult.WRITTEN, store.write(Preferences.DEFAULTS, changed));
    Files.delete(temp.resolve(SettingsLocation.MARKER));
    block(SettingsLocation.MARKER);
    assertEquals(SaveResult.FAILED, store.write(Preferences.DEFAULTS, changed));
    assertEquals(2, failedWrites().size(), warnings.toString());
  }
}
