package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** macOS. */
final class MacTraits implements OsTraits {
  static final MacTraits INSTANCE = new MacTraits();

  private MacTraits() {}

  @Override
  public DeviceNames deviceNames() {
    return new FileNames();
  }

  @Override
  public FileConfinement confinement() {
    return new MacFileConfinement();
  }

  @Override
  public Path userSettings(Map<String, String> env, Path userHome) {
    return userHome.resolve("Library").resolve("Application Support").resolve("QLogg");
  }

  @Override
  public Path launcherDirectory(Path home) {
    return null;
  }

  @Override
  public boolean macKeymap() {
    return true;
  }

  @Override
  public boolean takesCtrlShiftZero() {
    return false;
  }

  @Override
  public boolean keepFileClosedByDefault() {
    return false;
  }

  @Override
  public boolean watchServiceByDefault() {
    return false;
  }

  @Override
  public boolean runsInsteadOfOpening(Path file) {
    Path name = file.getFileName();
    return (name != null && RunnableTypes.mac(name.toString())) || Files.isExecutable(file);
  }
}
