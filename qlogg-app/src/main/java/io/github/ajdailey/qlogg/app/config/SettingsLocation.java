package io.github.ajdailey.qlogg.app.config;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
        System.getProperty("os.name"),
        System.getenv(),
        System.getProperty("user.home"));
  }

  /**
   * Resolves the location.
   *
   * @param explicit the validated {@code --settings} directory, or null
   * @param home the {@code qlogg.home} property, or null outside a packaged run
   * @param osName the {@code os.name} property
   * @param env the process environment
   * @param userHome the {@code user.home} property
   * @return the location
   */
  public static SettingsLocation resolve(
      Path explicit, String home, String osName, Map<String, String> env, String userHome) {
    if (explicit != null) {
      return new SettingsLocation(SettingsMode.EXPLICIT, explicit);
    }
    Path installed = absolute(home);
    if (installed != null && Files.isRegularFile(installed.resolve(MARKER))) {
      return new SettingsLocation(SettingsMode.PORTABLE, installed);
    }
    Path user = Path.of(userHome);
    Path dir;
    if (osName.startsWith("Windows")) {
      Path appData = absolute(env.get("APPDATA"));
      if (appData == null) {
        appData = user.resolve("AppData").resolve("Roaming");
      }
      dir = appData.resolve("QLogg");
    } else if (osName.startsWith("Mac")) {
      dir = user.resolve("Library").resolve("Application Support").resolve("QLogg");
    } else {
      Path xdg = absolute(env.get("XDG_CONFIG_HOME"));
      if (xdg == null) {
        xdg = user.resolve(".config");
      }
      dir = xdg.resolve("qlogg");
    }
    return new SettingsLocation(SettingsMode.PER_USER, dir);
  }

  /** Returns the value as a path when it is a non-empty absolute path, else null. */
  private static Path absolute(String value) {
    if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) {
      return null;
    }
    try {
      Path path = Path.of(value);
      return path.isAbsolute() ? path.normalize() : null;
    } catch (InvalidPathException e) {
      return null;
    }
  }
}
