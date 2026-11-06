package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.OverviewListener;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.HighlightSpans;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.OverviewBins;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Highlighters, match highlight, color labels and the overview bar, without SWT. */
class HighlightControllerTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);
  private static final Rgb RED = Rgb.of(0xff0000);
  private static final HighlightSet SET =
      new HighlightSet(
          "mock-set",
          "Mock",
          List.of(HighlightRule.matchOnly("line", MatchMode.LITERAL, null, RED)));

  @TempDir Path temp;

  private ControllerFixture fixture;
  private DocumentController document;

  @BeforeEach
  void open() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    document = fixture.open(fixture.lines("h.log", 40));
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  /** Drives the queue until a row arrives with colors. */
  private HighlightSpans spansOf(final LineViewController view, final long row)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.lines().contains(row) && !view.lines().spans(row).isEmpty();
          }
        });
    return view.lines().spans(row);
  }

  @Test
  void anActiveSetColorsBothViewsAndIsCharged() throws Exception {
    assertSame(Highlighter.NONE, document.highlighter());
    fixture.application.setHighlightSettings(
        HighlightSettings.DEFAULTS.withSets(List.of(SET)).withActive(List.of(SET.id())));
    assertEquals(1, document.highlighter().rules().size());
    assertSame(document.highlighter(), document.filtered().highlighter());
    HighlightSpans spans = spansOf(document, 0);
    assertEquals(1, spans.count());
    assertEquals(0, spans.start(0));
    assertEquals(4, spans.end(0));
    assertTrue(fixture.budget.reserved(MemoryShare.HIGHLIGHT) >= spans.retainedBytes());
    fixture.application.toggleHighlightSet(SET.id());
    assertSame(Highlighter.NONE, document.highlighter());
    fixture.application.toggleHighlightSet(SET.id());
    fixture.application.execute(CommandId.NO_HIGHLIGHTERS);
    assertTrue(fixture.application.highlightSettings().active().isEmpty());
  }

  @Test
  void matchHighlightFollowsTheSearchAndThePalette() throws Exception {
    document.search().run("hit", LITERAL);
    fixture.runUntilEnded(document.search());
    HighlightRule match = document.highlighter().rules().get(0);
    assertEquals("hit", match.pattern());
    assertEquals(Palette.LIGHT.match(), match.background());
    fixture.application.execute(CommandId.THEME_DARK);
    assertEquals(Palette.DARK.match(), document.highlighter().rules().get(0).background());
    assertEquals(ThemeMode.DARK, fixture.application.preferences().theme());
    HighlightSpans spans = spansOf(document.filtered(), 0);
    assertEquals(1, spans.count());
    fixture.application.execute(CommandId.TOGGLE_MATCH_HIGHLIGHT);
    assertSame(Highlighter.NONE, document.highlighter());
    fixture.application.execute(CommandId.TOGGLE_MATCH_HIGHLIGHT);
    document.search().run("hit", LITERAL.withInverse(true));
    fixture.runUntilEnded(document.search());
    assertSame(Highlighter.NONE, document.highlighter());
  }

  @Test
  void colorLabelsApplyCycleAndClear() throws Exception {
    fixture.application.setFocusedView(document);
    document.select(new Selection(new TextPosition(0, 0), new TextPosition(0, 4)));
    fixture.application.execute(CommandId.NEXT_COLOR_LABEL);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.labels().size() == 1;
          }
        });
    assertEquals(0, document.labels().labelOf("line"));
    fixture.application.execute(CommandId.COLOR_LABEL_3);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.labels().labelOf("line") == 2;
          }
        });
    assertEquals("line", document.highlighter().rules().get(0).pattern());
    fixture.application.execute(CommandId.CLEAR_COLOR_LABELS);
    assertEquals(0, document.labels().size());
    assertEquals(-1, document.labels().current());
    assertSame(Highlighter.NONE, document.highlighter());
  }

  @Test
  void overviewBinsArriveAndAClickShowsTheLine() throws Exception {
    document.search().run("hit", LITERAL);
    fixture.runUntilEnded(document.search());
    final OverviewController overview = document.overview();
    final AtomicInteger changes = new AtomicInteger();
    overview.addListener(
        fixture.ui.confined(
            OverviewListener.class,
            new OverviewListener() {
              @Override
              public void binsChanged() {
                changes.incrementAndGet();
              }
            }));
    overview.setGeometry(40, true);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return changes.get() > 0 && overview.bins().height() == 40;
          }
        });
    OverviewBins bins = overview.bins();
    assertEquals(1, bins.matches()[3]);
    assertEquals(0, bins.matches()[4]);
    overview.jumpTo(31);
    assertEquals(31, document.selection().caret().line());
    overview.setGeometry(40, false);
    overview.jumpTo(0);
    assertEquals(0, document.selection().caret().line());
  }

  /** Records the ends of a highlighter file task. */
  private static final class FileOutcome implements SettingsFileListener<HighlightSet> {
    private final List<List<HighlightSet>> done = new ArrayList<List<HighlightSet>>();
    private final List<String> failed = new ArrayList<String>();

    @Override
    public void done(List<HighlightSet> sets) {
      done.add(sets);
    }

    @Override
    public void failed(String message) {
      failed.add(message);
    }

    BooleanSupplier ended(final int count) {
      return new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
          return done.size() + failed.size() == count;
        }
      };
    }
  }

  @Test
  void highlighterFilesRoundTripAndReportFailures() throws Exception {
    Path file = temp.resolve("sets.conf");
    FileOutcome outcome = new FileOutcome();
    fixture.application.exportHighlights(file, List.of(SET), outcome);
    fixture.runUntil(outcome.ended(1));
    assertEquals(List.of(SET), outcome.done.get(0));
    fixture.application.importHighlights(file, outcome);
    fixture.runUntil(outcome.ended(2));
    assertEquals(List.of(SET), outcome.done.get(1));

    Files.writeString(file, "not a highlighter export\n");
    fixture.application.importHighlights(file, outcome);
    fixture.runUntil(outcome.ended(3));
    assertEquals(UiText.HIGHLIGHT_FILE_NOT_EXPORT.text(), outcome.failed.get(0));

    fixture.application.exportHighlights(temp, List.of(SET), outcome);
    fixture.runUntil(outcome.ended(4));
    assertEquals(2, outcome.failed.size());
    assertEquals(2, outcome.done.size());
  }

  @Test
  void aShutdownDropsAHighlighterImportThatPostedBack() throws Exception {
    Path file = temp.resolve("held.conf");
    FileOutcome outcome = new FileOutcome();
    fixture.application.exportHighlights(file, List.of(SET), outcome);
    fixture.runUntil(outcome.ended(1));
    fixture.ui.runPending();
    fixture.application.importHighlights(file, outcome);
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return fixture.ui.queued() > 0;
              }
            },
            Duration.ofSeconds(20)));
    fixture.close();
    assertTrue(outcome.ended(1).getAsBoolean());
  }
}
