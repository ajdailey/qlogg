package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a reload keeps: marks, their comments and color labels on {@code F5}, nothing after a change
 * on disk.
 */
class ReloadTest {
  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private DocumentController open(int lines) throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    return fixture.open(fixture.lines("reload.log", lines));
  }

  /** Waits for the shown tab to be another controller than {@code old} and indexed. */
  private DocumentController replacement(final DocumentController old) throws Exception {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null && shown != old && !shown.state().indexing();
          }
        });
    return fixture.application.activeDocument();
  }

  @Test
  void reloadKeepsMarksAndLabelsAndDropsMarksPastTheEnd() throws Exception {
    DocumentController document = open(60);
    MutableLineSet marks = document.document().marks();
    marks.add(5);
    marks.add(59);
    marks.add(100);
    document.applyLabel(0, "hit");
    fixture.application.execute(CommandId.RELOAD);
    final DocumentController reloaded = replacement(document);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return reloaded.marks().size() == 2;
          }
        });
    assertEquals(List.of(5L, 59L), ControllerFixture.toList(reloaded.marks()));
    assertEquals(1, reloaded.labels().size());
  }

  @Test
  void reloadKeepsMarkCommentsAndDropsThosePastTheEnd() throws Exception {
    DocumentController document = open(60);
    document.setMarkComment(5, "mock note");
    document.document().marks().add(100);
    document.document().markComments().put(100, "past the end");
    fixture.application.execute(CommandId.RELOAD);
    final DocumentController reloaded = replacement(document);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return reloaded.marks().size() == 1;
          }
        });
    assertEquals("mock note", reloaded.markComment(5));
    assertNull(reloaded.markComment(100));
    assertEquals(1, reloaded.document().markComments().size());
    Files.writeString(temp.resolve("reload.log"), "short\n", StandardOpenOption.TRUNCATE_EXISTING);
    DocumentController reopened = replacement(reloaded);
    assertNull(reopened.markComment(5));
  }

  @Test
  void aChangeOnDiskClearsMarksAndLabels() throws Exception {
    DocumentController document = open(60);
    document.document().marks().add(5);
    document.applyLabel(0, "hit");
    Files.writeString(temp.resolve("reload.log"), "short\n", StandardOpenOption.TRUNCATE_EXISTING);
    DocumentController reopened = replacement(document);
    assertEquals(0, reopened.marks().size());
    assertEquals(0, reopened.labels().size());
    assertTrue(document.isClosed());
  }
}
