package io.github.ajdailey.qlogg.bootstrap;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The directory SWT's natives load from, in order of precedence:
 *
 * <ol>
 *   <li>a bundle's {@code <qlogg.home>/lib/swt}, verified and never written;
 *   <li>{@code <settings>/cache/swt/<swt version>-<target>} for {@code --settings <dir>}, or with
 *       {@code <qlogg.home>} as the settings directory when {@code <qlogg.home>/qlogg.conf} exists
 *       (portable mode);
 *   <li>the per-user cache: {@code ${XDG_CACHE_HOME:-~/.cache}/qlogg/swt} on Linux, {@code
 *       %LOCALAPPDATA%\QLogg\cache\swt} on Windows, {@code
 *       ~/Library/Caches/io.github.ajdailey.qlogg/swt} on macOS, each with a {@code <swt
 *       version>-<target>} child.
 * </ol>
 *
 * @param dir the natives directory
 * @param writable false for a bundle directory
 */
record NativesLocation(Path dir, boolean writable) {
  /**
   * Resolves the location.
   *
   * @param home {@code qlogg.home}
   * @param settings the validated {@code --settings} directory, or null
   * @param target the running target
   * @param swtVersion the SWT version from the index
   * @param env the process environment
   * @param userHome the {@code user.home} property
   * @return the location
   */
  static NativesLocation resolve(
      Path home,
      Path settings,
      Target target,
      String swtVersion,
      Map<String, String> env,
      String userHome) {
    Path bundled = home.resolve("lib").resolve("swt");
    if (Files.isDirectory(bundled)) {
      return new NativesLocation(bundled, false);
    }
    String leaf = swtVersion + "-" + target.id();
    if (settings != null) {
      return new NativesLocation(settings.resolve("cache").resolve("swt").resolve(leaf), true);
    }
    if (Files.isRegularFile(home.resolve("qlogg.conf"))) {
      return new NativesLocation(home.resolve("cache").resolve("swt").resolve(leaf), true);
    }
    return new NativesLocation(userCache(target, env, userHome).resolve(leaf), true);
  }

  private static Path userCache(Target target, Map<String, String> env, String userHome) {
    Path home = Path.of(userHome);
    if (target.isWindows()) {
      Path local = absolute(env.get("LOCALAPPDATA"));
      if (local == null) {
        local = home.resolve("AppData").resolve("Local");
      }
      return local.resolve("QLogg").resolve("cache").resolve("swt");
    }
    if (target.isMac()) {
      return home.resolve("Library")
          .resolve("Caches")
          .resolve("io.github.ajdailey.qlogg")
          .resolve("swt");
    }
    Path xdg = absolute(env.get("XDG_CACHE_HOME"));
    if (xdg == null) {
      xdg = home.resolve(".cache");
    }
    return xdg.resolve("qlogg").resolve("swt");
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
