package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.theme.ColorScheme;
import io.github.ajdailey.qlogg.app.theme.ColorSchemes;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemeStoreTest {
  @TempDir Path temp;

  private final List<String> warnings = new ArrayList<String>();
  private final Logger logger = Logger.getLogger(SchemeStore.class.getName());
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(LogRecord record) {
          if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
            warnings.add(record.getMessage());
          }
        }

        @Override
        public void flush() {
          // Nothing buffered.
        }

        @Override
        public void close() {
          // Nothing held.
        }
      };

  @BeforeEach
  void listen() {
    logger.setLevel(Level.WARNING);
    logger.addHandler(capture);
  }

  @AfterEach
  void stopListening() {
    logger.removeHandler(capture);
    logger.setLevel(null);
  }

  private SettingsLocation location() {
    return new SettingsLocation(SettingsMode.EXPLICIT, temp);
  }

  /** A complete scheme file: every color from the dark palette, with optional extra lines. */
  private static String complete(String extra) {
    StringBuilder text = new StringBuilder("dark=true\n");
    Palette palette = Palette.DARK;
    Rgb[] colors = colors(palette);
    for (int i = 0; i < colors.length; i++) {
      text.append(Palette.COLOR_NAMES.get(i))
          .append(
              String.format(
                  "=#%02x%02x%02x\n", colors[i].red(), colors[i].green(), colors[i].blue()));
    }
    return text.append(extra).toString();
  }

  private static Rgb[] colors(Palette p) {
    return new Rgb[] {
      p.text(),
      p.background(),
      p.selection(),
      p.selectionText(),
      p.currentLine(),
      p.lineNumbers(),
      p.gutter(),
      p.match(),
      p.mark(),
      p.resultRow(),
      p.matchMarker(),
      p.markMarker(),
      p.overview(),
      p.overviewFrame(),
      p.newLinesDot(),
      p.newMatchesDot(),
      p.contextRow(),
      p.followStoppedDot(),
      p.spreadPause(),
      p.spreadActivity(),
      p.labelGray()
    };
  }

  private void write(String name, String text) throws IOException {
    Path dir = Files.createDirectories(temp.resolve(SchemeStore.FOLDER));
    Files.writeString(dir.resolve(name), text, StandardCharsets.ISO_8859_1);
  }

  @Test
  void noFolderLeavesTheBundledSchemes() {
    SchemeStore store = new SchemeStore(location());
    store.load();
    assertEquals(ColorSchemes.BUNDLED, store.schemes().all());
    assertEquals(List.of(), warnings);
  }

  @Test
  void aCompleteFileFoldsIntoTheListInNameOrder() throws IOException {
    write("mock-b.properties", complete("name=Mock Beta\n"));
    write("mock-a.properties", complete("unknown.key=ignored\n"));
    write("notes.txt", "not a scheme");
    SchemeStore store = new SchemeStore(location());
    store.load();
    List<ColorScheme> all = store.schemes().all();
    assertEquals(ColorSchemes.BUNDLED.size() + 2, all.size());
    ColorScheme first = all.get(ColorSchemes.BUNDLED.size());
    assertEquals("mock-a", first.id());
    assertEquals("mock-a", first.name());
    assertEquals(Palette.DARK, first.palette());
    assertEquals("Mock Beta", store.schemes().find("mock-b").name());
    assertEquals(List.of(), warnings);
  }

  @Test
  void eachBadFileIsSkippedWithOneWarning() throws IOException {
    String full = complete("");
    write("missing-color.properties", full.replaceAll("(?m)^labelGray=.*\\n", ""));
    write("bad-color.properties", full.replace("text=#", "text=#zz"));
    write("bad-dark.properties", full.replace("dark=true", "dark=maybe"));
    write("paper.properties", full);
    write("theme.properties", full);
    write("bad.id.properties", full);
    write("too-long.properties", full + "#" + "x".repeat(SchemeStore.MAX_BYTES) + "\n");
    write("bad-escape.properties", full + "name=\\u00zz\n");
    Files.createDirectories(temp.resolve(SchemeStore.FOLDER).resolve("folder.properties"));
    write("good.properties", full);
    SchemeStore store = new SchemeStore(location());
    store.load();
    assertEquals(ColorSchemes.BUNDLED.size() + 1, store.schemes().all().size());
    assertEquals("good", store.schemes().find("good").id());
    assertEquals(9, warnings.size(), warnings.toString());
    assertSame(ColorSchemes.BUNDLED.get(5).palette(), store.schemes().find("paper").palette());
  }

  @Test
  void aNameWithAControlCharacterOrOverTheLimitIsReplacedOrCut() throws IOException {
    write("mock-c.properties", complete("name=Mock\\u0007Bell\n"));
    write(
        "mock-d.properties", complete("name=" + "n".repeat(SchemeStore.MAX_NAME_CHARS + 5) + "\n"));
    SchemeStore store = new SchemeStore(location());
    store.load();
    assertEquals("mock-c", store.schemes().find("mock-c").name());
    assertEquals(SchemeStore.MAX_NAME_CHARS, store.schemes().find("mock-d").name().length());
  }

  @Test
  void viewColorsRoundTripsAndAnInvalidValueFallsBack() throws IOException {
    ConfigStore config = new ConfigStore(location());
    Preferences saved =
        Preferences.DEFAULTS.withView(Preferences.DEFAULTS.view().withColors("nightwater"));
    assertTrue(config.save(saved));
    String text = Files.readString(temp.resolve("qlogg.conf"), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("view.colors=nightwater\n"), text);
    assertEquals("nightwater", new ConfigStore(location()).load().view().colors());
    assertTrue(config.save(Preferences.DEFAULTS));
    assertEquals(ColorSchemes.FOLLOW_THEME, new ConfigStore(location()).load().view().colors());
    Files.writeString(
        temp.resolve("qlogg.conf"), "view.colors=../escape\n", StandardCharsets.ISO_8859_1);
    assertEquals(ColorSchemes.FOLLOW_THEME, new ConfigStore(location()).load().view().colors());
    assertFalse(SchemeStore.validId("../escape"));
    assertTrue(SchemeStore.validId("mock-scheme_2"));
  }
}
