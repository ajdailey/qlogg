package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@link TimeSettings} as {@code qlogg.conf} properties: {@code time.zone}, {@code
 * time.displayZone} and {@code time.format.N.name}, {@code .locate} and {@code .pattern}, read from
 * 0 until the first gap. A zone that does not parse reads as the system zone, and a format that
 * does not compile or repeats a name is dropped, each with one WARN naming the key. Values are
 * never logged.
 */
final class TimeFile {
  static final String ZONE_KEY = "time.zone";
  static final String DISPLAY_ZONE_KEY = "time.displayZone";
  static final String FORMAT_KEY = "time.format.";

  private static final Logger LOG = LoggerFactory.getLogger(TimeFile.class);

  private TimeFile() {}

  /**
   * Reads the timestamp settings; absent keys keep their defaults.
   *
   * @param properties the file's properties
   * @return the settings
   */
  static TimeSettings load(Properties properties) {
    List<UserFormat> formats = new ArrayList<UserFormat>();
    for (int i = 0; formats.size() < UserFormat.MAX_FORMATS; i++) {
      String key = FORMAT_KEY + i;
      String name = properties.getProperty(key + ".name");
      if (name == null) {
        break;
      }
      UserFormat format = null;
      try {
        format =
            new UserFormat(
                name,
                properties.getProperty(key + ".locate", ""),
                properties.getProperty(key + ".pattern", ""));
      } catch (IllegalArgumentException e) {
        // Reported below.
      }
      if (format != null && !named(formats, format.name())) {
        formats.add(format);
      } else {
        LOG.warn("Ignoring an invalid {}", LogText.oneLine(key));
      }
    }
    return new TimeSettings(
        zone(properties, ZONE_KEY), zone(properties, DISPLAY_ZONE_KEY), formats);
  }

  /**
   * Writes the timestamp settings; a system zone writes no key.
   *
   * @param time the settings
   * @param properties receives the keys
   */
  static void store(TimeSettings time, Properties properties) {
    if (time.zone() != null) {
      properties.setProperty(ZONE_KEY, time.zone().getId());
    }
    if (time.displayZone() != null) {
      properties.setProperty(DISPLAY_ZONE_KEY, time.displayZone().getId());
    }
    List<UserFormat> formats = time.formats();
    for (int i = 0; i < formats.size(); i++) {
      UserFormat format = formats.get(i);
      properties.setProperty(FORMAT_KEY + i + ".name", format.name());
      properties.setProperty(FORMAT_KEY + i + ".locate", format.locate());
      properties.setProperty(FORMAT_KEY + i + ".pattern", format.pattern());
    }
  }

  private static boolean named(List<UserFormat> formats, String name) {
    for (UserFormat format : formats) {
      if (format.name().equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static ZoneId zone(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return ZoneId.of(value.strip());
    } catch (DateTimeException e) {
      LOG.warn("Ignoring an invalid {}", LogText.oneLine(key));
      return null;
    }
  }
}
