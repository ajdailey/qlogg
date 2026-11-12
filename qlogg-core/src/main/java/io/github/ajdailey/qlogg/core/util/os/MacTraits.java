package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.Path;
import java.util.Map;

/** macOS. */
final class MacTraits implements OsTraits {
  static final MacTraits INSTANCE = new MacTraits();

  private MacTraits() {}

  @Override
  public boolean opensDeviceNames() {
    return false;
  }

  @Override
  public Path userSettings(Map<String, String> env, Path userHome) {
    return userHome.resolve("Library").resolve("Application Support").resolve("QLogg");
  }

  @Override
  public boolean confinesToSettings() {
    return false;
  }

  @Override
  public boolean webView2() {
    return false;
  }

  @Override
  public boolean macKeymap() {
    return true;
  }
}
