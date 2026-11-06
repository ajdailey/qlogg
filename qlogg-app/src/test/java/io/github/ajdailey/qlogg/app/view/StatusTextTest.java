package io.github.ajdailey.qlogg.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StatusTextTest {
  private static final long NOON = 1_793_448_000_000L;

  private static DocumentViewState state(
      boolean forced, boolean indexing, boolean following, boolean missing, boolean stopped) {
    return new DocumentViewState(
        "app.log",
        "/logs/app.log",
        "UTF-8",
        forced,
        1234,
        40,
        indexing,
        false,
        following,
        5L << 20,
        NOON,
        missing,
        stopped,
        false,
        null);
  }

  @Test
  void formatsTheTopRowFields() {
    DocumentViewState state = state(false, false, false, false, false);
    assertEquals("5.0 MiB", StatusText.size(state.fileBytes()));
    assertEquals("Last modified 2026-10-31 12:00:00", StatusText.sizeTip(state, ZoneOffset.UTC));
    assertEquals("UTF-8", StatusText.encoding(state));
    assertEquals("UTF-8 (forced)", StatusText.encoding(state(true, false, false, false, false)));
  }

  @Test
  void listsTheNotesThatApply() {
    assertEquals("", StatusText.notes(state(false, false, false, false, false)));
    assertEquals(
        "Indexing 40%    Following    File missing    Loading stopped",
        StatusText.notes(state(true, true, true, true, true)));
  }

  @Test
  void sizesUseTheLargestUnitUnder1024() {
    assertEquals("999 B", StatusText.size(999));
    assertEquals("1.5 KiB", StatusText.size(1536));
    assertEquals("1.0 MiB", StatusText.size(1L << 20));
    assertEquals("2.5 GiB", StatusText.size(5L << 29));
  }
}
