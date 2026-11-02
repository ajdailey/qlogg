package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ViewportTest {
  private static final long TRILLION = 1_000_000_000_000L;

  private static Viewport viewport(long lines, int rows) {
    Viewport viewport = new Viewport();
    viewport.resize(rows, 80);
    viewport.setLineCount(lines);
    return viewport;
  }

  @Test
  void unscaledIsOneUnitPerLine() {
    Viewport viewport = viewport(100, 10);
    assertFalse(viewport.scaled());
    assertEquals(100, viewport.scrollbarMaximum());
    assertEquals(10, viewport.scrollbarThumb());
    viewport.setScrollbarSelection(42);
    assertEquals(42, viewport.topLine());
    assertEquals(42, viewport.scrollbarSelection());
    viewport.setScrollbarSelection(95);
    assertEquals(90, viewport.topLine());
    assertTrue(viewport.atBottom());
  }

  @Test
  void rangeBoundaryStaysUnscaled() {
    Viewport viewport = viewport(Viewport.SCROLLBAR_RANGE + 40L, 40);
    assertFalse(viewport.scaled());
    assertEquals(Viewport.SCROLLBAR_RANGE + 40, viewport.scrollbarMaximum());
    viewport.scrollToLine(Long.MAX_VALUE);
    assertEquals(Viewport.SCROLLBAR_RANGE, viewport.scrollbarSelection());
    viewport.setLineCount(Viewport.SCROLLBAR_RANGE + 41L);
    assertTrue(viewport.scaled());
  }

  @Test
  void scaledEndpointsAreExact() {
    long[] counts = {(1L << 31) + 5, (1L << 32) + 12345, TRILLION, 1L << 52};
    for (long count : counts) {
      Viewport viewport = viewport(count, 50);
      long maxTop = count - 50;
      assertTrue(viewport.scaled());
      assertEquals(
          Viewport.SCROLLBAR_RANGE, viewport.scrollbarMaximum() - viewport.scrollbarThumb());
      viewport.scrollToLine(0);
      assertEquals(0, viewport.scrollbarSelection());
      viewport.scrollToLine(maxTop);
      assertEquals(Viewport.SCROLLBAR_RANGE, viewport.scrollbarSelection());
      viewport.setScrollbarSelection(0);
      assertEquals(0, viewport.topLine());
      viewport.setScrollbarSelection(Viewport.SCROLLBAR_RANGE);
      assertEquals(maxTop, viewport.topLine());
      viewport.setScrollbarSelection(Integer.MAX_VALUE);
      assertEquals(maxTop, viewport.topLine());
    }
  }

  @Test
  void scaledMappingIsMonotonicBothWays() {
    Viewport viewport = viewport(TRILLION, 50);
    long previous = -1;
    for (int selection = 0; selection <= Viewport.SCROLLBAR_RANGE; selection += 1 << 16) {
      viewport.setScrollbarSelection(selection);
      assertTrue(viewport.topLine() >= previous, "selection " + selection);
      previous = viewport.topLine();
    }
    int previousSelection = -1;
    long step = viewport.maxTop() / 4096;
    for (long top = 0; top <= viewport.maxTop(); top += step) {
      viewport.scrollToLine(top);
      assertTrue(viewport.scrollbarSelection() >= previousSelection, "top " + top);
      previousSelection = viewport.scrollbarSelection();
    }
  }

  @Test
  void scaledRoundTripIsWithinOneScrollUnit() {
    long[] counts = {(1L << 31) + 7, TRILLION};
    Random random = new Random(4);
    for (long count : counts) {
      Viewport viewport = viewport(count, 50);
      long unit = (viewport.maxTop() + Viewport.SCROLLBAR_RANGE - 1) / Viewport.SCROLLBAR_RANGE;
      for (int i = 0; i < 10_000; i++) {
        long top = (long) (random.nextDouble() * viewport.maxTop());
        viewport.scrollToLine(top);
        viewport.setScrollbarSelection(viewport.scrollbarSelection());
        assertTrue(Math.abs(viewport.topLine() - top) <= unit, top + " -> " + viewport.topLine());
      }
    }
  }

  @Test
  void scaledThumbHasAFloorOfOne() {
    Viewport viewport = viewport(TRILLION, 50);
    assertEquals(1, viewport.scrollbarThumb());
    viewport = viewport((1L << 31) + 50, 50);
    assertEquals(25, viewport.scrollbarThumb());
  }

  @Test
  void scrollingClampsAndSaturates() {
    Viewport viewport = viewport(TRILLION, 50);
    viewport.scrollBy(Long.MAX_VALUE);
    assertEquals(TRILLION - 50, viewport.topLine());
    viewport.scrollBy(-Long.MAX_VALUE);
    assertEquals(0, viewport.topLine());
    viewport.ensureVisible(1000);
    assertEquals(951, viewport.topLine());
    viewport.ensureVisible(10);
    assertEquals(10, viewport.topLine());
    viewport.setLineCount(30);
    assertEquals(0, viewport.topLine());
    assertEquals(30, viewport.visibleLines());
  }

  @Test
  void columnsClampToTheExtent() {
    Viewport viewport = viewport(10, 5);
    viewport.setMaxColumns(200);
    viewport.scrollColumnsBy(500);
    assertEquals(120, viewport.firstColumn());
    viewport.ensureColumnVisible(3);
    assertEquals(3, viewport.firstColumn());
    viewport.setMaxColumns(50);
    assertEquals(0, viewport.firstColumn());
  }
}
