package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.Binding;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.ShortcutOverrides;
import io.github.ajdailey.qlogg.core.io.PathCase;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Options, UI state, positions and the session through their files. */
class StateStoresTest {
  private static final PathCase EXACT = PathCase.of(OsTraits.of("Linux"));
  private static final PathCase IGNORING = PathCase.of(OsTraits.of("Mac OS X"));

  @TempDir Path temp;

  private SettingsLocation location() {
    return new SettingsLocation(SettingsMode.EXPLICIT, temp);
  }

  @Test
  void optionsAndStateRoundTrip() {
    ShortcutOverrides shortcuts =
        ShortcutOverrides.NONE
            .with(
                CommandId.OPEN,
                List.of(
                    new Binding(
                        CommandId.OPEN,
                        KeyStroke.parse("Ctrl+Shift+K"),
                        null,
                        Binding.Scope.WINDOW)))
            .with(CommandId.RELOAD, List.of());
    Options options =
        new Options(
            new FontSettings("Mono Mock", 12, 14, true, true),
            7,
            new MonitorSettings(500, false),
            "UTF-8",
            false,
            shortcuts,
            List.of("/mock/a.log", "/mock/b.log"),
            true,
            true,
            true);
    UiState state =
        UiState.DEFAULTS
            .withWindow(new WindowState(-5, 10, 900, 600, true), 55)
            .withSearch(new SearchToggles(false, true, false, true, false, false, false))
            .withLineNumbers(false, true)
            .withOpenDirectory("/mock")
            .addRecent("/mock/a.log", 7, EXACT)
            .addRecent("/mock/c.log", 7, EXACT)
            .withDialog("preferences", new UiState.Size(640, 480));
    ConfigStore store = new ConfigStore(location());
    assertTrue(
        store.save(
            Preferences.DEFAULTS, Preferences.DEFAULTS.withOptions(options).withState(state)));
    Preferences loaded = new ConfigStore(location()).load();
    assertEquals(options, loaded.options());
    assertEquals(state, loaded.state());
    assertEquals(List.of("/mock/c.log", "/mock/a.log"), loaded.state().recentFiles());
  }

  // Last tab.

  @Test
  void closeWindowWithLastTabIsOffByDefaultAndRoundTripsUnderItsKey() throws Exception {
    assertFalse(Options.DEFAULTS.closeWindowWithLastTab());
    assertFalse(new ConfigStore(location()).load().options().closeWindowWithLastTab());
    Files.writeString(
        temp.resolve(SettingsLocation.SETTINGS_FILE),
        "tabs.closeWindowWithLastTab=true\n",
        StandardCharsets.ISO_8859_1);
    Preferences loaded = new ConfigStore(location()).load();
    assertTrue(loaded.options().closeWindowWithLastTab());
    assertTrue(new ConfigStore(location()).save(loaded, loaded.withOptions(Options.DEFAULTS)));
    assertTrue(
        Files.readString(temp.resolve(SettingsLocation.SETTINGS_FILE), StandardCharsets.ISO_8859_1)
            .contains("tabs.closeWindowWithLastTab=false"));
    assertFalse(new ConfigStore(location()).load().options().closeWindowWithLastTab());
  }

  // Last used tab.

  @Test
  void closeShowsLastUsedIsOffByDefaultAndRoundTripsUnderItsKey() throws Exception {
    assertFalse(Options.DEFAULTS.closeShowsLastUsed());
    assertFalse(new ConfigStore(location()).load().options().closeShowsLastUsed());
    Files.writeString(
        temp.resolve(SettingsLocation.SETTINGS_FILE),
        "tabs.closeShowsLastUsed=true\n",
        StandardCharsets.ISO_8859_1);
    Preferences loaded = new ConfigStore(location()).load();
    assertTrue(loaded.options().closeShowsLastUsed());
    assertTrue(new ConfigStore(location()).save(loaded, loaded.withOptions(Options.DEFAULTS)));
    assertTrue(
        Files.readString(temp.resolve(SettingsLocation.SETTINGS_FILE), StandardCharsets.ISO_8859_1)
            .contains("tabs.closeShowsLastUsed=false"));
    assertFalse(new ConfigStore(location()).load().options().closeShowsLastUsed());
  }

  // Search in a folder.

  @Test
  void folderSearchSettingsRoundTripAndAnOverlongValueReadsAsTheDefault() throws Exception {
    FolderSearchSettings folder =
        new FolderSearchSettings("/mock/logs", "*.log;*.txt", true, "*.gz;debug.*");
    ConfigStore store = new ConfigStore(location());
    assertTrue(
        store.save(
            Preferences.DEFAULTS,
            Preferences.DEFAULTS.withState(UiState.DEFAULTS.withFolderSearch(folder))));
    assertEquals(folder, new ConfigStore(location()).load().state().folderSearch());
    Files.writeString(
        temp.resolve(SettingsLocation.SETTINGS_FILE),
        "folderSearch.folder="
            + "x".repeat(FolderSearchSettings.MAX_CHARS + 1)
            + "\nfolderSearch.names=*.out\nfolderSearch.recursive=maybe\nfolderSearch.exclude="
            + "x".repeat(FolderSearchSettings.MAX_CHARS + 1)
            + "\n",
        StandardCharsets.ISO_8859_1);
    assertEquals(
        new FolderSearchSettings("", "*.out", false, ""),
        new ConfigStore(location()).load().state().folderSearch());
  }

  @Test
  void invalidValuesFallBackToDefaults() throws Exception {
    Files.writeString(
        temp.resolve(SettingsLocation.SETTINGS_FILE),
        "font.size=900\nrecent.count=x\nencoding.default=no-such-charset\nwindow.maximized=yes\n"
            + "monitor.pollMillis=5\nshortcut.OPEN.count=1\nshortcut.OPEN.0=nowhere F2\n",
        StandardCharsets.ISO_8859_1);
    Preferences loaded = new ConfigStore(location()).load();
    assertEquals(0, loaded.options().font().size());
    assertEquals(Options.DEFAULTS.recentCount(), loaded.options().recentCount());
    assertEquals(Options.DEFAULT_ENCODING, loaded.options().defaultEncoding());
    assertFalse(loaded.state().window().maximized());
    assertEquals(1000, loaded.options().monitor().pollMillis());
    assertEquals(List.of(), loaded.options().shortcuts().bindings(CommandId.OPEN));
  }

  @Test
  void positionsKeepTheMostRecentEntries() throws Exception {
    PositionStore store = new PositionStore(location());
    for (int i = 0; i < PositionStore.MAX_ENTRIES + 5; i++) {
      store.put(Path.of("/mock/" + i + ".log"), i, i + 1, 2);
    }
    store.setEncoding(Path.of("/mock/9.log"), StandardCharsets.UTF_16BE);
    assertTrue(store.save());
    assertFalse(store.save());
    PositionStore loaded = new PositionStore(location());
    loaded.load();
    assertEquals(PositionStore.MAX_ENTRIES, loaded.size());
    assertEquals(PositionStore.Entry.NONE, loaded.get(Path.of("/mock/0.log")));
    assertEquals(new PositionStore.Entry(9, 10, 2, "UTF-16BE"), loaded.get(Path.of("/mock/9.log")));
    assertEquals(StandardCharsets.UTF_16BE, loaded.get(Path.of("/mock/9.log")).charset());
    assertNull(loaded.get(Path.of("/mock/20.log")).charset());
  }

  @Test
  void positionsKeepTheTimestampAndColumnChoices() throws Exception {
    PositionStore store = new PositionStore(location());
    Path file = Path.of("/mock/t.log");
    store.setTimeFormat(file, "user:app");
    store.setZone(file, "Europe/Berlin");
    store.put(file, 4, 5, 6);
    store.setEncoding(file, StandardCharsets.UTF_8);
    store.setTimeFormat(Path.of("/mock/n.log"), TimeSettings.NO_FORMAT);
    store.setColumns(file, "csv");
    store.setHeader(file, false);
    store.setColumns(Path.of("/mock/n.log"), PositionStore.NO_COLUMNS);
    assertTrue(store.save());
    PositionStore loaded = new PositionStore(location());
    loaded.load();
    assertEquals(
        new PositionStore.Entry(4, 5, 6, "UTF-8", "user:app", "Europe/Berlin", "csv", "0"),
        loaded.get(file));
    assertEquals(PositionStore.NO_COLUMNS, loaded.get(Path.of("/mock/n.log")).columns());
    assertEquals("", loaded.get(Path.of("/mock/n.log")).header());
    assertEquals(TimeSettings.NO_FORMAT, loaded.get(Path.of("/mock/n.log")).timeFormat());
    assertEquals("", loaded.get(Path.of("/mock/n.log")).zone());
  }

  /**
   * Where names ignore case, a path finds and updates the entry saved in another case, which keeps
   * its key; the exact rule keeps the two apart.
   */
  @Test
  void aPositionLookupInAnotherCaseFindsTheSavedEntryWhereNamesIgnoreCase() throws Exception {
    PositionStore saved = new PositionStore(location(), EXACT);
    saved.put(Path.of("/mock/APP.LOG"), 4, 5, 6);
    saved.put(Path.of("/mock/other.log"), 1, 1, 1);
    assertTrue(saved.save());
    PositionStore ignoring = new PositionStore(location(), IGNORING);
    ignoring.load();
    Path onDisk = Path.of("/mock/app.log");
    assertEquals(new PositionStore.Entry(4, 5, 6, ""), ignoring.get(onDisk));
    ignoring.put(onDisk, 7, 8, 9);
    ignoring.setEncoding(onDisk, StandardCharsets.UTF_8);
    assertEquals(2, ignoring.size());
    assertTrue(ignoring.save());
    String file = Files.readString(temp.resolve(PositionStore.FILE), StandardCharsets.ISO_8859_1);
    assertTrue(file.contains("APP.LOG"), file);
    assertFalse(file.contains("app.log"), file);
    PositionStore exact = new PositionStore(location(), EXACT);
    exact.load();
    assertEquals(PositionStore.Entry.NONE, exact.get(onDisk));
    assertEquals(new PositionStore.Entry(7, 8, 9, "UTF-8"), exact.get(Path.of("/mock/APP.LOG")));
  }

  /**
   * A recent or closed entry in another case gives way to the new spelling where names ignore case.
   */
  @Test
  void listsKeepOneEntryPerFileWhereNamesIgnoreCase() {
    UiState ignoring =
        UiState.DEFAULTS
            .addRecent("/mock/APP.LOG", 7, IGNORING)
            .addRecent("/mock/app.log", 7, IGNORING)
            .addClosed("/mock/APP.LOG", true, IGNORING)
            .addClosed("/mock/app.log", true, IGNORING);
    assertEquals(List.of("/mock/app.log"), ignoring.recentFiles());
    assertEquals(List.of("/mock/app.log"), ignoring.closedFiles());
    assertEquals(List.of("/mock/app.log"), ignoring.closedSets());
    assertEquals(List.of(), ignoring.removeClosed("/mock/App.Log", IGNORING).closedFiles());
    UiState exact =
        UiState.DEFAULTS.addRecent("/mock/APP.LOG", 7, EXACT).addRecent("/mock/app.log", 7, EXACT);
    assertEquals(List.of("/mock/app.log", "/mock/APP.LOG"), exact.recentFiles());
  }

  @Test
  void aMalformedPositionsFileIsKeptAsInvalid() throws Exception {
    Files.writeString(temp.resolve(PositionStore.FILE), "version=2\n");
    PositionStore store = new PositionStore(location());
    store.load();
    assertEquals(0, store.size());
    store.put(Path.of("/mock/a.log"), 1, 2, 3);
    assertTrue(store.save());
    assertEquals("version=2\n", Files.readString(temp.resolve(PositionStore.FILE + ".invalid")));
  }

  @Test
  void sessionRoundTripsTabsNamesFollowAndMarks() {
    SessionStore store = new SessionStore(location());
    Session session =
        new Session(
            List.of(
                new Session.Tab("/mock/a.log", "Alpha", true, new long[] {0, 0, 3, 7}),
                new Session.Tab("/mock/b.log", "", false, new long[0])),
            1);
    assertTrue(store.save(session));
    Session loaded = new SessionStore(location()).load();
    assertEquals(1, loaded.active());
    assertEquals(2, loaded.tabs().size());
    Session.Tab tab = loaded.tabs().get(0);
    assertEquals("Alpha", tab.name());
    assertTrue(tab.follow());
    assertArrayEquals(new long[] {0, 0, 3, 7}, tab.marks());
    assertTrue(tab.comments().isEmpty());
    assertEquals("", loaded.tabs().get(1).name());
  }

  @Test
  void aSessionOverTheLoadCapIsNotSavedAndTheLastOneStays() {
    SessionStore store = new SessionStore(location());
    Session small = new Session(List.of(new Session.Tab("/mock/a.log", "", false, new long[0])), 0);
    assertTrue(store.save(small));
    String path = "/mock/" + "x".repeat(SessionStore.MAX_BYTES);
    assertFalse(store.save(new Session(List.of(new Session.Tab(path, "", false, new long[0])), 0)));
    assertEquals("/mock/a.log", new SessionStore(location()).load().tabs().get(0).path());
  }

  @Test
  void sessionRoundTripsTheTimeOffset() throws Exception {
    SessionStore store = new SessionStore(location());
    Session.Tab tab =
        new Session.Tab("/mock/a.log", "", false, new long[0], List.of(), -90_000_000_001L);
    Session.Tab plain = new Session.Tab("/mock/b.log", "", false, new long[0]);
    assertTrue(store.save(new Session(List.of(tab, plain), 0)));
    Session loaded = new SessionStore(location()).load();
    assertEquals(-90_000_000_001L, loaded.tabs().get(0).timeOffset());
    assertEquals(0, loaded.tabs().get(1).timeOffset());
    assertFalse(
        Files.readString(temp.resolve(SessionStore.FILE), StandardCharsets.ISO_8859_1)
            .contains("tab.1.timeOffset"));
    Files.writeString(
        temp.resolve(SessionStore.FILE),
        "window.0.active=0\nwindow.0.tab.0.path=/mock/a.log\nwindow.0.tab.0.timeOffset=soon\n",
        StandardCharsets.ISO_8859_1);
    assertEquals(0, new SessionStore(location()).load().tabs().get(0).timeOffset());
  }

  @Test
  void sessionRoundTripsAFilterTabsDefinition() throws Exception {
    SessionStore store = new SessionStore(location());
    Session.Tab root = new Session.Tab("/mock/a.log", "", false, new long[] {3, 4});
    SearchToggles toggles =
        new SearchToggles(false, true, true, false, false, true, false, true, "END", 2, 0);
    Session.Derived derived = new Session.Derived(0, 0, "flagword", toggles, 10, 40, "level");
    Session.Tab tab =
        new Session.Tab(
            "/mock/a.log",
            "mock name",
            true,
            new long[0],
            List.of(),
            0,
            0,
            false,
            List.of(),
            derived);
    assertTrue(store.save(new Session(List.of(root, tab), 1)));
    Session loaded = new SessionStore(location()).load();
    assertNull(loaded.tabs().get(0).derived());
    Session.Tab back = loaded.tabs().get(1);
    assertEquals(derived, back.derived());
    assertEquals("mock name", back.name());
    assertTrue(back.follow());
    String file = Files.readString(temp.resolve(SessionStore.FILE), StandardCharsets.ISO_8859_1);
    assertTrue(file.contains("window.0.tab.1.derived.searchOption.rangeEnd=END"), file);
    // A malformed parent leaves a tab of the root's file.
    Files.writeString(
        temp.resolve(SessionStore.FILE),
        "window.0.active=0\n"
            + "window.0.tab.0.path=/mock/a.log\n"
            + "window.0.tab.0.derived.parent=first\n"
            + "window.0.tab.0.derived.text=flagword\n",
        StandardCharsets.ISO_8859_1);
    assertNull(new SessionStore(location()).load().tabs().get(0).derived());
  }

  @Test
  void sessionRoundTripsTheSyncGroup() throws Exception {
    SessionStore store = new SessionStore(location());
    Session.Tab tab = new Session.Tab("/mock/a.log", "", false, new long[0], List.of(), 0, 2);
    Session.Tab plain = new Session.Tab("/mock/b.log", "", false, new long[0]);
    assertTrue(store.save(new Session(List.of(tab, plain), 0)));
    Session loaded = new SessionStore(location()).load();
    assertEquals(2, loaded.tabs().get(0).syncGroup());
    assertEquals(0, loaded.tabs().get(1).syncGroup());
    assertFalse(
        Files.readString(temp.resolve(SessionStore.FILE), StandardCharsets.ISO_8859_1)
            .contains("tab.1.syncGroup"));
    Files.writeString(
        temp.resolve(SessionStore.FILE),
        "window.0.active=0\n"
            + "window.0.tab.0.path=/mock/a.log\n"
            + "window.0.tab.0.syncGroup=soon\n"
            + "window.0.tab.1.path=/mock/b.log\n"
            + "window.0.tab.1.syncGroup=-3\n",
        StandardCharsets.ISO_8859_1);
    List<Session.Tab> tabs = new SessionStore(location()).load().tabs();
    assertEquals(0, tabs.get(0).syncGroup());
    assertEquals(0, tabs.get(1).syncGroup());
  }

  @Test
  void theDroppedCommentCountRoundTripsAndReadsAsNoneWhenAbsentOrMalformed() throws Exception {
    SessionStore store = new SessionStore(location());
    Session.Tab tab = new Session.Tab("/mock/a.log", "", false, new long[0]);
    assertTrue(
        store.saveWindows(List.of(new Session(List.of(tab), 0, 2), new Session(List.of(), -1, 5))));
    List<Session> windows = new SessionStore(location()).loadWindows();
    assertEquals(7, windows.get(0).droppedComments());
    assertEquals(0, windows.get(1).droppedComments());
    assertTrue(store.save(new Session(List.of(tab), 0)));
    assertFalse(Files.readString(temp.resolve(SessionStore.FILE)).contains("droppedComments"));
    String tabs = "window.0.active=0\nwindow.0.tab.0.path=/mock/a.log\n";
    for (String file : List.of(tabs, tabs + "droppedComments=x\n", tabs + "droppedComments=-4\n")) {
      Files.writeString(temp.resolve(SessionStore.FILE), file, StandardCharsets.ISO_8859_1);
      Session session = new SessionStore(location()).load();
      assertEquals(1, session.tabs().size());
      assertEquals(0, session.droppedComments());
    }
  }

  @Test
  void sessionRoundTripsMarkCommentsAndSkipsMalformedOnes() throws Exception {
    SessionStore store = new SessionStore(location());
    List<MarkComments.Comment> comments =
        List.of(new MarkComments.Comment(3, "flagword seen"), new MarkComments.Comment(7, "b=c"));
    Session.Tab tab = new Session.Tab("/mock/a.log", "", false, new long[] {3, 7}, comments);
    assertTrue(store.save(new Session(List.of(tab), 0)));
    assertEquals(comments, new SessionStore(location()).load().tabs().get(0).comments());
    Files.writeString(
        temp.resolve(SessionStore.FILE),
        "window.0.active=0\n"
            + "window.0.tab.0.path=/mock/a.log\n"
            + "window.0.tab.0.marks=1-2\n"
            + "window.0.tab.0.comment.0=x y\n"
            + "window.0.tab.0.comment.1=nospace\n"
            + "window.0.tab.0.comment.2=2 kept\\tnote\n"
            + "window.0.tab.0.comment.4=1 after a gap\n",
        StandardCharsets.ISO_8859_1);
    assertEquals(
        List.of(new MarkComments.Comment(2, "kept note")),
        new SessionStore(location()).load().tabs().get(0).comments());
  }

  @Test
  void aRotatedTabRoundTripsItsMemberMarksUnderTheirDigests() throws Exception {
    SessionStore store = new SessionStore(location());
    Session.MemberMarks old =
        new Session.MemberMarks(
            0xfedcba9876543210L,
            new long[] {0, 2, 5, 5},
            List.of(new MarkComments.Comment(5, "flagword seen")));
    Session.MemberMarks active = new Session.MemberMarks(0x2aL, new long[] {1, 1}, List.of());
    Session.Tab tab =
        new Session.Tab(
            "/mock/app.log", "", true, new long[0], List.of(), 0, 0, true, List.of(old, active));
    assertTrue(store.save(new Session(List.of(tab), 0)));
    String text = Files.readString(temp.resolve(SessionStore.FILE), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("window.0.tab.0.rotated=1"), text);
    assertTrue(text.contains("window.0.tab.0.rmarks.0=fedcba9876543210 0-2,5"), text);
    assertTrue(text.contains("window.0.tab.0.rcomment.0=fedcba9876543210 5 flagword seen"), text);
    Session.Tab loaded = new SessionStore(location()).load().tabs().get(0);
    assertTrue(loaded.rotated());
    assertEquals(2, loaded.members().size());
    assertEquals(old.digest(), loaded.members().get(0).digest());
    assertArrayEquals(old.marks(), loaded.members().get(0).marks());
    assertEquals(old.comments(), loaded.members().get(0).comments());
    assertArrayEquals(active.marks(), loaded.members().get(1).marks());
    Session.Tab plain = new Session.Tab("/mock/b.log", "", false, new long[] {1, 1});
    assertTrue(store.save(new Session(List.of(plain), 0)));
    assertFalse(new SessionStore(location()).load().tabs().get(0).rotated());
  }

  @Test
  void recentlyClosedKeepsWhichFilesClosedAsSets() {
    UiState state =
        UiState.DEFAULTS
            .addClosed("/mock/a.log", true, EXACT)
            .addClosed("/mock/b.log", false, EXACT)
            .addClosed("/mock/c.log", true, EXACT);
    assertEquals(List.of("/mock/a.log", "/mock/c.log"), state.closedSets());
    ConfigStore store = new ConfigStore(location());
    assertTrue(store.save(Preferences.DEFAULTS, Preferences.DEFAULTS.withState(state)));
    UiState loaded = new ConfigStore(location()).load().state();
    assertEquals(List.of("/mock/c.log", "/mock/b.log", "/mock/a.log"), loaded.closedFiles());
    assertEquals(List.of("/mock/c.log", "/mock/a.log"), loaded.closedSets());
    assertEquals(List.of("/mock/a.log"), loaded.removeClosed("/mock/c.log", EXACT).closedSets());
    assertEquals(
        List.of("/mock/c.log"), loaded.addClosed("/mock/a.log", false, EXACT).closedSets());
  }

  @Test
  void eachWindowKeepsItsOwnTabs() {
    SessionStore store = new SessionStore(location());
    Session first = new Session(List.of(new Session.Tab("/mock/a.log", "", false, new long[0])), 0);
    Session second =
        new Session(
            List.of(
                new Session.Tab("/mock/b.log", "Beta", true, new long[0]),
                new Session.Tab("/mock/c.log", "", false, new long[] {4, 4})),
            1);
    Session empty = new Session(List.of(), -1);
    assertTrue(store.saveWindows(List.of(first, second, empty)));
    List<Session> loaded = new SessionStore(location()).loadWindows();
    assertEquals(3, loaded.size());
    assertEquals("/mock/a.log", loaded.get(0).tabs().get(0).path());
    assertEquals(1, loaded.get(1).active());
    assertEquals("Beta", loaded.get(1).tabs().get(0).name());
    assertArrayEquals(new long[] {4, 4}, loaded.get(1).tabs().get(1).marks());
    assertTrue(loaded.get(2).tabs().isEmpty());
    assertEquals("/mock/a.log", new SessionStore(location()).load().tabs().get(0).path());
  }

  @Test
  void eachWindowKeepsItsOwnBoundsWrittenOnlyWhenKnown() throws Exception {
    SessionStore store = new SessionStore(location());
    Session placed = new Session(List.of(), -1, 0, false, new WindowState(78, -25, 900, 600, true));
    Session unplaced = new Session(List.of(), -1, 0, false, WindowState.UNKNOWN);
    assertTrue(store.saveWindows(List.of(placed, unplaced)));
    String text = Files.readString(temp.resolve(SessionStore.FILE), StandardCharsets.ISO_8859_1);
    assertFalse(text.contains("window.1.x"), text);
    assertFalse(text.contains("window.1.maximized"), text);
    List<Session> loaded = new SessionStore(location()).loadWindows();
    assertEquals(new WindowState(78, -25, 900, 600, true), loaded.get(0).window());
    assertEquals(WindowState.UNKNOWN, loaded.get(1).window());
    Files.writeString(
        temp.resolve(SessionStore.FILE),
        "window.0.active=-1\nwindow.0.x=left\nwindow.0.y=40\n",
        StandardCharsets.ISO_8859_1);
    assertEquals(
        new WindowState(null, 40, null, null, false), new SessionStore(location()).load().window());
  }

  @Test
  void markRangesConvertBothWays() {
    MutableLineSet marks = new MutableLineSet();
    for (long line : new long[] {1, 2, 3, 9, 70000, 70001}) {
      marks.add(line);
    }
    long[] ranges = SessionStore.ranges(marks, 100);
    assertArrayEquals(new long[] {1, 3, 9, 9, 70000, 70001}, ranges);
    assertEquals("1-3,9,70000-70001", SessionStore.formatMarks(ranges));
    assertArrayEquals(ranges, SessionStore.parseMarks("1-3,9,70000-70001", 100));
    assertArrayEquals(new long[] {1, 3}, SessionStore.ranges(marks, 1));
    assertArrayEquals(new long[] {1, 3}, SessionStore.parseMarks("1-3,2,9", 100));
    MutableLineSet back = new MutableLineSet();
    assertTrue(SessionStore.addRanges(ranges, back));
    assertEquals(6, back.size());
  }

  @Test
  void aMissingSessionIsEmpty() {
    assertEquals(0, new SessionStore(location()).load().tabs().size());
  }
}
