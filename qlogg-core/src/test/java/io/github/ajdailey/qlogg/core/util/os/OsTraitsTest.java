package io.github.ajdailey.qlogg.core.util.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class OsTraitsTest {
  @TempDir Path temp;

  @Test
  void eachNameSelectsOneSystem() {
    assertSame(WindowsTraits.INSTANCE, OsTraits.of("Windows 11"));
    assertSame(WindowsTraits.INSTANCE, OsTraits.of("Windows Server 2022"));
    assertSame(MacTraits.INSTANCE, OsTraits.of("Mac OS X"));
    assertSame(LinuxTraits.INSTANCE, OsTraits.of("Linux"));
    assertSame(LinuxTraits.INSTANCE, OsTraits.of("FreeBSD"));
    assertSame(LinuxTraits.INSTANCE, OsTraits.of(null));
  }

  @Test
  void theCurrentSystemFollowsThisJvm() {
    assertSame(OsTraits.of(System.getProperty("os.name")), OsTraits.current());
    assertEquals(
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch"),
        OsTraits.description());
  }

  @Test
  void windows() {
    OsTraits os = OsTraits.of("Windows 11");
    assertTrue(os.deviceNames().opensDevice("CON"));
    assertTrue(os.confinement().embedsGuide(false));
    assertFalse(os.macKeymap());
    Path roaming = temp.resolve("Roaming");
    assertEquals(
        roaming.resolve("QLogg"), os.userSettings(Map.of("APPDATA", roaming.toString()), temp));
    assertEquals(
        temp.resolve("AppData").resolve("Roaming").resolve("QLogg"),
        os.userSettings(Map.of("APPDATA", "relative"), temp));
  }

  @Test
  void macos() {
    OsTraits os = OsTraits.of("Mac OS X");
    assertFalse(os.deviceNames().opensDevice("CON"));
    assertTrue(os.confinement().embedsGuide(false));
    assertTrue(os.macKeymap());
    assertEquals(
        temp.resolve("Library").resolve("Application Support").resolve("QLogg"),
        os.userSettings(Map.of("XDG_CONFIG_HOME", temp.toString()), temp));
  }

  @Test
  void linux() {
    OsTraits os = OsTraits.of("Linux");
    assertFalse(os.deviceNames().opensDevice("CON"));
    assertFalse(os.confinement().embedsGuide(false));
    assertTrue(os.confinement().embedsGuide(true));
    assertFalse(os.macKeymap());
    Path xdg = temp.resolve("xdg");
    assertEquals(
        xdg.resolve("qlogg"), os.userSettings(Map.of("XDG_CONFIG_HOME", xdg.toString()), temp));
    assertEquals(temp.resolve(".config").resolve("qlogg"), os.userSettings(Map.of(), temp));
  }

  @Test
  void runnableFilesFollowTheSystem() throws IOException {
    Path log = Files.writeString(temp.resolve("app.log"), "x\n");
    Path jar = Files.writeString(temp.resolve("tool.jar"), "x\n");
    Path terminal = Files.writeString(temp.resolve("shell.terminal"), "x\n");
    OsTraits windows = OsTraits.of("Windows 11");
    assertTrue(windows.runsInsteadOfOpening(jar));
    assertFalse(windows.runsInsteadOfOpening(terminal));
    assertFalse(windows.runsInsteadOfOpening(log));
    OsTraits mac = OsTraits.of("Mac OS X");
    assertTrue(mac.runsInsteadOfOpening(jar));
    assertTrue(mac.runsInsteadOfOpening(terminal));
  }

  /** Windows reports every readable file as executable, so the execute bit is set on POSIX only. */
  @Test
  @DisabledOnOs(OS.WINDOWS)
  void theExecuteBitDecidesOnMacOsAndLinux() throws IOException {
    Path log = Files.writeString(temp.resolve("app.log"), "x\n");
    Path terminal = Files.writeString(temp.resolve("shell.terminal"), "x\n");
    assertFalse(OsTraits.of("Linux").runsInsteadOfOpening(terminal));
    assertFalse(OsTraits.of("Mac OS X").runsInsteadOfOpening(log));
    assertFalse(OsTraits.of("Linux").runsInsteadOfOpening(log));
    Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rwx------"));
    assertTrue(OsTraits.of("Mac OS X").runsInsteadOfOpening(log));
    assertTrue(OsTraits.of("Linux").runsInsteadOfOpening(log));
    assertFalse(OsTraits.of("Windows 11").runsInsteadOfOpening(log));
  }

  @Test
  void onlyANonEmptyAbsolutePathIsAccepted() {
    assertEquals(temp, EnvPaths.absolute(temp.resolve("a").resolve("..").toString()));
    assertNull(EnvPaths.absolute(null));
    assertNull(EnvPaths.absolute(""));
    assertNull(EnvPaths.absolute("relative"));
    assertNull(EnvPaths.absolute(temp + "\0x"));
  }
}
