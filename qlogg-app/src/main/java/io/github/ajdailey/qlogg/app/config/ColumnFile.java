package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.model.ColumnLayout;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@link ColumnSettings} as {@code columns.<id>.<n>.name}, {@code .width} and
 * {@code .hidden} ({@code true} or {@code false}, absent for the format's default), with {@code n}
 * from 0 for the most recently shown, and {@code columns.<id>.frozen}, for each {@link
 * ColumnFormat} id. Entries are read from {@code n = 0} up to the first missing name, at most
 * {@link ColumnSettings#MAX_NAMES}. A name over {@link ColumnSchema#MAX_NAME_CHARS} chars or seen
 * before is skipped; a width outside {@link ColumnLayout#MIN_WIDTH} to {@link
 * ColumnSettings#MAX_WIDTH}, or a frozen count outside 0 to {@link ColumnSchema#MAX_FIELDS}, reads
 * as none with one WARN naming the key.
 */
final class ColumnFile {
  static final String KEY = "columns.";

  private static final Logger LOG = LoggerFactory.getLogger(ColumnFile.class);

  private ColumnFile() {}

  /**
   * Reads the layouts.
   *
   * @param properties the file's properties
   * @return the settings; formats with nothing stored are left out
   */
  static ColumnSettings load(Properties properties) {
    Map<String, ColumnSettings.Format> formats = new LinkedHashMap<String, ColumnSettings.Format>();
    for (ColumnFormat format : ColumnFormat.values()) {
      String prefix = KEY + format.id() + ".";
      List<ColumnSettings.Entry> entries = new ArrayList<ColumnSettings.Entry>();
      Set<String> names = new HashSet<String>();
      for (int n = 0; n < ColumnSettings.MAX_NAMES; n++) {
        String name = properties.getProperty(prefix + n + ".name");
        if (name == null) {
          break;
        }
        if (name.length() > ColumnSchema.MAX_NAME_CHARS || !names.add(name)) {
          continue;
        }
        int width =
            (int)
                number(
                    properties,
                    prefix + n + ".width",
                    ColumnLayout.MIN_WIDTH,
                    ColumnSettings.MAX_WIDTH);
        ColumnSettings.Entry entry =
            new ColumnSettings.Entry(
                name, Math.max(0, width), visibility(properties, prefix + n + ".hidden"));
        if (entry.width() > 0 || entry.visibility() != ColumnSettings.Visibility.DEFAULT) {
          entries.add(entry);
        }
      }
      int frozen = (int) number(properties, prefix + "frozen", 0, ColumnSchema.MAX_FIELDS);
      ColumnSettings.Format stored = new ColumnSettings.Format(entries, Math.max(0, frozen));
      if (!entries.isEmpty() || stored.frozen() > 0) {
        formats.put(format.id(), stored);
      }
    }
    return new ColumnSettings(formats);
  }

  /**
   * Writes the layouts.
   *
   * @param settings the layouts
   * @param properties receives the keys
   */
  static void store(ColumnSettings settings, Properties properties) {
    for (Map.Entry<String, ColumnSettings.Format> format : settings.formats().entrySet()) {
      String prefix = KEY + format.getKey() + ".";
      List<ColumnSettings.Entry> entries = format.getValue().entries();
      for (int n = 0; n < entries.size(); n++) {
        ColumnSettings.Entry entry = entries.get(n);
        properties.setProperty(prefix + n + ".name", entry.name());
        if (entry.width() > 0) {
          properties.setProperty(prefix + n + ".width", Integer.toString(entry.width()));
        }
        if (entry.visibility() != ColumnSettings.Visibility.DEFAULT) {
          properties.setProperty(
              prefix + n + ".hidden",
              Boolean.toString(entry.visibility() == ColumnSettings.Visibility.HIDDEN));
        }
      }
      if (format.getValue().frozen() > 0) {
        properties.setProperty(prefix + "frozen", Integer.toString(format.getValue().frozen()));
      }
    }
  }

  /** Reads {@code true} as hidden, {@code false} as shown, absent as the format's default. */
  private static ColumnSettings.Visibility visibility(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null) {
      return ColumnSettings.Visibility.DEFAULT;
    }
    String text = value.strip();
    if (text.equals("true") || text.equals("false")) {
      return text.equals("true")
          ? ColumnSettings.Visibility.HIDDEN
          : ColumnSettings.Visibility.SHOWN;
    }
    LOG.warn("Ignoring an invalid {}", LogText.oneLine(key));
    return ColumnSettings.Visibility.DEFAULT;
  }

  /** Returns a whole number in range, -1 when absent, or -1 with one WARN when invalid. */
  private static long number(Properties properties, String key, long min, long max) {
    String value = properties.getProperty(key);
    if (value == null) {
      return -1;
    }
    try {
      long parsed = Long.parseLong(value.strip());
      if (parsed >= min && parsed <= max) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // Reported below.
    }
    LOG.warn("Ignoring an invalid {}", LogText.oneLine(key));
    return -1;
  }
}
