package io.github.ajdailey.qlogg.app.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeControllerTest {
  @TempDir Path temp;

  /** A system setting the test flips. */
  private static final class FakeSystem implements ThemeService {
    boolean dark;
    final List<ThemeListener> listeners = new ArrayList<ThemeListener>();

    @Override
    public boolean systemDark() {
      return dark;
    }

    @Override
    public void addListener(ThemeListener listener) {
      listeners.add(listener);
    }

    void flip(boolean value) {
      dark = value;
      for (ThemeListener listener : listeners) {
        listener.systemThemeChanged(value);
      }
    }
  }

  @Test
  void modesResolveToPalettes() {
    assertSame(Palette.LIGHT, ThemeController.resolve(ThemeMode.LIGHT, true));
    assertSame(Palette.DARK, ThemeController.resolve(ThemeMode.DARK, false));
    assertSame(Palette.DARK, ThemeController.resolve(ThemeMode.SYSTEM, true));
    assertSame(Palette.LIGHT, ThemeController.resolve(ThemeMode.SYSTEM, false));
  }

  @Test
  void systemModeFollowsTheSystemAndListenersSeeEachChange() {
    FakeSystem system = new FakeSystem();
    ThemeController theme = new ThemeController(system, ThemeMode.SYSTEM);
    final List<Palette> seen = new ArrayList<Palette>();
    theme.addListener(
        new PaletteListener() {
          @Override
          public void paletteChanged(Palette palette) {
            seen.add(palette);
          }
        });
    assertSame(Palette.LIGHT, theme.palette());
    system.flip(true);
    system.flip(true);
    theme.setMode(ThemeMode.LIGHT);
    system.flip(false);
    theme.setMode(ThemeMode.DARK);
    assertEquals(List.of(Palette.DARK, Palette.LIGHT, Palette.DARK), seen);
    assertEquals(ThemeMode.DARK, theme.mode());
  }

  @Test
  void themeRoundTripsInPortableMode() throws Exception {
    Files.writeString(temp.resolve(SettingsLocation.MARKER), "", StandardCharsets.ISO_8859_1);
    SettingsLocation location =
        SettingsLocation.resolve(
            null,
            temp.toString(),
            "Linux",
            Map.<String, String>of(),
            temp.resolve("home").toString());
    assertEquals(SettingsMode.PORTABLE, location.mode());
    ConfigStore store = new ConfigStore(location);
    Preferences loaded = store.load();
    assertEquals(ThemeMode.LIGHT, loaded.theme());
    store.save(loaded.withTheme(ThemeMode.SYSTEM));
    assertEquals(ThemeMode.SYSTEM, new ConfigStore(location).load().theme());
    List<String> lines =
        Files.readAllLines(temp.resolve(SettingsLocation.MARKER), StandardCharsets.ISO_8859_1);
    assertTrue(lines.contains("ui.theme=system"));
    assertEquals(List.of(SettingsLocation.MARKER), names(temp));
  }

  private static List<String> names(Path dir) throws java.io.IOException {
    List<String> names = new ArrayList<String>();
    try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
    }
    return names;
  }
}
