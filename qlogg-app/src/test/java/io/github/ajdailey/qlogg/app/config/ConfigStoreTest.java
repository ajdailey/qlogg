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
import io.github.ajdailey.qlogg.core.time.SpreadMode;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
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

  private static final OsTraits LINUX = OsTraits.of("Linux");

  private static final List<OsTraits> SYSTEMS =
      List.of(OsTraits.of("Windows 11"), OsTraits.of("Mac OS X"), LINUX);

  private ConfigStore store(Path dir) {
    return store(dir, LINUX);
  }

  private ConfigStore store(Path dir, OsTraits os) {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, dir), os);
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

  // Per-system defaults: each comes from the OS seam, never from a value the store holds.
  @Test
  void aMissingFileOrKeyTakesTheSystemDefaults() throws IOException {
    for (OsTraits os : SYSTEMS) {
      Preferences missing = store(temp, os).load();
      assertSystemDefaults(os, missing);
      assertEquals(Preferences.defaults(os), missing);
    }
    Files.writeString(conf(), "ui.theme=dark\n", StandardCharsets.ISO_8859_1);
    for (OsTraits os : SYSTEMS) {
      assertSystemDefaults(os, store(temp, os).load());
    }
  }

  @Test
  void anExplicitValueWinsOverTheSystemDefault() throws IOException {
    for (OsTraits os : SYSTEMS) {
      boolean keepClosed = !os.keepFileClosedByDefault();
      boolean watch = !os.watchServiceByDefault();
      Files.writeString(
          conf(),
          "follow.keepClosed="
              + keepClosed
              + "\nmonitor.watchService="
              + watch
              + "\nencoding.default=UTF-8\n",
          StandardCharsets.ISO_8859_1);
      Preferences loaded = store(temp, os).load();
      assertEquals(keepClosed, loaded.follow().keepClosed());
      assertEquals(watch, loaded.options().useWatchService());
      assertEquals("UTF-8", loaded.options().defaultEncoding());
    }
  }

  @Test
  void theSystemDefaultsRoundTrip() throws IOException {
    for (OsTraits os : SYSTEMS) {
      Files.deleteIfExists(conf());
      ConfigStore store = store(temp, os);
      Preferences defaults = store.load();
      assertTrue(store.save(defaults, defaults.withSplitPercent(40)));
      Preferences loaded = store(temp, os).load();
      assertSystemDefaults(os, loaded);
      assertEquals(40, loaded.splitPercent());
    }
  }

  private static void assertSystemDefaults(OsTraits os, Preferences preferences) {
    assertEquals(os.keepFileClosedByDefault(), preferences.follow().keepClosed());
    assertEquals(os.watchServiceByDefault(), preferences.options().useWatchService());
    assertEquals(Options.DEFAULT_ENCODING, preferences.options().defaultEncoding());
  }

  @Test
  void savesVersionAndThemeAtomicallyAndLoadsThemBack() throws IOException {
    ConfigStore store = store(temp);
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
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
    assertTrue(store.save(DARK, Preferences.DEFAULTS.withTheme(ThemeMode.SYSTEM)));
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
            .withFollow(new FollowSettings(true, false, true));
    assertEquals(List.of("newest", "caf\u00e9 = x", "old"), saved.searchHistory());
    ConfigStore store = store(temp);
    assertTrue(store.save(Preferences.DEFAULTS, saved));
    assertEquals(saved, store(temp).load());
    String text = Files.readString(conf(), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("search.history.2=old"));
    assertTrue(text.contains("follow.onLoad=true"));
    assertTrue(text.contains("follow.onOverscroll=false"));
    assertTrue(text.contains("follow.keepClosed=true"));
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
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void aBadValueFallsBackWithoutMarkingTheFileMalformed() throws IOException {
    Files.writeString(conf(), "version=1\nui.theme=purple\nfuture.key=x\n");
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void aMalformedFileIsKeptAsInvalidBeforeTheFirstSave() throws IOException {
    byte[] bad = "ui.theme=dark\nbroken=\\u12\n".getBytes(StandardCharsets.ISO_8859_1);
    Files.write(conf(), bad);
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertFalse(Files.exists(invalid()));
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    assertArrayEquals(bad, Files.readAllBytes(invalid()));
    assertEquals(DARK, store(temp).load());
    Files.delete(invalid());
    assertTrue(store.save(DARK, Preferences.DEFAULTS));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void anotherVersionIsMalformed() throws IOException {
    Files.writeString(conf(), "version=2\nui.theme=dark\n");
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(Preferences.DEFAULTS, Preferences.DEFAULTS));
    assertTrue(Files.exists(invalid()));
  }

  @Test
  void aFileOverOneMebibyteIsMalformed() throws IOException {
    StringBuilder text = new StringBuilder("version=1\nui.theme=dark\n#");
    text.append("x".repeat(ConfigStore.MAX_BYTES));
    Files.writeString(conf(), text.toString());
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
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
      assertFalse(store.save(Preferences.DEFAULTS, Preferences.DEFAULTS));
      assertEquals("ui.theme=dark\n", Files.readString(dir.resolve("qlogg.conf")));
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
  }

  /** Windows refuses a move over a directory with access denied; the next save still runs. */
  @Test
  void aRefusedSaveLeavesSavingOn() throws IOException {
    Path dir = Files.createDirectory(temp.resolve("refused"));
    Path blocker = Files.createDirectory(dir.resolve("qlogg.conf"));
    Files.writeString(blocker.resolve("inside"), "x");
    ConfigStore store = store(dir);
    assertFalse(store.save(Preferences.DEFAULTS, DARK));
    assertFalse(store.saveDisabled());
    Files.delete(blocker.resolve("inside"));
    Files.delete(blocker);
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    assertEquals(DARK, store(dir).load());
  }

  @Test
  void aPerUserDirectoryIsCreatedOwnerOnlyOnSave() throws IOException {
    Path dir = temp.resolve("config").resolve("qlogg");
    ConfigStore store = new ConfigStore(new SettingsLocation(SettingsMode.PER_USER, dir), LINUX);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertFalse(Files.exists(dir));
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    assertTrue(Files.isRegularFile(dir.resolve("qlogg.conf")));
    if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }
  }

  // Key-difference save.

  @Test
  void aSaveKeepsTheKeysAnotherStoreChanged() throws IOException {
    Preferences first =
        Preferences.DEFAULTS.withState(
            UiState.DEFAULTS.withWindow(new WindowState(1, 2, 300, 400, false), 60));
    assertTrue(store(temp).save(Preferences.DEFAULTS, first));
    ConfigStore a = store(temp);
    ConfigStore b = store(temp);
    Preferences loadedA = a.load();
    Preferences loadedB = b.load();
    Preferences placed =
        loadedB.withState(loadedB.state().withWindow(new WindowState(5, 6, 700, 800, true), 40));
    assertTrue(b.save(loadedB, placed));
    assertTrue(a.save(loadedA, loadedA.withTheme(ThemeMode.DARK)));
    Preferences merged = store(temp).load();
    assertEquals(ThemeMode.DARK, merged.theme());
    assertEquals(placed.state().window(), merged.state().window());
    assertEquals(40, merged.state().split());
  }

  @Test
  void aSaveWithoutChangesLeavesTheFileAlone() throws IOException {
    Files.writeString(conf(), "ui.theme=dark\n");
    ConfigStore store = store(temp);
    Preferences loaded = store.load();
    assertTrue(store.save(loaded, loaded));
    assertEquals("ui.theme=dark\n", Files.readString(conf()));
  }

  @Test
  void aSaveWritesOnlyTheChangedKeysAndKeepsUnknownOnes() throws IOException {
    Files.writeString(conf(), "version=1\nfuture.key=kept\nsearch.maxResults=abc\n");
    ConfigStore store = store(temp);
    Preferences loaded = store.load();
    assertTrue(store.save(loaded, loaded.withTheme(ThemeMode.DARK)));
    assertEquals(
        List.of("future.key=kept", "search.maxResults=abc", "ui.theme=dark", "version=1"),
        Files.readAllLines(conf()));
  }

  @Test
  void aShorterListRemovesItsTailKeys() throws IOException {
    Preferences three = Preferences.DEFAULTS.withSearchHistory(List.of("a", "b", "c"));
    ConfigStore store = store(temp);
    assertTrue(store.save(Preferences.DEFAULTS, three));
    assertTrue(store.save(three, three.withSearchHistory(List.of("a"))));
    String text = Files.readString(conf(), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("search.history.0=a\n"));
    assertFalse(text.contains("search.history.1"));
    assertEquals(List.of("a"), store(temp).load().searchHistory());
  }

  @Test
  void aFileMalformedSinceTheLoadIsKeptBeforeTheSaveReplacesIt() throws IOException {
    ConfigStore store = store(temp);
    Preferences loaded = store.load();
    Files.writeString(conf(), "version=9\n");
    assertTrue(store.save(loaded, DARK));
    assertEquals("version=9\n", Files.readString(invalid()));
    assertEquals(DARK, store(temp).load());
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
                .withSyntaxColors(false)
                .withFolds(new FoldRules("^\\d{4}-", List.of("^x")))
                .withSelectors(
                    List.of(
                        new HighlightSelector("*.err", "", "errors"),
                        new HighlightSelector("", "^GET ", "web"))));
    ConfigStore store = store(temp);
    assertTrue(store.save(Preferences.DEFAULTS, saved));
    assertEquals(saved, store(temp).load());
    String text = Files.readString(conf(), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("view.wrapOnOpen=true"));
    assertTrue(text.contains("view.syntaxColors=false"));
    assertTrue(text.contains("highlight.selector.1.set=web"));
  }

  @Test
  void anIncompleteSelectorIsDropped() throws IOException {
    Files.writeString(
        conf(),
        "highlight.selector.0.set=errors\nhighlight.selector.0.glob=*.err\n"
            + "highlight.selector.0.firstLine=^x\nview.hideAnsi=maybe\nview.timeSpread=sideways\n"
            + "view.syntaxColors=maybe\n",
        StandardCharsets.ISO_8859_1);
    Preferences loaded = store(temp).load();
    assertEquals(List.of(), loaded.view().selectors());
    assertEquals(false, loaded.view().hideAnsi());
    assertEquals(true, loaded.view().syntaxColors());
    assertEquals(SpreadMode.OFF, loaded.view().timeSpread());
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
    assertTrue(store.save(Preferences.DEFAULTS, saved));
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
