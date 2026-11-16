package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.HighlightSpans;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read pipeline of {@link LineViewController}: a scroll while a read is in flight ending on a
 * block at the new top, and a block whose spans the {@link MemoryShare#HIGHLIGHT} share refuses
 * shown without them.
 */
class LineViewControllerTest {
  private static final HighlightSet HITS =
      new HighlightSet(
          "mock-hits",
          "Hits",
          List.of(HighlightRule.matchOnly("hit", MatchMode.LITERAL, null, Rgb.of(0xff0000))));

  @TempDir Path temp;

  private static void awaitTop(ControllerFixture fixture, final LineViewController view, long top)
      throws InterruptedException {
    final long wanted = top;
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.lines().count() > 0 && view.lines().firstLine() == wanted;
          }
        });
  }

  @Test
  void aScrollDuringAReadEndsOnTheNewTop() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(fixture.lines("read.log", 1000));
      fixture.runUntilIdle();
      awaitTop(fixture, document, 0);
      document.scrollLines(100);
      // The first read's result has not run on the UI thread, so this scroll marks a read wanted.
      document.scrollLines(100);
      assertEquals(200, document.viewport().topLine());
      awaitTop(fixture, document, 200);
      assertEquals("line 200", document.lines().text(200));
    }
  }

  @Test
  void spansTheShareRefusesAreDropped() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      final DocumentController document = fixture.open(fixture.lines("spans.log", 1000));
      fixture.application.setHighlightSettings(
          HighlightSettings.DEFAULTS.withSets(List.of(HITS)).withActive(List.of(HITS.id())));
      // Line 3 says hit.
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return document.lines().contains(3) && !document.lines().spans(3).isEmpty();
            }
          });
      // The whole limit, so the share stays full while older blocks release their charges.
      long held = fixture.budget.limit(MemoryShare.HIGHLIGHT);
      fixture.budget.charge(MemoryShare.HIGHLIGHT, held);
      try {
        document.scrollLines(1);
        awaitTop(fixture, document, 1);
        assertSame(HighlightSpans.NONE, document.lines().spans(3));
        assertEquals(0, document.lines().spanBytes());
      } finally {
        fixture.budget.release(MemoryShare.HIGHLIGHT, held);
      }
      document.scrollLines(-1);
      awaitTop(fixture, document, 0);
      assertFalse(document.lines().spans(3).isEmpty());
    }
  }
}
