package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The path keys of {@code qlogg.conf}, the session and the positions through their files. */
class SavedPathsTest {
  @TempDir Path temp;

  private Path install;
  private Path log;
  private Path outside;

  @BeforeEach
  void layout() throws IOException {
    install = Files.createDirectories(temp.resolve("install"));
    Files.writeString(install.resolve(SettingsLocation.MARKER), "");
    log = Files.writeString(Files.createDirectories(install.resolve("logs")).resolve("a.log"), "a");
    outside = Files.writeString(temp.resolve("outside.log"), "o");
  }

  private static SettingsLocation portable(Path home) {
    return new SettingsLocation(SettingsMode.PORTABLE, home, home);
  }

  private static Properties read(Path file) throws IOException {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      properties.load(reader);
    }
    return properties;
  }

  /** Every path key holding {@code first}, and {@code second} where a list has room. */
  private static Preferences preferences(String first, String second) {
    Preferences d = Preferences.DEFAULTS;
    return d.withOptions(d.options().withFavorites(List.of(first, second)))
        .withState(
            d.state()
                .withOpenDirectory(first)
                .withRecentFiles(List.of(first, second))
                .withClosedFiles(List.of(first))
                .withFolderSearch(new FolderSearchSettings(first, "*.log", false, "")));
  }

  private static Session session(String path) {
    return new Session(
        List.of(new Session.Tab(path, "", false, new long[0], List.of())), 0, 0, false);
  }

  @Test
  void aMovedPortableInstallKeepsTabsRecentsFavoritesAndPositions() throws IOException {
    ConfigStore config = new ConfigStore(portable(install));
    assertTrue(config.save(preferences(log.toString(), outside.toString())));
    assertTrue(config.sessions().save(session(log.toString())));
    config.positions().put(log, 3, 4, 5);
    assertTrue(config.positions().save());

    Properties conf = read(install.resolve(SettingsLocation.MARKER));
    assertEquals("logs/a.log", conf.getProperty("favorite.0"));
    assertEquals(outside.toString(), conf.getProperty("favorite.1"));
    assertEquals("logs/a.log", conf.getProperty("recent.0"));
    assertEquals(outside.toString(), conf.getProperty("recent.1"));
    assertEquals("logs/a.log", conf.getProperty("closed.0"));
    assertEquals("logs/a.log", conf.getProperty("open.directory"));
    assertEquals("logs/a.log", conf.getProperty("folderSearch.folder"));
    assertEquals(
        "logs/a.log", read(install.resolve(SessionStore.FILE)).getProperty("window.0.tab.0.path"));
    assertEquals("logs/a.log", read(install.resolve(PositionStore.FILE)).getProperty("pos.0.path"));

    Path moved = Files.move(install, temp.resolve("moved"));
    String expected = moved.toRealPath().resolve("logs").resolve("a.log").toString();
    ConfigStore again = new ConfigStore(portable(moved));
    Preferences loaded = again.load();
    assertFalse(again.pathsStale());
    assertEquals(List.of(expected, outside.toString()), loaded.options().favorites());
    assertEquals(List.of(expected, outside.toString()), loaded.state().recentFiles());
    assertEquals(List.of(expected), loaded.state().closedFiles());
    assertEquals(expected, loaded.state().openDirectory());
    assertEquals(expected, loaded.state().folderSearch().folder());
    assertEquals(expected, again.sessions().load().tabs().get(0).path());
    again.positions().load();
    assertEquals(3, again.positions().get(Path.of(expected)).top());
    assertFalse(again.positions().save());
  }

  @Test
  void anUpgradedPortableInstallIsRewrittenRelativeOnce() throws IOException {
    String absolute = log.toString();
    ConfigStore perUser =
        new ConfigStore(new SettingsLocation(SettingsMode.PER_USER, install, install));
    assertTrue(perUser.save(preferences(absolute, absolute)));
    perUser.positions().put(log, 1, 1, 0);
    assertTrue(perUser.positions().save());
    assertEquals(absolute, read(install.resolve(SettingsLocation.MARKER)).getProperty("recent.0"));

    ConfigStore config = new ConfigStore(portable(install));
    Preferences loaded = config.load();
    assertTrue(config.pathsStale());
    config.positions().load();
    assertTrue(config.positions().save());
    assertTrue(config.save(loaded));
    assertFalse(config.pathsStale());
    Properties conf = read(install.resolve(SettingsLocation.MARKER));
    assertEquals("logs/a.log", conf.getProperty("recent.0"));
    assertEquals("logs/a.log", conf.getProperty("favorite.1"));
    assertEquals("logs/a.log", read(install.resolve(PositionStore.FILE)).getProperty("pos.0.path"));

    ConfigStore second = new ConfigStore(portable(install));
    second.load();
    assertFalse(second.pathsStale());
    second.positions().load();
    assertFalse(second.positions().save());
  }

  @Test
  void perUserModeWritesAbsoluteAndRewritesNothing() throws IOException {
    Path settings = Files.createDirectories(temp.resolve("settings"));
    SettingsLocation location = new SettingsLocation(SettingsMode.PER_USER, settings, install);
    ConfigStore config = new ConfigStore(location);
    assertTrue(config.save(preferences(log.toString(), log.toString())));
    assertTrue(config.sessions().save(session(log.toString())));
    config.positions().put(log, 1, 1, 0);
    assertTrue(config.positions().save());
    assertEquals(
        log.toString(), read(settings.resolve(SettingsLocation.MARKER)).getProperty("recent.0"));
    assertEquals(
        log.toString(),
        read(settings.resolve(SessionStore.FILE)).getProperty("window.0.tab.0.path"));

    ConfigStore again = new ConfigStore(location);
    again.load();
    assertFalse(again.pathsStale());
    again.positions().load();
    assertFalse(again.positions().save());
  }

  @Test
  void aRelativeValueResolvesAgainstTheInstallationInPerUserMode() throws IOException {
    Path settings = Files.createDirectories(temp.resolve("settings"));
    Files.writeString(
        settings.resolve(SessionStore.FILE),
        "window.0.active=0\nwindow.0.tab.0.path=logs/a.log\n",
        StandardCharsets.ISO_8859_1);
    SessionStore sessions =
        new SessionStore(new SettingsLocation(SettingsMode.PER_USER, settings, install));
    assertEquals(
        install.toRealPath().resolve("logs").resolve("a.log").toString(),
        sessions.load().tabs().get(0).path());
  }
}
