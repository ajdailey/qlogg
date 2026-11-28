package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code columns.*} keys: a round trip through {@code qlogg.conf}, the 256-name bound with the
 * least recently shown dropped first, the move to the front of shown names, invalid values, and
 * entries that store nothing.
 */
class ColumnFileTest {
  @TempDir Path temp;

  private ConfigStore store() {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp), OsTraits.of("Linux"));
  }

  private static ColumnSettings.Entry width(String name, int width) {
    return new ColumnSettings.Entry(name, width, ColumnSettings.Visibility.DEFAULT);
  }

  @Test
  void layoutsRoundTripThroughTheSettingsFile() throws IOException {
    ColumnSettings settings =
        ColumnSettings.DEFAULTS
            .with(
                "csv",
                ColumnSettings.Format.EMPTY
                    .with(width("amount", 12))
                    .with(new ColumnSettings.Entry("a=b c", 0, ColumnSettings.Visibility.HIDDEN))
                    .withFrozen(2))
            .with(
                "clef",
                ColumnSettings.Format.EMPTY.with(
                    new ColumnSettings.Entry("Template", 30, ColumnSettings.Visibility.SHOWN)));
    Preferences saved =
        Preferences.DEFAULTS.withView(Preferences.DEFAULTS.view().withColumns(settings));
    assertTrue(store().save(Preferences.DEFAULTS, saved));
    assertEquals(settings, store().load().view().columns());
    String text = Files.readString(temp.resolve("qlogg.conf"), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("columns.csv.0.name=a\\=b c"), text);
    assertTrue(text.contains("columns.csv.0.hidden=true"));
    assertTrue(text.contains("columns.csv.1.width=12"));
    assertTrue(text.contains("columns.csv.frozen=2"));
    assertTrue(text.contains("columns.clef.0.hidden=false"));
    Preferences cleared =
        saved.withView(saved.view().withColumns(settings.with("csv", ColumnSettings.Format.EMPTY)));
    assertTrue(store().save(saved, cleared));
    text = Files.readString(temp.resolve("qlogg.conf"), StandardCharsets.ISO_8859_1);
    assertTrue(!text.contains("columns.csv."), text);
    assertTrue(text.contains("columns.clef.0.name=Template"));
  }

  @Test
  void aFormatKeepsTheMostRecentlyShown256Names() {
    ColumnSettings.Format format = ColumnSettings.Format.EMPTY;
    for (int i = 0; i < 300; i++) {
      format = format.with(width("c" + i, 10));
    }
    assertEquals(ColumnSettings.MAX_NAMES, format.entries().size());
    assertEquals("c299", format.entries().get(0).name());
    assertNull(format.entry("c43"));
    assertEquals(10, format.entry("c44").width());
    ColumnSettings.Format shown = format.shown(List.of("c44", "missing", "c45"));
    assertEquals("c44", shown.entries().get(0).name());
    assertEquals("c45", shown.entries().get(1).name());
    assertSame(shown, shown.shown(List.of("c44", "c45")));
    assertEquals("c44", shown.with(width("new", 5)).entries().get(1).name());
    assertNull(shown.with(width("new", 5)).entry("c46"));
  }

  @Test
  void loadReadsAtMost256NamesAndSkipsInvalidValues() {
    Properties properties = new Properties();
    for (int i = 0; i < 300; i++) {
      properties.setProperty("columns.csv." + i + ".name", "c" + i);
      properties.setProperty("columns.csv." + i + ".width", "7");
    }
    properties.setProperty("columns.csv.1.width", "2");
    properties.setProperty("columns.csv.2.width", "wide");
    properties.setProperty("columns.csv.3.name", "c0");
    properties.setProperty("columns.csv.4.hidden", "maybe");
    properties.setProperty("columns.csv.frozen", "-1");
    properties.setProperty("columns.mock.0.name", "ignored");
    ColumnSettings loaded = ColumnFile.load(properties);
    ColumnSettings.Format csv = loaded.format("csv");
    List<String> names = new ArrayList<String>();
    for (ColumnSettings.Entry entry : csv.entries()) {
      names.add(entry.name());
    }
    assertEquals(253, names.size());
    assertEquals("c0", names.get(0));
    assertTrue(!names.contains("c1") && !names.contains("c2") && !names.contains("c256"));
    assertEquals(ColumnSettings.Visibility.DEFAULT, csv.entry("c4").visibility());
    assertEquals(0, csv.frozen());
    assertEquals(List.of("csv"), List.copyOf(loaded.formats().keySet()));
  }

  @Test
  void anEntryThatStoresNothingGoes() {
    ColumnSettings.Format format = ColumnSettings.Format.EMPTY.with(width("a", 9));
    assertEquals(ColumnSettings.Format.EMPTY, format.with(width("a", 0)));
    assertEquals(
        ColumnSettings.DEFAULTS, ColumnSettings.DEFAULTS.with("csv", ColumnSettings.Format.EMPTY));
    assertEquals(ColumnSettings.Format.EMPTY, format.withFrozen(3).without(List.of("a")));
  }
}
