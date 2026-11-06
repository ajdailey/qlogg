package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.app.highlight.HighlightFile;
import io.github.ajdailey.qlogg.app.search.FilterFile;
import io.github.ajdailey.qlogg.app.search.SearchHistory;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.core.io.AtomicFiles;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.util.Streams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and saves {@link Preferences} in {@code qlogg.conf} in the settings directory. Both block,
 * so they run off the UI thread; a lock serializes them. Thread-safe.
 *
 * <p>The file is Java properties with {@code version=1}; a file without {@code version} is read as
 * version 1, so an empty portable marker is valid. A file that cannot be read, is over {@link
 * #MAX_BYTES}, does not parse or has another version is malformed: defaults apply, one WARN names
 * it, and the first save copies it to {@code qlogg.conf.invalid} before replacing it. A value that
 * does not parse falls back to its default. Saves write atomically ({@link AtomicFiles}). A
 * read-only settings directory disables saving with one WARN. Only file names and keys are logged,
 * never values.
 */
public final class ConfigStore {
  /** Largest file read; a longer one is malformed. */
  public static final int MAX_BYTES = 1 << 20;

  /** The format version written. */
  public static final String VERSION = "1";

  static final String VERSION_KEY = "version";
  static final String THEME_KEY = "ui.theme";
  static final String HISTORY_SIZE_KEY = "search.historySize";
  static final String MAX_RESULTS_KEY = "search.maxResults";
  static final String RUN_ON_ADD_KEY = "search.runOnAdd";
  static final String HISTORY_KEY = "search.history.";
  static final String QUICK_FIND_INCREMENTAL_KEY = "quickFind.incremental";
  static final String QUICK_FIND_REGEX_KEY = "quickFind.regex";
  static final String SPLIT_KEY = "view.splitPercent";
  static final String OVERVIEW_KEY = "view.overview";
  static final String FOLLOW_ON_LOAD_KEY = "follow.onLoad";
  static final String FOLLOW_ON_OVERSCROLL_KEY = "follow.onOverscroll";
  // Keep file closed.
  static final String FOLLOW_KEEP_CLOSED_KEY = "follow.keepClosed";
  static final String INVALID_SUFFIX = ".invalid";

  private static final Logger LOG = LoggerFactory.getLogger(ConfigStore.class);

  private final SettingsLocation location;
  private final ReentrantLock lock = new ReentrantLock();
  private boolean keepInvalid;
  private boolean saveDisabled;
  // Session and remembered positions.
  private final SessionStore sessions;
  private final PositionStore positions;

  /**
   * Creates the store; nothing is read until {@link #load}.
   *
   * @param location the settings directory
   */
  public ConfigStore(SettingsLocation location) {
    this.location = location;
    this.sessions = new SessionStore(location);
    this.positions = new PositionStore(location);
  }

  // Session and remembered positions.

  /**
   * Returns the store of {@code qlogg_session.conf} in the same directory.
   *
   * @return the session store
   */
  public SessionStore sessions() {
    return sessions;
  }

  /**
   * Returns the remembered positions in the same directory, empty until {@link PositionStore#load}.
   *
   * @return the position store
   */
  public PositionStore positions() {
    return positions;
  }

  /**
   * Returns the settings directory.
   *
   * @return the location
   */
  public SettingsLocation location() {
    return location;
  }

  /**
   * Reports whether saving is disabled because the directory is read-only.
   *
   * @return true after a load or save found the directory read-only
   */
  public boolean saveDisabled() {
    lock.lock();
    try {
      return saveDisabled;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Reads the settings. Blocking.
   *
   * @return the settings; defaults when the file is missing or malformed
   */
  public Preferences load() {
    lock.lock();
    try {
      Path dir = location.directory();
      if (Files.isDirectory(dir) && !Files.isWritable(dir)) {
        disableSaving();
      }
      Path file = SafePaths.child(dir, SettingsLocation.MARKER);
      byte[] bytes;
      try (InputStream in = Files.newInputStream(file)) {
        bytes = Streams.readLimited(in, MAX_BYTES);
      } catch (NoSuchFileException e) {
        return Preferences.DEFAULTS;
      }
      Properties properties = parse(bytes);
      if (properties == null) {
        malformed(file);
        return Preferences.DEFAULTS;
      }
      return read(properties, file);
    } catch (IOException e) {
      LOG.warn(
          "Cannot read settings in {}: {}",
          LogText.oneLine(location.directory()),
          LogText.oneLine(e.getMessage()));
      keepInvalid = true;
      return Preferences.DEFAULTS;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Writes the settings atomically, creating a per-user directory first. Blocking.
   *
   * @param preferences the settings
   * @return false when saving is disabled or the write failed, which is logged
   */
  public boolean save(Preferences preferences) {
    lock.lock();
    try {
      if (saveDisabled) {
        return false;
      }
      Path dir = location.directory();
      if (location.mode() == SettingsMode.PER_USER && !Files.isDirectory(dir)) {
        SafePaths.settingsDirectory(dir);
      }
      Path file = SafePaths.child(dir, SettingsLocation.MARKER);
      if (keepInvalid && Files.exists(file)) {
        Path invalid = SafePaths.child(dir, SettingsLocation.MARKER + INVALID_SUFFIX);
        Files.copy(file, invalid, StandardCopyOption.REPLACE_EXISTING);
      }
      keepInvalid = false;
      AtomicFiles.write(file, format(preferences));
      return true;
    } catch (AccessDeniedException e) {
      disableSaving();
      return false;
    } catch (IOException e) {
      if (Files.isDirectory(location.directory()) && !Files.isWritable(location.directory())) {
        disableSaving();
      } else {
        LOG.warn(
            "Cannot save settings in {}: {}",
            LogText.oneLine(location.directory()),
            LogText.oneLine(e.getMessage()));
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  /** Returns the properties, or null when the bytes do not parse or name another version. */
  private static Properties parse(byte[] bytes) throws IOException {
    Properties properties = new Properties();
    try {
      properties.load(new ByteArrayInputStream(bytes));
    } catch (IllegalArgumentException e) {
      return null;
    }
    String version = properties.getProperty(VERSION_KEY);
    if (version != null && !VERSION.equals(version.strip())) {
      return null;
    }
    return properties;
  }

  private static Preferences read(Properties properties, Path file) {
    Preferences preferences = Preferences.DEFAULTS;
    String theme = properties.getProperty(THEME_KEY);
    if (theme != null) {
      ThemeMode mode = ThemeMode.parse(theme);
      if (mode == null) {
        invalid(THEME_KEY, file);
      } else {
        preferences = preferences.withTheme(mode);
      }
    }
    long size = number(properties, HISTORY_SIZE_KEY, 1, SearchHistory.MAX_SIZE, file);
    if (size > 0) {
      preferences = preferences.withHistorySize((int) size);
    }
    long limit = number(properties, MAX_RESULTS_KEY, 0, Long.MAX_VALUE, file);
    if (limit >= 0) {
      preferences = preferences.withMaxResults(limit);
    }
    long split = number(properties, SPLIT_KEY, Preferences.MIN_SPLIT, Preferences.MAX_SPLIT, file);
    if (split > 0) {
      preferences = preferences.withSplitPercent((int) split);
    }
    preferences =
        preferences
            .withRunSearchOnAdd(
                flag(properties, RUN_ON_ADD_KEY, preferences.runSearchOnAdd(), file))
            .withQuickFind(
                flag(
                    properties,
                    QUICK_FIND_INCREMENTAL_KEY,
                    preferences.quickFindIncremental(),
                    file),
                flag(properties, QUICK_FIND_REGEX_KEY, preferences.quickFindRegex(), file));
    List<String> history = new ArrayList<String>();
    for (int i = 0; i < SearchHistory.MAX_SIZE; i++) {
      String entry = properties.getProperty(HISTORY_KEY + i);
      if (entry == null) {
        break;
      }
      history.add(entry);
    }
    return preferences
        .withOverview(flag(properties, OVERVIEW_KEY, preferences.overview(), file))
        .withFollow(
            new FollowSettings(
                flag(properties, FOLLOW_ON_LOAD_KEY, preferences.follow().onLoad(), file),
                flag(
                    properties,
                    FOLLOW_ON_OVERSCROLL_KEY,
                    preferences.follow().onOverscroll(),
                    file),
                flag(properties, FOLLOW_KEEP_CLOSED_KEY, preferences.follow().keepClosed(), file)))
        .withSearchHistory(history)
        .withFilters(FilterFile.load(properties))
        .withHighlights(HighlightFile.load(properties))
        .withView(ViewFile.load(properties))
        // Options dialog settings, favorites and remembered UI state.
        .withOptions(OptionsFile.loadOptions(properties, file))
        .withState(OptionsFile.loadState(properties, file))
        .withTime(TimeFile.load(properties));
  }

  /** Returns a whole number in range, -1 when absent, or -1 with one WARN when invalid. */
  private static long number(Properties properties, String key, long min, long max, Path file) {
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
    invalid(key, file);
    return -1;
  }

  private static boolean flag(Properties properties, String key, boolean fallback, Path file) {
    String value = properties.getProperty(key);
    if (value == null) {
      return fallback;
    }
    String text = value.strip();
    if (text.equals("true") || text.equals("false")) {
      return text.equals("true");
    }
    invalid(key, file);
    return fallback;
  }

  private static void invalid(String key, Path file) {
    LOG.warn("Ignoring an invalid {} in {}", LogText.oneLine(key), LogText.oneLine(file));
  }

  /** Returns the file content: sorted keys, ISO-8859-1 with escapes, no date comment. */
  static byte[] format(Preferences preferences) throws IOException {
    Properties properties = new Properties();
    properties.setProperty(VERSION_KEY, VERSION);
    properties.setProperty(THEME_KEY, preferences.theme().key());
    properties.setProperty(HISTORY_SIZE_KEY, Integer.toString(preferences.historySize()));
    properties.setProperty(MAX_RESULTS_KEY, Long.toString(preferences.maxResults()));
    properties.setProperty(RUN_ON_ADD_KEY, Boolean.toString(preferences.runSearchOnAdd()));
    properties.setProperty(
        QUICK_FIND_INCREMENTAL_KEY, Boolean.toString(preferences.quickFindIncremental()));
    properties.setProperty(QUICK_FIND_REGEX_KEY, Boolean.toString(preferences.quickFindRegex()));
    properties.setProperty(SPLIT_KEY, Integer.toString(preferences.splitPercent()));
    List<String> history = preferences.searchHistory();
    for (int i = 0; i < history.size(); i++) {
      properties.setProperty(HISTORY_KEY + i, history.get(i));
    }
    properties.setProperty(OVERVIEW_KEY, Boolean.toString(preferences.overview()));
    properties.setProperty(FOLLOW_ON_LOAD_KEY, Boolean.toString(preferences.follow().onLoad()));
    properties.setProperty(
        FOLLOW_ON_OVERSCROLL_KEY, Boolean.toString(preferences.follow().onOverscroll()));
    properties.setProperty(
        FOLLOW_KEEP_CLOSED_KEY, Boolean.toString(preferences.follow().keepClosed()));
    FilterFile.store(preferences.filters(), properties);
    HighlightFile.store(preferences.highlights(), properties);
    ViewFile.store(preferences.view(), properties);
    // Options dialog settings, favorites and remembered UI state.
    OptionsFile.store(preferences.options(), preferences.state(), properties);
    // Timestamps.
    TimeFile.store(preferences.time(), properties);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    properties.store(out, null);
    String text = out.toString(StandardCharsets.ISO_8859_1);
    StringBuilder kept = new StringBuilder();
    for (String line : text.split("\\R")) {
      if (!line.startsWith("#")) {
        kept.append(line).append('\n');
      }
    }
    return kept.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  private void malformed(Path file) {
    keepInvalid = true;
    LOG.warn(
        "Malformed settings file {}; defaults apply and it is kept as {} before the next save",
        LogText.oneLine(file),
        SettingsLocation.MARKER + INVALID_SUFFIX);
  }

  private void disableSaving() {
    if (!saveDisabled) {
      saveDisabled = true;
      LOG.warn(
          "Settings directory {} is read-only; settings are not saved",
          LogText.oneLine(location.directory()));
    }
  }
}
