package io.github.ajdailey.qlogg.core.util.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
    assertTrue(os.opensDeviceNames());
    assertFalse(os.confinesToSettings());
    assertTrue(os.webView2());
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
    assertFalse(os.opensDeviceNames());
    assertFalse(os.confinesToSettings());
    assertFalse(os.webView2());
    assertTrue(os.macKeymap());
    assertEquals(
        temp.resolve("Library").resolve("Application Support").resolve("QLogg"),
        os.userSettings(Map.of("XDG_CONFIG_HOME", temp.toString()), temp));
  }

  @Test
  void linux() {
    OsTraits os = OsTraits.of("Linux");
    assertFalse(os.opensDeviceNames());
    assertTrue(os.confinesToSettings());
    assertFalse(os.webView2());
    assertFalse(os.macKeymap());
    Path xdg = temp.resolve("xdg");
    assertEquals(
        xdg.resolve("qlogg"), os.userSettings(Map.of("XDG_CONFIG_HOME", xdg.toString()), temp));
    assertEquals(temp.resolve(".config").resolve("qlogg"), os.userSettings(Map.of(), temp));
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
