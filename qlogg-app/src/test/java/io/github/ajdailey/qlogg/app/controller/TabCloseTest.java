package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tab shown after a close: the tab after the closed one, else the one before; with {@code
 * tabs.closeShowsLastUsed} the most recently shown tab; Close All, Close Others and Close to the
 * Left or Right showing only the tab they keep, under either setting.
 */
class TabCloseTest {
  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  /** Opens {@code t1.log} to {@code tN.log}; each open shows its tab. */
  private List<DocumentController> openTabs(int count) throws Exception {
    if (fixture == null) {
      fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    }
    for (int i = 1; i <= count; i++) {
      fixture.open(fixture.lines("t" + i + ".log", 3));
    }
    return List.copyOf(fixture.application.documents());
  }

  private void closeShowsLastUsed(boolean on) {
    fixture.application.setOptions(
        fixture.application.preferences().options().withCloseShowsLastUsed(on));
  }

  private void show(DocumentController document) {
    fixture.application.activate(document);
    assertSame(document, fixture.application.activeDocument());
  }

  private void closeShown() {
    fixture.application.execute(CommandId.CLOSE);
  }

  private void assertShown(DocumentController expected) {
    assertSame(expected, fixture.application.activeDocument());
  }

  /** Records each document shown, from now on. */
  private List<DocumentController> recordShown() {
    final List<DocumentController> shown = new ArrayList<DocumentController>();
    fixture.application.addListener(
        new ApplicationListener() {
          @Override
          public void documentOpened(DocumentController document) {
            // Not recorded.
          }

          @Override
          public void documentClosed(DocumentController document) {
            // Not recorded.
          }

          @Override
          public void activeDocumentChanged(DocumentController document) {
            shown.add(document);
          }
        });
    return shown;
  }

  @Test
  void closingTheShownTabShowsTheTabAfterItElseTheOneBefore() throws Exception {
    List<DocumentController> tabs = openTabs(5);
    show(tabs.get(0));
    closeShown();
    assertShown(tabs.get(1));
    show(tabs.get(2));
    closeShown();
    assertShown(tabs.get(3));
    show(tabs.get(4));
    closeShown();
    assertShown(tabs.get(3));
  }

  @Test
  void closingATabNotShownKeepsTheShownOne() throws Exception {
    List<DocumentController> tabs = openTabs(3);
    closeShowsLastUsed(true);
    show(tabs.get(0));
    List<DocumentController> shown = recordShown();
    fixture.application.closeDocument(tabs.get(1));
    assertShown(tabs.get(0));
    assertEquals(List.of(), shown);
  }

  @Test
  void theLastUsedSettingShowsTheMostRecentlyUsedTab() throws Exception {
    List<DocumentController> tabs = openTabs(5);
    closeShowsLastUsed(true);
    show(tabs.get(4));
    show(tabs.get(0));
    show(tabs.get(1));
    closeShown();
    assertShown(tabs.get(0));
    closeShown();
    assertShown(tabs.get(4));
    closeShowsLastUsed(false);
    show(tabs.get(2));
    show(tabs.get(3));
    closeShown();
    assertShown(tabs.get(4));
  }

  @Test
  void closeAllShowsNoOtherTabOnTheWay() throws Exception {
    for (int at = 0; at < 3; at++) {
      List<DocumentController> tabs = openTabs(3);
      closeShowsLastUsed(true);
      show(tabs.get(at));
      List<DocumentController> shown = recordShown();
      fixture.application.execute(CommandId.CLOSE_ALL);
      assertEquals(0, fixture.application.documents().size());
      assertEquals(Collections.singletonList(null), shown, "shown tab " + at);
    }
  }

  @Test
  void closeOthersFromATabNotShownShowsOnlyThatTab() throws Exception {
    List<DocumentController> tabs = openTabs(4);
    closeShowsLastUsed(true);
    show(tabs.get(3));
    show(tabs.get(0));
    List<DocumentController> shown = recordShown();
    fixture.application.closeOthers(tabs.get(2));
    assertEquals(List.of(tabs.get(2)), fixture.application.documents());
    assertEquals(List.of(tabs.get(2)), shown);
  }

  @Test
  void closeToTheLeftOrRightShowsOnlyTheKeptTab() throws Exception {
    for (int setting = 0; setting < 2; setting++) {
      for (int side = 0; side < 2; side++) {
        boolean left = side == 0;
        for (int at = 0; at < 5; at++) {
          List<DocumentController> tabs = openTabs(5);
          closeShowsLastUsed(setting == 1);
          DocumentController from = tabs.get(2);
          show(tabs.get(left ? 4 : 0));
          show(tabs.get(left ? 3 : 1));
          show(tabs.get(at));
          List<DocumentController> shown = recordShown();
          fixture.application.closeSide(from, left);
          boolean closed = left ? at < 2 : at > 2;
          String what = (setting == 1 ? "last used, " : "") + (left ? "left" : "right") + ", " + at;
          assertEquals(closed ? List.of(from) : List.of(), shown, what);
          assertShown(closed ? from : tabs.get(at));
          assertEquals(3, fixture.application.documents().size(), what);
          fixture.application.closeAll();
        }
      }
    }
  }

  @Test
  void theLastUsedOrderFollowsAReloadAndCountsAReopenedTab() throws Exception {
    List<DocumentController> tabs = openTabs(4);
    closeShowsLastUsed(true);
    show(tabs.get(1));
    final DocumentController old = tabs.get(1);
    fixture.application.reload(old);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != old && !shown.state().indexing();
          }
        });
    DocumentController reloaded = fixture.application.activeDocument();
    show(tabs.get(3));
    closeShown();
    assertShown(reloaded);
    fixture.application.execute(CommandId.REOPEN_CLOSED);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null && shown.state().title().equals("t4.log");
          }
        });
    DocumentController reopened = fixture.application.activeDocument();
    assertNotSame(tabs.get(3), reopened);
    show(tabs.get(0));
    closeShown();
    assertShown(reopened);
  }
}
