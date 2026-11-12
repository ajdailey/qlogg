package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsLocationTest {
  @TempDir Path temp;

  private SettingsLocation resolve(Path explicit, Path home, OsTraits os, Map<String, String> env) {
    return SettingsLocation.resolve(
        explicit, home == null ? null : home.toString(), os, env, temp.toString());
  }

  @Test
  void explicitWinsOverPortable() throws IOException {
    Path home = Files.createDirectory(temp.resolve("home"));
    Files.createFile(home.resolve("qlogg.conf"));
    Path explicit = temp.resolve("explicit");
    assertEquals(
        new SettingsLocation(SettingsMode.EXPLICIT, explicit),
        resolve(explicit, home, OsTraits.of("Linux"), Map.of()));
    assertEquals(
        new SettingsLocation(SettingsMode.PORTABLE, home),
        resolve(null, home, OsTraits.of("Linux"), Map.of()));
  }

  @Test
  void onLinuxAMarkerBesideTheLauncherScriptSelectsPortable() throws IOException {
    Path home = Files.createDirectory(temp.resolve("home"));
    Path bin = Files.createDirectory(home.resolve("bin"));
    Files.createFile(bin.resolve("qlogg.conf"));
    assertEquals(
        new SettingsLocation(SettingsMode.PORTABLE, bin),
        resolve(null, home, OsTraits.of("Linux"), Map.of()));
    assertEquals(
        SettingsMode.PER_USER, resolve(null, home, OsTraits.of("Windows 11"), Map.of()).mode());
    assertEquals(
        SettingsMode.PER_USER, resolve(null, home, OsTraits.of("Mac OS X"), Map.of()).mode());
    // With both markers, the installation directory wins.
    Files.createFile(home.resolve("qlogg.conf"));
    assertEquals(
        new SettingsLocation(SettingsMode.PORTABLE, home),
        resolve(null, home, OsTraits.of("Linux"), Map.of()));
  }

  @Test
  void withoutTheMarkerTheDirectoryIsPerUser() throws IOException {
    Path home = Files.createDirectory(temp.resolve("home"));
    Files.createDirectory(home.resolve("qlogg.conf"));
    assertEquals(
        new SettingsLocation(SettingsMode.PER_USER, temp.resolve(".config").resolve("qlogg")),
        resolve(null, home, OsTraits.of("Linux"), Map.of()));
  }

  @Test
  void perUserDirectoriesFollowTheOs() {
    Path xdg = temp.resolve("xdg");
    assertEquals(
        xdg.resolve("qlogg"),
        resolve(null, null, OsTraits.of("Linux"), Map.of("XDG_CONFIG_HOME", xdg.toString()))
            .directory());
    assertEquals(
        temp.resolve(".config").resolve("qlogg"),
        resolve(null, null, OsTraits.of("Linux"), Map.of("XDG_CONFIG_HOME", "relative"))
            .directory());
    Path appData = temp.resolve("Roaming");
    assertEquals(
        appData.resolve("QLogg"),
        resolve(null, null, OsTraits.of("Windows 11"), Map.of("APPDATA", appData.toString()))
            .directory());
    assertEquals(
        temp.resolve("AppData").resolve("Roaming").resolve("QLogg"),
        resolve(null, null, OsTraits.of("Windows 11"), Map.of()).directory());
    assertEquals(
        temp.resolve("Library").resolve("Application Support").resolve("QLogg"),
        resolve(null, null, OsTraits.of("Mac OS X"), Map.of()).directory());
  }
}
