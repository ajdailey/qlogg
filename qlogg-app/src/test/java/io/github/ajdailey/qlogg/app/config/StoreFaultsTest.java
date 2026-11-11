package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A malformed, oversized or unknown-version session file loads as no windows and is kept as {@code
 * .invalid} on the next save; a remembered encoding the runtime lacks reads as detection.
 */
class StoreFaultsTest {
  @TempDir Path temp;

  private SettingsLocation location() {
    return new SettingsLocation(SettingsMode.EXPLICIT, temp);
  }

  private void assertIgnoredAndKept(String content) throws Exception {
    Path file = temp.resolve(SessionStore.FILE);
    Files.writeString(file, content, StandardCharsets.ISO_8859_1);
    SessionStore store = new SessionStore(location());
    assertEquals(List.of(), store.loadWindows());
    assertEquals(Session.EMPTY, store.load());
    assertTrue(store.save(Session.EMPTY));
    assertEquals(
        content,
        Files.readString(
            temp.resolve(SessionStore.FILE + ".invalid"), StandardCharsets.ISO_8859_1));
  }

  @Test
  void aMalformedSessionIsIgnoredAndKept() throws Exception {
    assertIgnoredAndKept("version=1\nwindow.0.active=0\nwindow.0.tab.0.path=\\uZZZZ\n");
  }

  @Test
  void anOversizedSessionIsIgnoredAndKept() throws Exception {
    StringBuilder text = new StringBuilder("version=1\nwindow.0.active=0\n");
    while (text.length() <= SessionStore.MAX_BYTES) {
      text.append("window.0.tab.0.path=/mock/").append("a".repeat(1000)).append('\n');
    }
    assertIgnoredAndKept(text.toString());
  }

  @Test
  void anUnknownSessionVersionIsIgnoredAndKept() throws Exception {
    assertIgnoredAndKept("version=2\nwindow.0.active=0\nwindow.0.tab.0.path=/mock/a.log\n");
  }

  /** Windows refuses a move over a directory with access denied; the next save still runs. */
  @Test
  void aRefusedSessionSaveLeavesSavingOn() throws Exception {
    Path blocker = Files.createDirectory(temp.resolve(SessionStore.FILE));
    Files.writeString(blocker.resolve("inside"), "x");
    SessionStore store = new SessionStore(location());
    assertFalse(store.save(Session.EMPTY));
    Files.delete(blocker.resolve("inside"));
    Files.delete(blocker);
    assertTrue(store.save(Session.EMPTY));
    assertTrue(Files.isRegularFile(temp.resolve(SessionStore.FILE)));
  }

  @Test
  void anUnsupportedRememberedEncodingReadsAsDetection() throws Exception {
    Path a = Path.of("/mock/a.log");
    Path b = Path.of("/mock/b.log");
    Files.writeString(
        temp.resolve(PositionStore.FILE),
        "version=1\npos.0.path="
            + key(a)
            + "\npos.0.encoding=x-mock-unknown\npos.1.path="
            + key(b)
            + "\npos.1.encoding=bad name\n",
        StandardCharsets.ISO_8859_1);
    PositionStore store = new PositionStore(location());
    store.load();
    assertEquals("x-mock-unknown", store.get(a).encoding());
    assertNull(store.get(a).charset());
    assertNull(store.get(b).charset());
  }

  /** A path as a properties value: backslashes escaped. */
  private static String key(Path path) {
    return path.toString().replace("\\", "\\\\");
  }
}
