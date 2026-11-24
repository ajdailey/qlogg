package io.github.ajdailey.qlogg.core.util.os;

/** Path forms that open a device rather than a file, one implementation per system. */
public interface DeviceNames {
  /**
   * Reports whether a path opens a device. On Windows that is a {@code \\.\} or {@code \\?\}
   * prefix, or a component that is a reserved device name such as {@code CON} or {@code COM1}, with
   * or without an extension; elsewhere no form does.
   *
   * @param path the path text
   * @return true when the system would open a device rather than a file
   */
  boolean opensDevice(String path);
}
