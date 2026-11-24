package io.github.ajdailey.qlogg.core.util.os;

/** Linux and macOS: every path names a file. */
final class FileNames implements DeviceNames {
  @Override
  public boolean opensDevice(String path) {
    return false;
  }
}
