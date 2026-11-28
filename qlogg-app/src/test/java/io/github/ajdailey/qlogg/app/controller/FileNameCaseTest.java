package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plain opens typed in another case where file names ignore case: the document takes the on-disk
 * spelling, so the open finds the file's tab and the title, recent list and position store use the
 * real name. The Mac stub runs the cases on a case-sensitive file system; the others run only on a
 * case-insensitive one.
 */
class FileNameCaseTest {
  private static final OsTraits MAC = OsTraits.of("Mac OS X");

  @TempDir Path temp;
  private ControllerFixture fixture;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private Path file() throws Exception {
    Path logs = Files.createDirectories(temp.resolve("logs"));
    return logs.resolve("app.log");
  }

  private Path typed() {
    return temp.resolve("LOGS").resolve("APP.LOG");
  }

  private void assumeCaseInsensitive() {
    assumeTrue(Files.exists(typed()), "names keep their case here");
  }

  @Test
  void aPlainOpenInAnotherCaseFindsTheOpenTab() throws Exception {
    Path file = fixture(MAC);
    plainOpen(file);
  }

  @Test
  void aPlainOpenInAnotherCaseOnACaseInsensitiveFileSystem() throws Exception {
    Path file = fixture(null);
    assumeCaseInsensitive();
    plainOpen(file);
  }

  @Test
  void aHandoffInAnotherCaseShowsTheOpenTab() throws Exception {
    Path file = fixture(MAC);
    handoff(file);
  }

  @Test
  void aHandoffInAnotherCaseOnACaseInsensitiveFileSystem() throws Exception {
    Path file = fixture(null);
    assumeCaseInsensitive();
    handoff(file);
  }

  @Test
  void aPositionSavedInAnotherCaseIsRestored() throws Exception {
    Path file = fixture(MAC);
    position(file);
  }

  @Test
  void aPositionSavedInAnotherCaseOnACaseInsensitiveFileSystem() throws Exception {
    Path file = fixture(null);
    assumeCaseInsensitive();
    position(file);
  }

  /** Writes the file and starts the application with {@code os}, or the host's rule for null. */
  private Path fixture(OsTraits os) throws Exception {
    Path file = file();
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 60; i++) {
      text.append("line ").append(i).append('\n');
    }
    Files.writeString(file, text.toString());
    fixture =
        os == null
            ? new ControllerFixture(temp, Preferences.DEFAULTS)
            : new ControllerFixture(temp, Preferences.DEFAULTS, os);
    return file;
  }

  private DocumentController openTyped() throws Exception {
    fixture.application.open(List.of(new FileRequest(typed(), 0)));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null && !shown.state().indexing();
          }
        });
    return fixture.application.activeDocument();
  }

  private void plainOpen(Path file) throws Exception {
    DocumentController tab = openTyped();
    assertEquals(file, tab.document().path());
    assertEquals("app.log", tab.state().title());
    fixture.application.open(List.of(new FileRequest(file, 0)));
    fixture.application.open(List.of(new FileRequest(typed(), 0)));
    fixture.runUntilIdle();
    assertEquals(List.of(tab), fixture.application.documents());
    assertEquals(List.of(file.toString()), fixture.application.preferences().state().recentFiles());
  }

  private void handoff(Path file) throws Exception {
    DocumentController tab = fixture.open(file);
    WindowSet set = fixture.application.windowSet();
    set.openHandoff(List.of(new FileRequest(typed(), 30)), false);
    final DocumentController shown = tab;
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.sourceLine(shown.selection().caret().line()) == 29;
          }
        });
    assertEquals(List.of(tab), fixture.application.documents());
  }

  private void position(Path file) throws Exception {
    fixture.application.windowSet().config().positions().put(typed(), 10, 20, 0);
    final DocumentController tab = openTyped();
    assertEquals(file, tab.document().path());
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.selection().caret().line() == 20;
          }
        });
    assertEquals(1, fixture.application.windowSet().config().positions().size());
  }
}
