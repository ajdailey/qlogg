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
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Options, UI state, positions and the session through their files. */
class StateStoresTest {
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
            true);
    UiState state =
        UiState.DEFAULTS
            .withWindow(new WindowState(-5, 10, 900, 600, true), 55)
            .withSearch(new SearchToggles(false, true, false, true, false, false, false))
            .withLineNumbers(false, true)
            .withOpenDirectory("/mock")
            .addRecent("/mock/a.log", 7)
            .addRecent("/mock/c.log", 7)
            .withDialog("options", new UiState.Size(640, 480));
    ConfigStore store = new ConfigStore(location());
    assertTrue(store.save(Preferences.DEFAULTS.withOptions(options).withState(state)));
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
        temp.resolve(SettingsLocation.MARKER),
        "tabs.closeWindowWithLastTab=true\n",
        StandardCharsets.ISO_8859_1);
    Preferences loaded = new ConfigStore(location()).load();
    assertTrue(loaded.options().closeWindowWithLastTab());
    assertTrue(new ConfigStore(location()).save(loaded.withOptions(Options.DEFAULTS)));
    assertTrue(
        Files.readString(temp.resolve(SettingsLocation.MARKER), StandardCharsets.ISO_8859_1)
            .contains("tabs.closeWindowWithLastTab=false"));
    assertFalse(new ConfigStore(location()).load().options().closeWindowWithLastTab());
  }

  // Search in a folder.

  @Test
  void folderSearchSettingsRoundTripAndAnOverlongValueReadsAsTheDefault() throws Exception {
    FolderSearchSettings folder = new FolderSearchSettings("/mock/logs", "*.log;*.txt", true);
    ConfigStore store = new ConfigStore(location());
    assertTrue(
        store.save(Preferences.DEFAULTS.withState(UiState.DEFAULTS.withFolderSearch(folder))));
    assertEquals(folder, new ConfigStore(location()).load().state().folderSearch());
    Files.writeString(
        temp.resolve(SettingsLocation.MARKER),
        "folderSearch.folder="
            + "x".repeat(FolderSearchSettings.MAX_CHARS + 1)
            + "\nfolderSearch.names=*.out\nfolderSearch.recursive=maybe\n",
        StandardCharsets.ISO_8859_1);
    assertEquals(
        new FolderSearchSettings("", "*.out", false),
        new ConfigStore(location()).load().state().folderSearch());
  }

  @Test
  void invalidValuesFallBackToDefaults() throws Exception {
    Files.writeString(
        temp.resolve(SettingsLocation.MARKER),
        "font.size=900\nrecent.count=x\nencoding.default=no-such-charset\nwindow.maximized=yes\n"
            + "monitor.pollMillis=5\nshortcut.OPEN.count=1\nshortcut.OPEN.0=nowhere F2\n",
        StandardCharsets.ISO_8859_1);
    Preferences loaded = new ConfigStore(location()).load();
    assertEquals(0, loaded.options().font().size());
    assertEquals(Options.DEFAULTS.recentCount(), loaded.options().recentCount());
    assertEquals("ISO-8859-1", loaded.options().defaultEncoding());
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
  void positionsKeepTheTimestampChoices() throws Exception {
    PositionStore store = new PositionStore(location());
    Path file = Path.of("/mock/t.log");
    store.setTimeFormat(file, "user:app");
    store.setZone(file, "Europe/Berlin");
    store.put(file, 4, 5, 6);
    store.setEncoding(file, StandardCharsets.UTF_8);
    store.setTimeFormat(Path.of("/mock/n.log"), TimeSettings.NO_FORMAT);
    assertTrue(store.save());
    PositionStore loaded = new PositionStore(location());
    loaded.load();
    assertEquals(
        new PositionStore.Entry(4, 5, 6, "UTF-8", "user:app", "Europe/Berlin"), loaded.get(file));
    assertEquals(TimeSettings.NO_FORMAT, loaded.get(Path.of("/mock/n.log")).timeFormat());
    assertEquals("", loaded.get(Path.of("/mock/n.log")).zone());
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
