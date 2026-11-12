package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.Path;
import java.util.Map;

/**
 * What differs between the operating systems QLogg runs on, one method per question a caller asks.
 * {@link #current()} is chosen once from {@code os.name}; no other class in core, app or the
 * launcher reads that property. Tests take another system's answers from {@link #of(String)}.
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
   * Reports whether a reserved device name such as {@code CON} or a {@code \\.\} prefix opens a
   * device rather than a file.
   *
   * @return true on Windows
   */
  boolean opensDeviceNames();

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
   * Reports whether explicit and portable mode keep QLogg's files out of the user's home: GTK SWT
   * writes under {@code user.home} and WebKitGTK under the XDG directories, so {@code user.home}
   * moves to the settings directory and the user guide opens outside the process.
   *
   * @return true on Linux
   */
  boolean confinesToSettings();

  /**
   * Reports whether the embedded browser is WebView2, which keeps a user data folder.
   *
   * @return true on Windows
   */
  boolean webView2();

  /**
   * Reports whether the default key bindings are the macOS set.
   *
   * @return true on macOS
   */
  boolean macKeymap();
}
