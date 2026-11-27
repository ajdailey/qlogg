package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.Path;
import java.util.Map;

/**
 * What differs between the operating systems QLogg runs on: a value per question a caller asks, and
 * the system's implementation of each seam whose behavior differs. {@link #current()} is chosen
 * once from {@code os.name}; no other class in core, app or the launcher reads that property. Tests
 * take another system's answers from {@link #of(String)}.
 */
public interface OsTraits {
  /**
   * Returns this process's traits.
   *
   * @return the traits for this JVM's {@code os.name}
   */
  static OsTraits current() {
    return Current.TRAITS;
  }

  /**
   * Returns a system's traits: Windows for a name starting {@code Windows}, macOS for one starting
   * {@code Mac}, and Linux for any other name, null included.
   *
   * @param osName an {@code os.name} value, or null
   * @return the traits
   */
  static OsTraits of(String osName) {
    String name = osName == null ? "" : osName;
    if (name.startsWith("Windows")) {
      return WindowsTraits.INSTANCE;
    }
    if (name.startsWith("Mac")) {
      return MacTraits.INSTANCE;
    }
    return LinuxTraits.INSTANCE;
  }

  /**
   * Returns this JVM's OS name, version and architecture, space-separated, as About shows them.
   *
   * @return the line
   */
  static String description() {
    return System.getProperty("os.name")
        + " "
        + System.getProperty("os.version")
        + " "
        + System.getProperty("os.arch");
  }

  /**
   * Returns the system's device path forms.
   *
   * @return the forms
   */
  DeviceNames deviceNames();

  /**
   * Returns how the launcher keeps SWT and browser files in the settings directory.
   *
   * @return the confinement
   */
  FileConfinement confinement();

  /**
   * Returns the foreground rights a launch passes to the running instance before a handoff.
   *
   * @return {@link WindowsForegroundRights} on Windows, else {@link NoForegroundRights}
   */
  ForegroundRights foregroundRights();

  /**
   * Returns the per-user settings directory: {@code %APPDATA%\QLogg} on Windows, {@code
   * ~/Library/Application Support/QLogg} on macOS, {@code $XDG_CONFIG_HOME/qlogg} on Linux. A
   * variable that is unset, empty or not an absolute path takes its default under the home
   * directory.
   *
   * @param env the process environment
   * @param userHome the user's home directory
   * @return the directory, which need not exist
   */
  Path userSettings(Map<String, String> env, Path userHome);

  /**
   * Returns the launcher's directory, whose {@code portable.txt} or {@code qlogg.conf} also selects
   * portable mode when the installation directory has neither.
   *
   * @param home the installation directory, the parent of {@code lib/}
   * @return {@code <home>/bin}, beside the {@code bin/qlogg} script, on Linux; null elsewhere,
   *     where the launcher sits in the installation directory
   */
  Path launcherDirectory(Path home);

  /**
   * Reports whether the default key bindings are the macOS set.
   *
   * @return true on macOS
   */
  boolean macKeymap();

  /**
   * Reports whether the system can take {@code Ctrl+Shift+0} before QLogg sees it: true on Windows,
   * where it is an input-language hotkey.
   *
   * @return true on Windows
   */
  boolean takesCtrlShiftZero();

  /**
   * Reports whether {@code follow.keepClosed} defaults to on: true on Windows, where a file held
   * open refuses a writer's rename over it.
   *
   * @return true on Windows
   */
  boolean keepFileClosedByDefault();

  /**
   * Reports whether {@code monitor.watchService} defaults to on: false on macOS, where the JDK's
   * {@code WatchService} polls the directory every 2 s and adds nothing to the monitor's own poll.
   *
   * @return false on macOS
   */
  boolean watchServiceByDefault();

  /**
   * Reports whether opening a file with its default application runs it: on Windows an extension in
   * {@link RunnableTypes#WINDOWS} or {@code PATHEXT}, whatever the attributes, since every readable
   * file is executable there; on macOS an extension in {@link RunnableTypes#MAC} or the execute
   * bit; on Linux the execute bit. Reads the file's attributes.
   *
   * @param file the file
   * @return true when the file would run
   */
  boolean runsInsteadOfOpening(Path file);
}
