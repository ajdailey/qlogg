package io.github.ajdailey.qlogg.core.util.os;

import java.util.regex.Pattern;

/** Windows reserved device names and namespace prefixes. */
final class WindowsDeviceNames implements DeviceNames {
  private static final Pattern RESERVED =
      Pattern.compile(
          "(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]|CONIN\\$|CONOUT\\$)(\\..*)?",
          Pattern.CASE_INSENSITIVE);

  @Override
  public boolean opensDevice(String path) {
    String text = path.replace('/', '\\');
    if (text.startsWith("\\\\.\\") || text.startsWith("\\\\?\\")) {
      return true;
    }
    for (String part : text.split("[\\\\:]")) {
      if (RESERVED.matcher(part.strip()).matches()) {
        return true;
      }
    }
    return false;
  }
}
