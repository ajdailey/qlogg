package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ColumnSettings;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.core.model.ColumnLayout;
import io.github.ajdailey.qlogg.core.text.PathAtCaret;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tab's column layout from the stored settings: stored widths, visibility and frozen count, the
 * first rows read fitting the rest, edits stored and rebuilt, reset, the shown names moved first,
 * and the path Open Path at Caret takes from a cell value.
 */
class ColumnSizingTest {
  private static final String CSV =
      "id,level,message\n1,INFO,mock first\n2,WARN,mock second message\n";

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private DocumentController open(Preferences preferences) throws Exception {
    fixture = new ControllerFixture(temp, preferences);
    Path file = temp.resolve("sizes.csv");
    Files.writeString(file, CSV, StandardCharsets.UTF_8);
    final DocumentController document = fixture.open(file);
    document.viewportResized(10, 120);
    fitted(document);
    return document;
  }

  private void fitted(final DocumentController document) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            ColumnLayout layout = document.columns().layout();
            return layout != null
                && !layout.unfitted()
                && document.lines().cellView()
                && document.lines().contains(0)
                && document.lines().width(0) == layout.width();
          }
        });
  }

  private static Preferences with(ColumnSettings settings) {
    return Preferences.DEFAULTS.withView(Preferences.DEFAULTS.view().withColumns(settings));
  }

  private ColumnSettings.Format stored() {
    return fixture.application.preferences().view().columns().format("csv");
  }

  @Test
  void storedSettingsBuildTheLayoutAndTheFirstRowsFitTheRest() throws Exception {
    ColumnSettings settings =
        ColumnSettings.DEFAULTS.with(
            "csv",
            ColumnSettings.Format.EMPTY
                .with(new ColumnSettings.Entry("message", 20, ColumnSettings.Visibility.DEFAULT))
                .with(new ColumnSettings.Entry("level", 0, ColumnSettings.Visibility.HIDDEN))
                .withFrozen(1));
    DocumentController document = open(with(settings));
    ColumnLayout layout = document.columns().layout();
    assertEquals(2, layout.count());
    assertEquals(0, layout.column(0));
    assertEquals(3, layout.width(0));
    assertEquals(20, layout.width(1));
    assertEquals(1, layout.frozen());
    assertTrue(document.lines().text(0).startsWith("1  mock first"));
  }

  @Test
  void editsAreStoredAndRebuildTheLayout() throws Exception {
    final DocumentController document = open(Preferences.DEFAULTS);
    ColumnSizing sizing = document.columns().sizing();
    assertEquals(20, document.columns().layout().width(2));
    sizing.resize(2, 1);
    assertEquals(ColumnLayout.MIN_WIDTH, document.columns().layout().width(2));
    assertEquals(ColumnLayout.MIN_WIDTH, stored().entry("message").width());
    sizing.hide(0);
    sizing.hide(0);
    assertEquals(1, document.columns().layout().count());
    sizing.hide(0);
    assertEquals(1, document.columns().layout().count());
    sizing.setShown(1, true);
    assertEquals(2, document.columns().layout().count());
    assertNull(stored().entry("level"));
    assertEquals(ColumnSettings.Visibility.HIDDEN, stored().entry("id").visibility());
    sizing.freeze(1);
    assertEquals(2, document.columns().layout().frozen());
    assertEquals(2, stored().frozen());
    sizing.freeze(-1);
    assertEquals(0, stored().frozen());
    sizing.reset();
    assertEquals(ColumnSettings.Format.EMPTY, stored());
    assertEquals(3, document.columns().layout().count());
    assertTrue(document.columns().layout().unfitted());
    fitted(document);
    assertEquals(20, document.columns().layout().width(2));
  }

  @Test
  void openingMovesTheShownNamesFirst() throws Exception {
    ColumnSettings.Format format = ColumnSettings.Format.EMPTY;
    format = format.with(new ColumnSettings.Entry("id", 9, ColumnSettings.Visibility.DEFAULT));
    for (int i = 0; i < ColumnSettings.MAX_NAMES - 1; i++) {
      format =
          format.with(new ColumnSettings.Entry("other" + i, 4, ColumnSettings.Visibility.DEFAULT));
    }
    assertEquals("id", format.entries().get(ColumnSettings.MAX_NAMES - 1).name());
    open(with(ColumnSettings.DEFAULTS.with("csv", format)));
    assertEquals("id", stored().entries().get(0).name());
    ColumnSettings.Format next =
        stored().with(new ColumnSettings.Entry("new", 5, ColumnSettings.Visibility.DEFAULT));
    assertEquals(9, next.entry("id").width());
    assertNull(next.entry("other0"));
  }

  @Test
  void openPathTakesTheFirstRunHoldingASeparator() {
    PathAtCaret.Found found = OpenPathAtCaret.firstPath("see mock/dir/a.log:12 now");
    assertEquals("mock/dir/a.log", found.path());
    assertEquals(12, found.line());
    assertEquals("plain", OpenPathAtCaret.firstPath("  plain words").path());
    assertNull(OpenPathAtCaret.firstPath(""));
    assertFalse(OpenPathAtCaret.firstPath("C:\\mock\\x.txt").url());
  }
}
