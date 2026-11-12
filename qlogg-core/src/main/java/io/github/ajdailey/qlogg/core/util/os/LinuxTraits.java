package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.Path;
import java.util.Map;

/** Linux, and any system {@link OsTraits#of} does not name. */
final class LinuxTraits implements OsTraits {
  static final LinuxTraits INSTANCE = new LinuxTraits();

  private LinuxTraits() {}

  @Override
  public boolean opensDeviceNames() {
    return false;
  }

  @Override
  public Path userSettings(Map<String, String> env, Path userHome) {
    Path xdg = EnvPaths.absolute(env.get("XDG_CONFIG_HOME"));
    if (xdg == null) {
      xdg = userHome.resolve(".config");
    }
    return xdg.resolve("qlogg");
  }

  @Override
  public boolean confinesToSettings() {
    return true;
  }

  @Override
  public boolean webView2() {
    return false;
  }

  @Override
  public boolean macKeymap() {
    return false;
  }
}
