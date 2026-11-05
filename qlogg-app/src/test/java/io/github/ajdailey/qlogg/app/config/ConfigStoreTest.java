package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.highlight.HighlightSelector;
import io.github.ajdailey.qlogg.app.search.PredefinedFilter;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
  private static final Preferences DARK = Preferences.DEFAULTS.withTheme(ThemeMode.DARK);

  @TempDir Path temp;

  private ConfigStore store(Path dir) {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, dir));
  }

  private Path conf() {
    return temp.resolve("qlogg.conf");
  }

  private Path invalid() {
    return temp.resolve("qlogg.conf.invalid");
  }

  private List<String> names() throws IOException {
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(temp)) {
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
    }
    Collections.sort(names);
    return names;
  }

  @Test
  void aMissingFileLoadsDefaults() {
    assertEquals(Preferences.DEFAULTS, store(temp).load());
    assertEquals(ThemeMode.LIGHT, Preferences.DEFAULTS.theme());
  }

  @Test
  void savesVersionAndThemeAtomicallyAndLoadsThemBack() throws IOException {
    ConfigStore store = store(temp);
    assertTrue(store.save(DARK));
    String text = Files.readString(conf(), StandardCharsets.UTF_8);
    for (String line :
        List.of(
            "quickFind.incremental=true",
            "quickFind.regex=false",
            "search.historySize=50",
            "search.maxResults=0",
            "search.runOnAdd=true",
            "ui.theme=dark",
            "version=1",
            "view.overview=true",
            "view.splitPercent=70",
            "matchHighlight.enabled=true",
            "label.8.cycle=true")) {
      assertTrue(text.contains(line + "\n"), line);
    }
    assertEquals(List.of("qlogg.conf"), names());
    assertEquals(DARK, store(temp).load());
    assertTrue(store.save(Preferences.DEFAULTS.withTheme(ThemeMode.SYSTEM)));
    assertEquals(ThemeMode.SYSTEM, store(temp).load().theme());
  }

  @Test
  void historyFiltersAndOptionsRoundTrip() throws IOException {
    Preferences saved =
        Preferences.DEFAULTS
            .withHistorySize(3)
            .withSearchHistory(List.of("newest", "caf\u00e9 = x", "old", "dropped"))
            .withFilters(
                List.of(
                    new PredefinedFilter("Errors", "ERROR|FATAL", MatchMode.REGEX),
                    new PredefinedFilter("Dots", "a.b", MatchMode.LITERAL)))
            .withMaxResults(1000)
            .withRunSearchOnAdd(false)
            .withQuickFind(false, true)
            .withSplitPercent(40)
            .withFollow(new FollowSettings(true, false));
    assertEquals(List.of("newest", "caf\u00e9 = x", "old"), saved.searchHistory());
    ConfigStore store = store(temp);
    assertTrue(store.save(saved));
    assertEquals(saved, store(temp).load());
    String text = Files.readString(conf(), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("search.history.2=old"));
    assertTrue(text.contains("follow.onLoad=true"));
    assertTrue(text.contains("follow.onOverscroll=false"));
  }

  @Test
  void outOfRangeOptionsFallBackOneByOne() throws IOException {
    Files.writeString(
        conf(),
        "search.historySize=0\nsearch.maxResults=-1\nview.splitPercent=95\n"
            + "search.runOnAdd=maybe\nquickFind.regex=true\nsearch.history.0=a\n"
            + "search.history.2=skipped\nfilter.0.name=n\nfilter.0.pattern=p\n"
            + "filter.0.regex=yes\nfilter.1.name=m\nfilter.1.pattern=q\n");
    Preferences loaded = store(temp).load();
    assertEquals(Preferences.DEFAULTS.historySize(), loaded.historySize());
    assertEquals(0, loaded.maxResults());
    assertEquals(Preferences.DEFAULTS.splitPercent(), loaded.splitPercent());
    assertTrue(loaded.runSearchOnAdd());
    assertTrue(loaded.quickFindRegex());
    assertEquals(List.of("a"), loaded.searchHistory());
    assertEquals(List.of(new PredefinedFilter("m", "q", MatchMode.REGEX)), loaded.filters());
  }

  @Test
  void anEmptyMarkerIsValid() throws IOException {
    Files.createFile(conf());
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(DARK));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void aBadValueFallsBackWithoutMarkingTheFileMalformed() throws IOException {
    Files.writeString(conf(), "version=1\nui.theme=purple\nfuture.key=x\n");
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(DARK));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void aMalformedFileIsKeptAsInvalidBeforeTheFirstSave() throws IOException {
    byte[] bad = "ui.theme=dark\nbroken=\\u12\n".getBytes(StandardCharsets.ISO_8859_1);
    Files.write(conf(), bad);
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertFalse(Files.exists(invalid()));
    assertTrue(store.save(DARK));
    assertArrayEquals(bad, Files.readAllBytes(invalid()));
    assertEquals(DARK, store(temp).load());
    Files.delete(invalid());
    assertTrue(store.save(Preferences.DEFAULTS));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void anotherVersionIsMalformed() throws IOException {
    Files.writeString(conf(), "version=2\nui.theme=dark\n");
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(Preferences.DEFAULTS));
    assertTrue(Files.exists(invalid()));
  }

  @Test
  void aFileOverOneMebibyteIsMalformed() throws IOException {
    StringBuilder text = new StringBuilder("version=1\nui.theme=dark\n#");
    text.append("x".repeat(ConfigStore.MAX_BYTES));
    Files.writeString(conf(), text.toString());
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(DARK));
    assertTrue(Files.size(invalid()) > ConfigStore.MAX_BYTES);
  }

  @Test
  void aReadOnlyDirectoryDisablesSaving() throws IOException {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    Path dir = Files.createDirectory(temp.resolve("read-only"));
    Files.writeString(dir.resolve("qlogg.conf"), "ui.theme=dark\n");
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeTrue(!Files.isWritable(dir), "running with permission to write anyway");
      ConfigStore store = store(dir);
      assertEquals(DARK, store.load());
      assertTrue(store.saveDisabled());
      assertFalse(store.save(Preferences.DEFAULTS));
      assertEquals("ui.theme=dark\n", Files.readString(dir.resolve("qlogg.conf")));
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void aPerUserDirectoryIsCreatedOwnerOnlyOnSave() throws IOException {
    Path dir = temp.resolve("config").resolve("qlogg");
    ConfigStore store = new ConfigStore(new SettingsLocation(SettingsMode.PER_USER, dir));
    assertEquals(Preferences.DEFAULTS, store.load());
    assertFalse(Files.exists(dir));
    assertTrue(store.save(DARK));
    assertTrue(Files.isRegularFile(dir.resolve("qlogg.conf")));
    if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }
  }

  // View settings: wrap on open, whitespace, hidden ANSI colors, fold rules and selectors.

  @Test
  void viewOptionsRoundTrip() throws IOException {
    Preferences saved =
        Preferences.DEFAULTS.withView(
            ViewOptions.DEFAULTS
                .withWrapOnOpen(true)
                .withShowWhitespace(true)
                .withHideAnsi(true)
                .withFolds(new FoldRules("^\\d{4}-", List.of("^x")))
                .withSelectors(
                    List.of(
                        new HighlightSelector("*.err", "", "errors"),
                        new HighlightSelector("", "^GET ", "web"))));
    ConfigStore store = store(temp);
    assertTrue(store.save(saved));
    assertEquals(saved, store(temp).load());
    String text = Files.readString(conf(), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("view.wrapOnOpen=true"));
    assertTrue(text.contains("highlight.selector.1.set=web"));
  }

  @Test
  void anIncompleteSelectorIsDropped() throws IOException {
    Files.writeString(
        conf(),
        "highlight.selector.0.set=errors\nhighlight.selector.0.glob=*.err\n"
            + "highlight.selector.0.firstLine=^x\nview.hideAnsi=maybe\n",
        StandardCharsets.ISO_8859_1);
    Preferences loaded = store(temp).load();
    assertEquals(List.of(), loaded.view().selectors());
    assertEquals(false, loaded.view().hideAnsi());
  }

  // Timestamps.

  @Test
  void timeSettingsRoundTripAndABadFormatIsDropped() throws IOException {
    Preferences saved =
        Preferences.DEFAULTS.withTime(
            new TimeSettings(
                ZoneId.of("Europe/Berlin"),
                ZoneOffset.UTC,
                List.of(
                    new UserFormat("app", "^(?<ts>\\S+ \\S+)", "yyyy-MM-dd HH:mm:ss.SSS"),
                    new UserFormat("short", "(?<ts>\\d+:\\d+:\\d+)", "HH:mm:ss"))));
    ConfigStore store = store(temp);
    assertTrue(store.save(saved));
    assertEquals(saved, store(temp).load());
    String text = Files.readString(conf(), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("time.zone=Europe/Berlin"));
    assertTrue(text.contains("time.format.1.name=short"));
    Files.writeString(
        conf(),
        "time.zone=Nowhere/Mock\ntime.format.0.name=bad\ntime.format.0.locate=(no group)\n"
            + "time.format.0.pattern=HH\ntime.format.1.name=good\n"
            + "time.format.1.locate=(?<ts>\\\\d+)\ntime.format.1.pattern=HH\n",
        StandardCharsets.ISO_8859_1);
    TimeSettings loaded = store(temp).load().time();
    assertNull(loaded.zone());
    assertEquals(1, loaded.formats().size());
    assertEquals("good", loaded.formats().get(0).name());
  }
}
