package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.util.os.EnvPaths;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The settings directory and how it was chosen. In order of precedence: {@code --settings <dir>};
 * {@code <qlogg.home>} when {@code <qlogg.home>/qlogg.conf} exists (portable); the per-user
 * directory: {@code $XDG_CONFIG_HOME/qlogg} (default {@code ~/.config/qlogg}) on Linux, {@code
 * %APPDATA%\QLogg} on Windows, {@code ~/Library/Application Support/QLogg} on macOS.
 *
 * @param mode how the directory was chosen
 * @param directory the directory; a per-user directory may not exist yet
 */
public record SettingsLocation(SettingsMode mode, Path directory) {
  /** The portable-mode marker, which is also the settings file. */
  public static final String MARKER = "qlogg.conf";

  /**
   * Resolves the location from this process's properties and environment.
   *
   * @param explicit the validated {@code --settings} directory, or null
   * @return the location
   */
  public static SettingsLocation fromSystem(Path explicit) {
    return resolve(
        explicit,
        System.getProperty("qlogg.home"),
        OsTraits.current(),
        System.getenv(),
        System.getProperty("user.home"));
  }

  /**
   * Resolves the location.
   *
   * @param explicit the validated {@code --settings} directory, or null
   * @param home the {@code qlogg.home} property, or null outside a packaged run
   * @param os the system
   * @param env the process environment
   * @param userHome the {@code user.home} property
   * @return the location
   */
  public static SettingsLocation resolve(
      Path explicit, String home, OsTraits os, Map<String, String> env, String userHome) {
    if (explicit != null) {
      return new SettingsLocation(SettingsMode.EXPLICIT, explicit);
    }
    Path installed = EnvPaths.absolute(home);
    if (installed != null && Files.isRegularFile(installed.resolve(MARKER))) {
      return new SettingsLocation(SettingsMode.PORTABLE, installed);
    }
    return new SettingsLocation(SettingsMode.PER_USER, os.userSettings(env, Path.of(userHome)));
  }
}
