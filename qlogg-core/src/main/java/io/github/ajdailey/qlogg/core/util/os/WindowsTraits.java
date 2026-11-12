package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.Path;
import java.util.Map;

/** Windows. */
final class WindowsTraits implements OsTraits {
  static final WindowsTraits INSTANCE = new WindowsTraits();

  private WindowsTraits() {}

  @Override
  public boolean opensDeviceNames() {
    return true;
  }

  @Override
  public Path userSettings(Map<String, String> env, Path userHome) {
    Path appData = EnvPaths.absolute(env.get("APPDATA"));
    if (appData == null) {
      appData = userHome.resolve("AppData").resolve("Roaming");
    }
    return appData.resolve("QLogg");
  }

  @Override
  public boolean confinesToSettings() {
    return false;
  }

  @Override
  public boolean webView2() {
    return true;
  }

  @Override
  public boolean macKeymap() {
    return false;
  }
}
