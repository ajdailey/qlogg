package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.search.SearchHistory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The range and context keys of {@code searchOption.*} through the settings file. */
class RangeContextOptionsTest {
  @TempDir Path temp;

  private SettingsLocation location() {
    return new SettingsLocation(SettingsMode.EXPLICIT, temp);
  }

  @Test
  void rangeAndContextRoundTrip() {
    SearchToggles toggles =
        new SearchToggles(false, true, false, true, false, true, false, true, "END = x", 3, 1000);
    ConfigStore store = new ConfigStore(location());
    assertTrue(
        store.save(
            Preferences.DEFAULTS.withState(Preferences.DEFAULTS.state().withSearch(toggles))));
    assertEquals(toggles, new ConfigStore(location()).load().state().search());
  }

  @Test
  void aFileWithoutTheKeysLoadsTheEarlierToggles() throws Exception {
    Files.writeString(
        temp.resolve(SettingsLocation.MARKER),
        "searchOption.regex=false\nsearchOption.inverse=true\n",
        StandardCharsets.ISO_8859_1);
    assertEquals(
        new SearchToggles(false, true, false, true, false, false, false),
        new ConfigStore(location()).load().state().search());
  }

  @Test
  void malformedValuesFallBackToDefaults() throws Exception {
    Files.writeString(
        temp.resolve(SettingsLocation.MARKER),
        "searchOption.range=yes\nsearchOption.contextBefore=-1\nsearchOption.contextAfter=1001\n"
            + "searchOption.rangeEnd="
            + "x".repeat(SearchHistory.MAX_ENTRY_CHARS + 1)
            + "\n",
        StandardCharsets.ISO_8859_1);
    assertEquals(SearchToggles.DEFAULTS, new ConfigStore(location()).load().state().search());
  }
}
