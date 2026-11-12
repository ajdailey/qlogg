package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Tab context commands, path commands, dropped files and open from clipboard. */
class TabCommandsTest {
  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private long pasted() {
    return fixture.budget.limit(MemoryShare.PASTED) - fixture.budget.available(MemoryShare.PASTED);
  }

  private List<DocumentController> openThree() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    fixture.open(fixture.lines("a.log", 3));
    fixture.open(fixture.lines("b.log", 3));
    fixture.open(fixture.lines("c.log", 3));
    return List.copyOf(fixture.application.documents());
  }

  @Test
  void closeOthersAndEachSide() throws Exception {
    List<DocumentController> tabs = openThree();
    fixture.application.closeSide(tabs.get(1), true);
    assertEquals(List.of(tabs.get(1), tabs.get(2)), fixture.application.documents());
    fixture.application.closeSide(tabs.get(1), false);
    assertEquals(List.of(tabs.get(1)), fixture.application.documents());
    assertTrue(tabs.get(0).isClosed() && tabs.get(2).isClosed());
    assertSame(tabs.get(1), fixture.application.activeDocument());
  }

  @Test
  void closeOthersKeepsOneTab() throws Exception {
    List<DocumentController> tabs = openThree();
    fixture.application.closeOthers(tabs.get(0));
    assertEquals(List.of(tabs.get(0)), fixture.application.documents());
    assertSame(tabs.get(0), fixture.application.activeDocument());
  }

  @Test
  void closeMultipleTabsActsOnTheActiveTab() throws Exception {
    List<DocumentController> tabs = openThree();
    CommandStates states = new CommandStates(fixture.application);
    fixture.application.activate(tabs.get(1));
    assertTrue(states.enabled(CommandId.CLOSE_LEFT) && states.enabled(CommandId.CLOSE_RIGHT));
    fixture.application.execute(CommandId.CLOSE_RIGHT);
    assertEquals(List.of(tabs.get(0), tabs.get(1)), fixture.application.documents());
    assertFalse(states.enabled(CommandId.CLOSE_RIGHT));
    fixture.application.execute(CommandId.CLOSE_LEFT);
    assertEquals(List.of(tabs.get(1)), fixture.application.documents());
    assertFalse(states.enabled(CommandId.CLOSE_OTHERS));
    assertFalse(states.enabled(CommandId.CLOSE_LEFT));
    fixture.open(fixture.lines("d.log", 3));
    assertTrue(states.enabled(CommandId.CLOSE_OTHERS));
    fixture.application.execute(CommandId.CLOSE_OTHERS);
    assertEquals(1, fixture.application.documents().size());
    assertTrue(tabs.get(1).isClosed());
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
  void closeAllShowsNoTabOnTheWay() throws Exception {
    List<DocumentController> tabs = openThree();
    fixture.application.activate(tabs.get(0));
    List<DocumentController> shown = recordShown();
    fixture.application.closeAll();
    assertTrue(fixture.application.documents().isEmpty());
    assertEquals(Collections.singletonList(null), shown);
  }

  @Test
  void closeOthersShowsOnlyTheTabLeft() throws Exception {
    List<DocumentController> tabs = openThree();
    fixture.application.activate(tabs.get(0));
    List<DocumentController> shown = recordShown();
    fixture.application.closeOthers(tabs.get(2));
    assertEquals(List.of(tabs.get(2)), shown);
    assertEquals(List.of(tabs.get(2)), fixture.application.documents());
  }

  @Test
  void closeSideShowsOnlyTheTabLeft() throws Exception {
    List<DocumentController> tabs = openThree();
    fixture.application.activate(tabs.get(0));
    List<DocumentController> shown = recordShown();
    fixture.application.closeSide(tabs.get(2), true);
    assertEquals(List.of(tabs.get(2)), shown);
    assertEquals(List.of(tabs.get(2)), fixture.application.documents());
  }

  @Test
  void renameKeepsTheNamePerPathUntilReset() throws Exception {
    List<DocumentController> tabs = openThree();
    DocumentController first = tabs.get(0);
    fixture.application.renameTab(first);
    assertEquals("a.log", fixture.view.suggestedName);
    fixture.view.nameListener.nameChosen("  gateway  ");
    assertEquals("gateway", fixture.application.tabTitle(first));
    assertEquals("a.log", first.state().title());
    fixture.application.resetTabName(first);
    assertEquals("a.log", fixture.application.tabTitle(first));
  }

  @Test
  void pathCommandsUseTheShownFile() throws Exception {
    List<DocumentController> tabs = openThree();
    Path file = tabs.get(2).document().path();
    fixture.application.execute(CommandId.COPY_PATH);
    assertEquals(file.toString(), fixture.view.clipboard);
    fixture.application.execute(CommandId.OPEN_FOLDER);
    assertEquals(List.of(file.getParent()), fixture.view.launched);
    fixture.application.execute(CommandId.OPEN_IN_EDITOR);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.view.launched.size() == 2;
          }
        });
    assertEquals(file, fixture.view.launched.get(1));
    fixture.view.launchResult = false;
    fixture.application.execute(CommandId.OPEN_FOLDER);
    assertTrue(fixture.view.notices.contains("No application opened it"));
  }

  @Test
  void programsAreNotOpenedInAnEditor() throws Exception {
    assertTrue(ApplicationController.runsInsteadOfOpening(Path.of("setup.EXE"), true));
    assertTrue(ApplicationController.runsInsteadOfOpening(Path.of("run.bat"), true));
    assertFalse(ApplicationController.runsInsteadOfOpening(Path.of("app.log"), true));
  }

  @Test
  void theViewDecidesWhetherTheExtensionRefusesAFile() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    fixture.open(fixture.lines("run.bat", 3));
    fixture.view.byExtension = true;
    fixture.application.execute(CommandId.OPEN_IN_EDITOR);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.view.notices.contains("run.bat is a program; it is not opened");
          }
        });
    assertEquals(List.of(), fixture.view.launched);
  }

  /** Windows reports every readable file as executable, so the POSIX check runs elsewhere. */
  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aPlainFileIsNotAProgramOnPosix() throws Exception {
    Path log = Files.writeString(temp.resolve("plain.log"), "x\n");
    assertFalse(ApplicationController.runsInsteadOfOpening(log, false));
  }

  @Test
  void droppedFilesOpenATabEach() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    final Path a = fixture.lines("drop-a.log", 2);
    Path b = fixture.lines("drop-b.log", 2);
    fixture.application.openDropped(List.of(a, b));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.application.documents().size() == 2;
          }
        });
    assertEquals("drop-b.log", fixture.application.activeDocument().state().title());
  }

  @Test
  void droppedFoldersAreSkippedWithANotice() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    Path folder = Files.createDirectory(temp.resolve("folder"));
    Path a = fixture.lines("drop-a.log", 2);
    fixture.application.openDropped(List.of(folder, a));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.view.notices.contains("Folders are not opened");
          }
        });
    assertEquals(1, fixture.application.documents().size());
    assertEquals("drop-a.log", fixture.application.activeDocument().state().title());
    assertEquals(List.of(), fixture.view.errors);
  }

  @Test
  void clipboardTextOpensInMemoryWithoutFileCommands() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    fixture.application.execute(CommandId.OPEN_CLIPBOARD);
    assertEquals(List.of("The clipboard holds no text"), fixture.view.notices);
    fixture.view.clipboard = "first\nsecond\nthird";
    fixture.application.execute(CommandId.OPEN_CLIPBOARD);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null && !shown.state().indexing();
          }
        });
    DocumentController pasted = fixture.application.activeDocument();
    assertTrue(pasted.document().inMemory());
    assertNull(pasted.document().path());
    assertEquals("Clipboard 1", pasted.state().title());
    assertEquals("", pasted.state().path());
    assertEquals(3, pasted.viewport().lineCount());
    assertEquals(18, pasted());
    pasted.setFollowing(true);
    assertFalse(pasted.following());
    fixture.view.notices.clear();
    fixture.application.execute(CommandId.RELOAD);
    fixture.application.execute(CommandId.COPY_PATH);
    assertEquals(
        List.of("Text from the clipboard has no file", "Text from the clipboard has no file"),
        fixture.view.notices);
    assertEquals("first\nsecond\nthird", fixture.view.clipboard);
    fixture.application.closeDocument(pasted);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pasted() == 0;
          }
        });
  }
}
