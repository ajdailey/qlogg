package io.github.ajdailey.qlogg.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class BootTest {
  @TempDir Path temp;

  @Test
  void findsSettingsBeforeDoubleDash() {
    assertEquals("a", Boot.settingsArgument(new String[] {"-m", "--settings", "a", "f"}));
    assertEquals("b", Boot.settingsArgument(new String[] {"--settings=b"}));
    assertNull(Boot.settingsArgument(new String[] {"--", "--settings", "c"}));
    assertNull(Boot.settingsArgument(new String[] {"--settings"}));
  }

  @Test
  void homeIsParentOfLib() {
    Path lib = temp.resolve("qlogg").resolve("lib");
    assertEquals(temp.resolve("qlogg"), Boot.home(lib.resolve("qlogg.jar")));
    assertEquals(temp, Boot.home(temp.resolve("qlogg.jar")));
  }

  @Test
  void settingsDirectoryIsCreated() throws Exception {
    Path dir = Boot.settingsDirectory(temp.resolve("s").resolve("t").toString());
    assertTrue(Files.isDirectory(dir));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void missingSettingsDirectoryIsOwnerOnly() throws Exception {
    Path dir = Boot.settingsDirectory(temp.resolve("s").resolve("t").toString());
    assertEquals("rwx------", mode(dir));
    assertEquals("rwx------", mode(dir.getParent()));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void existingSettingsDirectoryKeepsItsMode() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("shared"));
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxr-x"));
    Boot.settingsDirectory(dir.toString());
    assertEquals("rwxrwxr-x", mode(dir));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void settingsDirectoryMayBeASymbolicLink() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("real"));
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    Path link = Files.createSymbolicLink(temp.resolve("link"), dir);
    assertEquals(dir.toRealPath(), Boot.settingsDirectory(link.toString()));
    assertEquals("rwxr-xr-x", mode(dir));
  }

  private static String mode(Path dir) throws Exception {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(dir));
  }

  @Test
  void nativesLocationPrecedence() throws Exception {
    Path home = Files.createDirectory(temp.resolve("home"));
    Map<String, String> env = Map.of("XDG_CACHE_HOME", temp.resolve("xdg").toString());
    NativesLocation user =
        NativesLocation.resolve(home, null, Target.LINUX_X86_64, "3.135.0", env, "/u");
    assertEquals(
        temp.resolve("xdg").resolve("qlogg").resolve("swt").resolve("3.135.0-linux-x86_64"),
        user.dir());
    assertTrue(user.writable());

    Path settings = temp.resolve("settings");
    assertEquals(
        settings.resolve("cache").resolve("swt").resolve("3.135.0-linux-x86_64"),
        NativesLocation.resolve(home, settings, Target.LINUX_X86_64, "3.135.0", env, "/u").dir());

    // On Linux, a marker beside bin/qlogg; the installation directory's wins.
    Path bin = Files.createDirectory(home.resolve("bin"));
    Files.createFile(bin.resolve("qlogg.conf"));
    assertEquals(
        bin.resolve("cache").resolve("swt").resolve("3.135.0-linux-x86_64"),
        NativesLocation.resolve(home, null, Target.LINUX_X86_64, "3.135.0", env, "/u").dir());
    assertEquals(
        Path.of(
            "/u", "Library", "Caches", "io.github.ajdailey.qlogg", "swt", "3.135.0-macos-aarch64"),
        NativesLocation.resolve(home, null, Target.MACOS_AARCH64, "3.135.0", env, "/u").dir());

    Files.createFile(home.resolve("qlogg.conf"));
    assertEquals(
        home.resolve("cache").resolve("swt").resolve("3.135.0-linux-x86_64"),
        NativesLocation.resolve(home, null, Target.LINUX_X86_64, "3.135.0", env, "/u").dir());

    Path bundled = Files.createDirectories(home.resolve("lib").resolve("swt"));
    NativesLocation bundle =
        NativesLocation.resolve(home, settings, Target.LINUX_X86_64, "3.135.0", env, "/u");
    assertEquals(bundled, bundle.dir());
    assertFalse(bundle.writable());
  }

  @Test
  void relativeXdgCacheHomeIsIgnored() {
    Path dir =
        NativesLocation.resolve(
                temp, null, Target.LINUX_X86_64, "3", Map.of("XDG_CACHE_HOME", "rel"), "/u")
            .dir();
    assertEquals(Path.of("/u", ".cache", "qlogg", "swt", "3-linux-x86_64"), dir);
  }

  @Test
  void macRelaunchDecision() {
    Map<String, String> onMain = Map.of("JAVA_STARTED_ON_FIRST_THREAD_7", "1");
    assertEquals(
        MacRelaunch.Decision.PROCEED, MacRelaunch.decide(Target.LINUX_X86_64, Map.of(), 7, null));
    assertEquals(
        MacRelaunch.Decision.PROCEED, MacRelaunch.decide(Target.MACOS_AARCH64, onMain, 7, null));
    assertEquals(
        MacRelaunch.Decision.RELAUNCH, MacRelaunch.decide(Target.MACOS_AARCH64, Map.of(), 7, null));
    assertEquals(
        MacRelaunch.Decision.FAIL, MacRelaunch.decide(Target.MACOS_X86_64, Map.of(), 7, "true"));
  }

  @Test
  void relaunchCommandKeepsEachArgument() {
    assertEquals(
        List.of(
            "/j/bin/java",
            "-XstartOnFirstThread",
            "-Dqlogg.relaunched=true",
            "-jar",
            Path.of("/q/lib/qlogg.jar").toString(),
            "a b",
            "; rm x"),
        MacRelaunch.command(
            "/j/bin/java", Path.of("/q/lib/qlogg.jar"), new String[] {"a b", "; rm x"}));
  }
}
