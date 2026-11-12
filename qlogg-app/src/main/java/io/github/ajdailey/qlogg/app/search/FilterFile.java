package io.github.ajdailey.qlogg.app.search;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.io.AtomicFiles;
import io.github.ajdailey.qlogg.core.io.PropertiesFiles;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Predefined filters as Java properties: {@code filter.N.name}, {@code filter.N.pattern} and {@code
 * filter.N.regex} for N from 0 with no gap, in order. {@code qlogg.conf} holds them under the same
 * keys. An export file adds {@code version=1}; {@link #read} accepts a file of at most {@link
 * #MAX_BYTES} with at most {@link #MAX_FILTERS} filters. Both file methods block, so they run on a
 * worker, and take the user's path through {@link SafePaths#logFile(Path)}.
 */
public final class FilterFile {
  /** Largest file read. */
  public static final int MAX_BYTES = 1 << 20;

  /** Most filters kept. */
  public static final int MAX_FILTERS = 1000;

  static final String PREFIX = "filter.";
  static final String NAME = ".name";
  static final String PATTERN = ".pattern";
  static final String REGEX = ".regex";
  static final String VERSION_KEY = "version";
  static final String VERSION = "1";

  /** A filter file that does not parse. */
  public static final class FormatException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong, from {@link UiText}, without file content
     */
    public FormatException(String message) {
      super(message);
    }
  }

  private FilterFile() {}

  /**
   * Reads the filters a file exports.
   *
   * @param path the user's file
   * @return the filters, unmodifiable
   * @throws IOException when the path is rejected or unreadable; {@link FormatException} when the
   *     file is too large, does not parse, names another version or holds a malformed filter
   */
  public static List<PredefinedFilter> read(Path path) throws IOException {
    Path file = SafePaths.logFile(path);
    if (Files.size(file) > MAX_BYTES) {
      throw new FormatException(UiText.FILTER_FILE_TOO_LARGE.text());
    }
    Properties properties;
    try {
      properties = PropertiesFiles.read(file, MAX_BYTES);
    } catch (IllegalArgumentException e) {
      throw new FormatException(UiText.FILTER_FILE_NOT_PROPERTIES.text());
    }
    String version = properties.getProperty(VERSION_KEY);
    if (version == null || !VERSION.equals(version.strip())) {
      throw new FormatException(UiText.FILTER_FILE_NOT_EXPORT.text());
    }
    List<PredefinedFilter> filters = new ArrayList<PredefinedFilter>();
    for (int i = 0; ; i++) {
      String name = properties.getProperty(PREFIX + i + NAME);
      if (name == null) {
        break;
      }
      PredefinedFilter filter = filter(properties, i);
      if (filter == null) {
        throw new FormatException(UiText.FILTER_FILE_INCOMPLETE.format(i + 1));
      }
      if (filters.size() == MAX_FILTERS) {
        throw new FormatException(UiText.FILTER_FILE_TOO_MANY.format(MAX_FILTERS));
      }
      filters.add(filter);
    }
    return Collections.unmodifiableList(filters);
  }

  /**
   * Writes the filters to a file through {@link AtomicFiles#writeUserFile}, replacing it.
   *
   * @param path the user's file
   * @param filters the filters
   * @throws IOException when the path is rejected or the write fails
   */
  public static void write(Path path, List<PredefinedFilter> filters) throws IOException {
    Path file = SafePaths.logFile(path);
    Properties properties = new Properties();
    properties.setProperty(VERSION_KEY, VERSION);
    store(filters, properties);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    properties.store(out, null);
    StringBuilder kept = new StringBuilder();
    for (String line : out.toString(StandardCharsets.ISO_8859_1).split("\\R")) {
      if (!line.startsWith("#")) {
        kept.append(line).append('\n');
      }
    }
    AtomicFiles.writeUserFile(file, kept.toString().getBytes(StandardCharsets.ISO_8859_1));
  }

  /**
   * Puts filters into properties under the indexed keys.
   *
   * @param filters the filters
   * @param properties receives the keys
   */
  public static void store(List<PredefinedFilter> filters, Properties properties) {
    for (int i = 0; i < filters.size(); i++) {
      PredefinedFilter filter = filters.get(i);
      properties.setProperty(PREFIX + i + NAME, filter.name());
      properties.setProperty(PREFIX + i + PATTERN, filter.pattern());
      properties.setProperty(
          PREFIX + i + REGEX, Boolean.toString(filter.mode() == MatchMode.REGEX));
    }
  }

  /**
   * Reads filters from properties, stopping at the first index without a name and skipping an
   * incomplete entry.
   *
   * @param properties the properties
   * @return the filters, unmodifiable; the count of skipped entries is not reported
   */
  public static List<PredefinedFilter> load(Properties properties) {
    List<PredefinedFilter> filters = new ArrayList<PredefinedFilter>();
    for (int i = 0; filters.size() < MAX_FILTERS; i++) {
      if (properties.getProperty(PREFIX + i + NAME) == null) {
        break;
      }
      PredefinedFilter filter = filter(properties, i);
      if (filter != null) {
        filters.add(filter);
      }
    }
    return Collections.unmodifiableList(filters);
  }

  private static PredefinedFilter filter(Properties properties, int i) {
    String name = properties.getProperty(PREFIX + i + NAME);
    String pattern = properties.getProperty(PREFIX + i + PATTERN);
    String regex = properties.getProperty(PREFIX + i + REGEX, "true").strip();
    if (name == null || pattern == null || name.isBlank() || pattern.isEmpty()) {
      return null;
    }
    if (!regex.equals("true") && !regex.equals("false")) {
      return null;
    }
    return new PredefinedFilter(
        name.strip(), pattern, regex.equals("true") ? MatchMode.REGEX : MatchMode.LITERAL);
  }
}
