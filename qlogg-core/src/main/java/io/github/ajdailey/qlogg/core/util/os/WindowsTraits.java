package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.Path;
import java.util.Map;

/** Windows. */
final class WindowsTraits implements OsTraits {
  static final WindowsTraits INSTANCE = new WindowsTraits();

  private WindowsTraits() {}

  @Override
  public DeviceNames deviceNames() {
    return new WindowsDeviceNames();
  }

  @Override
  public FileConfinement confinement() {
    return new WindowsFileConfinement();
  }

  @Override
  public ForegroundRights foregroundRights() {
    return WindowsForegroundRights.INSTANCE;
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
  public Path launcherDirectory(Path home) {
    return null;
  }

  @Override
  public boolean macKeymap() {
    return false;
  }

  @Override
  public boolean takesCtrlShiftZero() {
    return true;
  }

  @Override
  public boolean keepFileClosedByDefault() {
    return true;
  }

  @Override
  public boolean watchServiceByDefault() {
    return true;
  }

  @Override
  public boolean fileNamesIgnoreCase() {
    return true;
  }

  @Override
  public boolean runsInsteadOfOpening(Path file) {
    Path name = file.getFileName();
    return name != null && RunnableTypes.windows(name.toString(), System.getenv("PATHEXT"));
  }
}
