package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tab data states driven by real growth, searches, reloads and closes; the file monitor runs for
 * real and the UI queue is drained by the test.
 */
class TabDataModelTest {
  private static final SearchOptions AUTO_REFRESH =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, true, false);

  @TempDir Path temp;

  private ControllerFixture fixture;
  private TabDataModel model;
  private final List<String> events = new ArrayList<String>();
  private DocumentController background;
  private DocumentController shown;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  /** Opens {@code a.log} (20 lines, hits at 3, 10 and 17), then {@code b.log}, which is shown. */
  private void openTwo(boolean searchFirst) throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    model = fixture.application.tabData();
    model.addListener(
        new TabDataListener() {
          @Override
          public void tabDataChanged(DocumentController document, TabDataState state) {
            events.add(document.state().title() + " " + state);
          }
        });
    background = fixture.open(fixture.lines("a.log", 20));
    if (searchFirst) {
      background.search().run("hit", AUTO_REFRESH);
      fixture.runUntilEnded(background.search());
    }
    shown = fixture.open(fixture.lines("b.log", 5));
  }

  private void append(String name, String text) throws Exception {
    Files.writeString(
        temp.resolve(name), text, StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
  }

  private void awaitLines(final DocumentController document, final long count)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.state().lineCount() == count && !document.state().indexing();
          }
        });
  }

  private void awaitState(final DocumentController document, final TabDataState state)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return model.state(document) == state;
          }
        });
  }

  /** Waits for a reload or reopen of the first tab and returns the replacement once indexed. */
  private DocumentController replacementOf(final DocumentController old)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController first = fixture.application.documents().get(0);
            return first != old && !first.state().indexing();
          }
        });
    return fixture.application.documents().get(0);
  }

  @Test
  void growthOfABackgroundTabSetsNewLinesAndShowingItClears() throws Exception {
    openTwo(false);
    assertEquals(TabDataState.NONE, model.state(background), "initial indexing is not growth");
    append("b.log", "line 5\n");
    awaitLines(shown, 6);
    assertEquals(TabDataState.NONE, model.state(shown), "the shown tab stays clear");
    append("a.log", "line 20\n");
    awaitState(background, TabDataState.NEW_LINES);
    fixture.application.activate(background);
    assertEquals(TabDataState.NONE, model.state(background));
    assertEquals(List.of("a.log NEW_LINES", "a.log NONE"), events);
  }

  @Test
  void matchingGrowthWinsOverNewLinesAndSurvivesAReload() throws Exception {
    openTwo(true);
    append("a.log", "line 20\n");
    awaitState(background, TabDataState.NEW_LINES);
    append("a.log", "hit 21\n");
    awaitState(background, TabDataState.NEW_MATCHES);
    append("a.log", "line 22\n");
    awaitLines(background, 23);
    assertEquals(TabDataState.NEW_MATCHES, model.state(background));
    fixture.application.reload(background);
    DocumentController reloaded = replacementOf(background);
    assertNotSame(background, reloaded);
    assertEquals(TabDataState.NEW_MATCHES, model.state(reloaded));
    assertEquals(TabDataState.NONE, model.state(background));
    fixture.application.activate(reloaded);
    assertEquals(TabDataState.NONE, model.state(reloaded));
  }

  @Test
  void aSearchRunInABackgroundTabDoesNotSetAState() throws Exception {
    openTwo(false);
    background.search().run("hit", AUTO_REFRESH);
    fixture.runUntilEnded(background.search());
    assertEquals(3, background.search().state().matchCount());
    assertEquals(TabDataState.NONE, model.state(background));
    assertTrue(events.isEmpty(), events.toString());
  }

  @Test
  void aReloadOrTruncationOfABackgroundTabSetsNewLines() throws Exception {
    openTwo(false);
    fixture.application.reload(background);
    DocumentController reloaded = replacementOf(background);
    awaitState(reloaded, TabDataState.NEW_LINES);
    fixture.application.activate(reloaded);
    fixture.application.activate(shown);
    Files.writeString(
        temp.resolve("a.log"),
        "line 0\n",
        StandardCharsets.US_ASCII,
        StandardOpenOption.TRUNCATE_EXISTING);
    DocumentController truncated = replacementOf(reloaded);
    awaitState(truncated, TabDataState.NEW_LINES);
    assertEquals(1, truncated.state().lineCount());
  }

  @Test
  void closingATabDropsItsStateAndItsListeners() throws Exception {
    openTwo(false);
    append("a.log", "line 20\n");
    awaitState(background, TabDataState.NEW_LINES);
    fixture.application.closeDocument(background);
    assertEquals(TabDataState.NONE, model.state(background));
    assertEquals(List.of("a.log NEW_LINES"), events);
    fixture.application.reload(shown);
    DocumentController reloaded = replacementOf(shown);
    assertEquals(TabDataState.NONE, model.state(reloaded), "a reload of the shown tab");
    assertEquals(List.of("a.log NEW_LINES"), events);
  }
}
