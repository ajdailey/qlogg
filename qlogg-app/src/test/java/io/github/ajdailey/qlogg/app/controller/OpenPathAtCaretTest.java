package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File > Open Path at Caret through {@link OpenPathAtCaret}, driven through the application. */
class OpenPathAtCaretTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  private ControllerFixture fixture;

  @BeforeEach
  void create() {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  /** Writes a file of numbered lines, {@code row 1} to {@code row count}. */
  private Path numbered(Path file, int count) throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= count; i++) {
      text.append("row ").append(i).append('\n');
    }
    Files.createDirectories(file.getParent());
    return Files.writeString(file, text, StandardCharsets.US_ASCII);
  }

  private DocumentController log(String text) throws Exception {
    return fixture.open(Files.writeString(temp.resolve("app.log"), text, StandardCharsets.UTF_8));
  }

  /** Puts the caret of a view on a line and column, then runs the command and waits for it. */
  private void openAt(LineViewController view, long row, long column) throws Exception {
    fixture.application.setFocusedView(view);
    view.select(Selection.caretAt(new TextPosition(row, column)));
    final int before = fixture.view.activations.get();
    fixture.application.execute(CommandId.OPEN_PATH_AT_CARET);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.view.activations.get() > before && fixture.view.active.get() == 0;
          }
        });
  }

  /** Waits until the shown tab is a file's, indexed, with its caret on a 0-based line. */
  private DocumentController awaitShown(final Path file, final long line) throws Exception {
    final Path real = file.toRealPath();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null
                && real.equals(shown.document().realPath())
                && !shown.state().indexing()
                && shown.selection().caret().line() == line;
          }
        });
    return fixture.application.activeDocument();
  }

  private String lastNotice() {
    return fixture.view.lastNotice();
  }

  @Test
  void aRelativePathOpensBesideTheLogAtItsLine() throws Exception {
    Path target = numbered(temp.resolve("sub").resolve("t.log"), 9);
    DocumentController log = log("see sub/t.log:3:4 there\n");
    openAt(log, 0, 6);
    awaitShown(target, 2);
    assertEquals(2, fixture.application.documents().size());
    assertNull(lastNotice());
  }

  @Test
  void anAbsolutePathOpensAndAnOpenFileShowsItsTab() throws Exception {
    Path target = numbered(temp.resolve("abs.log"), 9);
    String absolute = target.toAbsolutePath().toString();
    DocumentController log = log("\"" + absolute + ":5\" and again \"" + absolute + "\"\n");
    openAt(log, 0, 2);
    DocumentController opened = awaitShown(target, 4);
    fixture.application.activate(log);
    openAt(log, 0, absolute.length() + 17);
    assertSame(opened, awaitShown(target, 4));
    assertEquals(2, fixture.application.documents().size());
  }

  @Test
  void aJavaFrameResolvesBesideTheLog() throws Exception {
    Path source = numbered(temp.resolve("Foo.java"), 50);
    DocumentController log = log("\tat io.mock.Foo.run(Foo.java:42)\n");
    // The tab takes columns 0 to 7, so column 29 is the second char of Foo.java.
    openAt(log, 0, LineReader.TAB_WIDTH + 21);
    awaitShown(source, 41);
  }

  @Test
  void aMissingFileAUrlAndNoPathShowANotice() throws Exception {
    DocumentController log = log("missing.log https://example.invalid/x  \n");
    openAt(log, 0, 3);
    assertEquals(
        UiText.OPEN_PATH_MISSING.format(temp.toAbsolutePath().resolve("missing.log")),
        lastNotice());
    openAt(log, 0, 20);
    assertEquals(UiText.OPEN_PATH_URL.format("https://example.invalid/x"), lastNotice());
    openAt(log, 0, 39);
    assertEquals(UiText.OPEN_PATH_NONE.text(), lastNotice());
    Files.createDirectory(temp.resolve("folder"));
    DocumentController other = fixture.open(Files.writeString(temp.resolve("b.log"), "folder\n"));
    openAt(other, 0, 2);
    assertEquals(
        UiText.OPEN_PATH_MISSING.format(temp.toAbsolutePath().resolve("folder")), lastNotice());
    assertEquals(2, fixture.application.documents().size());
  }

  /**
   * A path naming a Windows device shows the device reason on Windows and a missing file elsewhere.
   * OBERON G1 SM9: the open notice read "the file does not exist".
   */
  @Test
  void aWindowsDeviceNameShowsTheDeviceReason() throws Exception {
    Path logs = Files.createDirectory(temp.resolve("logs"));
    DocumentController log = log("logs/nul.txt\n");
    openAt(log, 0, 2);
    assertEquals(
        File.separatorChar == '\\'
            ? UiText.OPEN_PATH_REFUSED.format(UiText.OPEN_REASON_DEVICE.text())
            : UiText.OPEN_PATH_MISSING.format(logs.toAbsolutePath().resolve("nul.txt")),
        lastNotice());
  }

  @Test
  void clipboardTextOpensAbsolutePathsOnly() throws Exception {
    Path target = numbered(temp.resolve("w.log"), 3);
    fixture.view.clipboard = "w.log\n" + target.toAbsolutePath() + ":2\n";
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
    openAt(pasted, 0, 1);
    assertEquals(UiText.OPEN_PATH_NO_FOLDER.format("w.log"), lastNotice());
    openAt(pasted, 1, 3);
    awaitShown(target, 1);
  }

  @Test
  void theFilteredViewOpensThePathOnItsCaretLine() throws Exception {
    Path target = numbered(temp.resolve("f.log"), 9);
    DocumentController log = log("plain\nhit f.log:7\nplain\n");
    log.search().run("hit", LITERAL);
    fixture.runUntilEnded(log.search());
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return log.filtered().viewport().lineCount() == 1;
          }
        });
    openAt(log.filtered(), 0, 6);
    awaitShown(target, 6);
  }

  @Test
  void aNewerRunDropsAnOlderResult() throws Exception {
    numbered(temp.resolve("first.log"), 3);
    Path second = numbered(temp.resolve("second.log"), 3);
    DocumentController log = log("first.log second.log\n");
    fixture.application.setFocusedView(log);
    log.select(Selection.caretAt(new TextPosition(0, 2)));
    fixture.application.execute(CommandId.OPEN_PATH_AT_CARET);
    log.select(Selection.caretAt(new TextPosition(0, 12)));
    fixture.application.execute(CommandId.OPEN_PATH_AT_CARET);
    awaitShown(second, 0);
    fixture.runUntilIdle();
    fixture.ui.runPending();
    assertEquals(2, fixture.application.documents().size());
    assertEquals(List.of(), fixture.view.notices);
  }
}
