package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnopenedNamesTest {
  @TempDir Path temp;

  private StartupRequest request(SessionMode session) {
    // The DataSync+ log monitor's launch: -f --window-width 1200 --window-height 720.
    return new StartupRequest(
        List.of(new FileRequest(temp.resolve("zz.txt"), 0)),
        true,
        session,
        1200,
        720,
        null,
        null,
        null,
        false);
  }

  @Test
  void anExpansionDropsEveryFileAndKeepsTheOptions() {
    StartupRequest applied =
        new UnopenedNames(List.of("??.txt"), List.of()).apply(request(SessionMode.DEFAULT));
    assertEquals(List.of(), applied.files());
    assertTrue(applied.follow());
    assertEquals(1200, applied.width());
    assertEquals(720, applied.height());
    assertEquals(SessionMode.NEW, applied.session());
  }

  @Test
  void unpassedNamesKeepTheOtherFilesAndSkipTheSession() {
    StartupRequest request = request(SessionMode.DEFAULT);
    StartupRequest applied = new UnopenedNames(List.of(), List.of("??.txt")).apply(request);
    assertEquals(request.files(), applied.files());
    assertEquals(SessionMode.NEW, applied.session());
    assertEquals(
        SessionMode.LOAD,
        new UnopenedNames(List.of(), List.of("??.txt")).apply(request(SessionMode.LOAD)).session());
  }

  @Test
  void withEveryNameOpenedTheRequestStands() {
    StartupRequest request = request(SessionMode.DEFAULT);
    assertSame(request, new UnopenedNames(List.of(), List.of()).apply(request));
  }

  @Test
  void theMessageNamesWhatWasNotOpened() {
    assertNull(new UnopenedNames(List.of(), List.of()).message());
    String unpassed = new UnopenedNames(List.of(), List.of("a?.txt", "b*.txt")).message();
    assertTrue(unpassed.startsWith("Not opened: a?.txt, b*.txt."), unpassed);
    String expanded = new UnopenedNames(List.of("??.txt"), List.of("c?.txt")).message();
    assertTrue(expanded.contains("Java expanded ??.txt as a wildcard"), expanded);
  }
}
