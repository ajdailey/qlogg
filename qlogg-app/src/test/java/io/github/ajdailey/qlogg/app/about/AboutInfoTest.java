package io.github.ajdailey.qlogg.app.about;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AboutInfoTest {
  @TempDir Path temp;

  @Test
  void theOsLineIsNameVersionAndArchitecture() {
    AboutInfo info =
        AboutInfo.collect("SWT mock", new SettingsLocation(SettingsMode.EXPLICIT, temp));
    assertEquals(
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch"),
        info.os());
  }
}
