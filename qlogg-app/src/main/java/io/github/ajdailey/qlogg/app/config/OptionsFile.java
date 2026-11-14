package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.app.command.ShortcutOverrides;
import io.github.ajdailey.qlogg.app.search.SearchHistory;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the {@link Options} and {@link UiState} keys of {@code qlogg.conf}. A value that
 * does not parse falls back to its default with one WARN naming the key. The path keys ({@code
 * recent.N}, {@code closed.N}, {@code favorite.N}, {@code open.directory}, {@code
 * folderSearch.folder}) pass through {@link InstallPaths}.
 */
final class OptionsFile {
  static final String FONT_FAMILY = "font.family";
  static final String FONT_SIZE = "font.size";
  static final String FONT_ZOOM = "font.zoom";
  static final String FONT_BOLD = "font.bold";
  static final String FONT_ANTIALIAS = "font.antialias";
  static final String RECENT_COUNT = "recent.count";
  static final String RECENT = "recent.";
  // Recently Closed.
  static final String CLOSED = "closed.";
  static final String POLL = "monitor.pollMillis";
  static final String WATCH = "monitor.watchService";
  static final String ENCODING = "encoding.default";
  static final String LOAD_LAST = "session.loadLast";
  static final String LOG_FILE = "log.file";
  static final String FAVORITE = "favorite.";
  static final String WINDOW_X = "window.x";
  static final String WINDOW_Y = "window.y";
  static final String WINDOW_WIDTH = "window.width";
  static final String WINDOW_HEIGHT = "window.height";
  static final String WINDOW_MAXIMIZED = "window.maximized";
  static final String WINDOW_SPLIT = "window.split";
  static final String SEARCH_REGEX = "searchOption.regex";
  static final String SEARCH_CASE = "searchOption.caseSensitive";
  static final String SEARCH_WORD = "searchOption.wholeWord";
  static final String SEARCH_INVERSE = "searchOption.inverse";
  static final String SEARCH_COMBINING = "searchOption.combining";
  static final String SEARCH_REFRESH = "searchOption.autoRefresh";
  static final String SEARCH_KEEP = "searchOption.keepResults";
  // Context lines and range filter.
  static final String SEARCH_RANGE = "searchOption.range";
  static final String SEARCH_RANGE_END = "searchOption.rangeEnd";
  static final String SEARCH_BEFORE = "searchOption.contextBefore";
  static final String SEARCH_AFTER = "searchOption.contextAfter";
  static final String LINE_NUMBERS = "view.lineNumbers";
  static final String FILTERED_LINE_NUMBERS = "view.filteredLineNumbers";
  static final String OPEN_DIRECTORY = "open.directory";
  static final String DIALOG = "dialog.";
  // Search in a folder.
  static final String FOLDER_SEARCH_FOLDER = "folderSearch.folder";
  static final String FOLDER_SEARCH_NAMES = "folderSearch.names";
  static final String FOLDER_SEARCH_RECURSIVE = "folderSearch.recursive";
  static final String FOLDER_SEARCH_EXCLUDE = "folderSearch.exclude";
  // Last tab.
  static final String CLOSE_WINDOW_WITH_LAST_TAB = "tabs.closeWindowWithLastTab";
  // Last used tab.
  static final String CLOSE_SHOWS_LAST_USED = "tabs.closeShowsLastUsed";

  /** Largest window coordinate or size accepted, in pixels. */
  static final int MAX_PIXELS = 100_000;

  private static final Logger LOG = LoggerFactory.getLogger(OptionsFile.class);

  private OptionsFile() {}

  /**
   * Reads the options.
   *
   * @param p the file's properties
   * @param file the file, for warnings
   * @param paths resolves the path keys
   * @return the options; defaults for keys that are absent or invalid
   */
  static Options loadOptions(Properties p, Path file, InstallPaths paths) {
    Options d = Options.DEFAULTS;
    FontSettings font = d.font();
    String family = p.getProperty(FONT_FAMILY, "").strip();
    if (family.length() > FontSettings.MAX_FAMILY_CHARS) {
      invalid(FONT_FAMILY, file);
      family = "";
    }
    int size = (int) number(p, FONT_SIZE, FontSettings.MIN_SIZE, FontSettings.MAX_SIZE, 0, file);
    int zoom = (int) number(p, FONT_ZOOM, FontSettings.MIN_SIZE, FontSettings.MAX_SIZE, 0, file);
    font =
        new FontSettings(
            family,
            size,
            zoom,
            flag(p, FONT_BOLD, font.bold(), file),
            flag(p, FONT_ANTIALIAS, font.antialias(), file));
    int recent = (int) number(p, RECENT_COUNT, 1, Options.MAX_RECENT, d.recentCount(), file);
    int poll =
        (int)
            number(
                p,
                POLL,
                Options.MIN_POLL_MILLIS,
                Options.MAX_POLL_MILLIS,
                d.monitor().pollMillis(),
                file);
    MonitorSettings monitor =
        new MonitorSettings(poll, flag(p, WATCH, d.monitor().useWatchService(), file));
    String encoding = p.getProperty(ENCODING);
    if (encoding == null) {
      encoding = d.defaultEncoding();
    } else if (Options.charset(encoding.strip()) == null) {
      invalid(ENCODING, file);
      encoding = d.defaultEncoding();
    } else {
      encoding = Options.charset(encoding.strip()).name();
    }
    return new Options(
        font,
        recent,
        monitor,
        encoding,
        flag(p, LOAD_LAST, d.loadLastSession(), file),
        ShortcutOverrides.load(p),
        list(p, FAVORITE, Options.MAX_FAVORITES, paths),
        flag(p, LOG_FILE, d.debugLogFile(), file),
        flag(p, CLOSE_WINDOW_WITH_LAST_TAB, d.closeWindowWithLastTab(), file),
        flag(p, CLOSE_SHOWS_LAST_USED, d.closeShowsLastUsed(), file));
  }

  /**
   * Reads the remembered UI state.
   *
   * @param p the file's properties
   * @param file the file, for warnings
   * @param paths resolves the path keys
   * @return the state; defaults for keys that are absent or invalid
   */
  static UiState loadState(Properties p, Path file, InstallPaths paths) {
    UiState d = UiState.DEFAULTS;
    WindowState window =
        new WindowState(
            optional(p, WINDOW_X, -MAX_PIXELS, MAX_PIXELS, file),
            optional(p, WINDOW_Y, -MAX_PIXELS, MAX_PIXELS, file),
            optional(p, WINDOW_WIDTH, 1, MAX_PIXELS, file),
            optional(p, WINDOW_HEIGHT, 1, MAX_PIXELS, file),
            flag(p, WINDOW_MAXIMIZED, false, file));
    int split =
        (int) number(p, WINDOW_SPLIT, Preferences.MIN_SPLIT, Preferences.MAX_SPLIT, 0, file);
    SearchToggles s = d.search();
    SearchToggles search =
        new SearchToggles(
            flag(p, SEARCH_REGEX, s.regex(), file),
            flag(p, SEARCH_CASE, s.caseSensitive(), file),
            flag(p, SEARCH_WORD, s.wholeWord(), file),
            flag(p, SEARCH_INVERSE, s.inverse(), file),
            flag(p, SEARCH_COMBINING, s.combining(), file),
            flag(p, SEARCH_REFRESH, s.autoRefresh(), file),
            flag(p, SEARCH_KEEP, s.keepResults(), file),
            flag(p, SEARCH_RANGE, s.range(), file),
            rangeEnd(p, file),
            (int) number(p, SEARCH_BEFORE, 0, SearchToggles.MAX_CONTEXT, 0, file),
            (int) number(p, SEARCH_AFTER, 0, SearchToggles.MAX_CONTEXT, 0, file));
    Map<String, UiState.Size> dialogs = new LinkedHashMap<String, UiState.Size>();
    for (String key : p.stringPropertyNames()) {
      if (!key.startsWith(DIALOG) || !key.endsWith(".width")) {
        continue;
      }
      String name = key.substring(DIALOG.length(), key.length() - ".width".length());
      if (!name.matches("[A-Za-z0-9]+") || dialogs.size() >= UiState.MAX_DIALOGS) {
        continue;
      }
      Integer width = optional(p, key, 1, MAX_PIXELS, file);
      Integer height = optional(p, DIALOG + name + ".height", 1, MAX_PIXELS, file);
      if (width != null && height != null) {
        dialogs.put(name, new UiState.Size(width, height));
      }
    }
    return new UiState(
        window,
        split,
        search,
        flag(p, LINE_NUMBERS, d.lineNumbers(), file),
        flag(p, FILTERED_LINE_NUMBERS, d.filteredLineNumbers(), file),
        paths.read(p.getProperty(OPEN_DIRECTORY, "")),
        list(p, RECENT, Options.MAX_RECENT, paths),
        dialogs,
        list(p, CLOSED, UiState.MAX_CLOSED, paths),
        folderSearch(p, file, paths));
  }

  // Search in a folder.

  private static FolderSearchSettings folderSearch(Properties p, Path file, InstallPaths paths) {
    FolderSearchSettings d = FolderSearchSettings.DEFAULTS;
    String folder = p.getProperty(FOLDER_SEARCH_FOLDER, d.folder());
    String names = p.getProperty(FOLDER_SEARCH_NAMES, d.names());
    if (folder.length() > FolderSearchSettings.MAX_CHARS) {
      folder = d.folder();
    }
    if (names.length() > FolderSearchSettings.MAX_CHARS) {
      names = d.names();
    }
    String exclude = p.getProperty(FOLDER_SEARCH_EXCLUDE, d.exclude());
    if (exclude.length() > FolderSearchSettings.MAX_CHARS) {
      exclude = d.exclude();
    }
    return new FolderSearchSettings(
        paths.read(folder), names, flag(p, FOLDER_SEARCH_RECURSIVE, d.recursive(), file), exclude);
  }

  /**
   * Writes the options and the state.
   *
   * @param options the options
   * @param state the state
   * @param p receives the keys
   * @param paths relativizes the path keys
   */
  static void store(Options options, UiState state, Properties p, InstallPaths paths) {
    FontSettings font = options.font();
    p.setProperty(FONT_FAMILY, font.family());
    p.setProperty(FONT_SIZE, Integer.toString(font.size()));
    p.setProperty(FONT_ZOOM, Integer.toString(font.zoom()));
    p.setProperty(FONT_BOLD, Boolean.toString(font.bold()));
    p.setProperty(FONT_ANTIALIAS, Boolean.toString(font.antialias()));
    p.setProperty(RECENT_COUNT, Integer.toString(options.recentCount()));
    p.setProperty(POLL, Integer.toString(options.monitor().pollMillis()));
    p.setProperty(WATCH, Boolean.toString(options.monitor().useWatchService()));
    p.setProperty(ENCODING, options.defaultEncoding());
    p.setProperty(LOAD_LAST, Boolean.toString(options.loadLastSession()));
    p.setProperty(LOG_FILE, Boolean.toString(options.debugLogFile()));
    // Last tab.
    p.setProperty(CLOSE_WINDOW_WITH_LAST_TAB, Boolean.toString(options.closeWindowWithLastTab()));
    // Last used tab.
    p.setProperty(CLOSE_SHOWS_LAST_USED, Boolean.toString(options.closeShowsLastUsed()));
    options.shortcuts().store(p);
    storeList(p, FAVORITE, options.favorites(), paths);
    WindowState window = state.window();
    storeOptional(p, WINDOW_X, window.x());
    storeOptional(p, WINDOW_Y, window.y());
    storeOptional(p, WINDOW_WIDTH, window.width());
    storeOptional(p, WINDOW_HEIGHT, window.height());
    p.setProperty(WINDOW_MAXIMIZED, Boolean.toString(window.maximized()));
    if (state.split() != 0) {
      p.setProperty(WINDOW_SPLIT, Integer.toString(state.split()));
    }
    SearchToggles search = state.search();
    p.setProperty(SEARCH_REGEX, Boolean.toString(search.regex()));
    p.setProperty(SEARCH_CASE, Boolean.toString(search.caseSensitive()));
    p.setProperty(SEARCH_WORD, Boolean.toString(search.wholeWord()));
    p.setProperty(SEARCH_INVERSE, Boolean.toString(search.inverse()));
    p.setProperty(SEARCH_COMBINING, Boolean.toString(search.combining()));
    p.setProperty(SEARCH_REFRESH, Boolean.toString(search.autoRefresh()));
    p.setProperty(SEARCH_KEEP, Boolean.toString(search.keepResults()));
    p.setProperty(SEARCH_RANGE, Boolean.toString(search.range()));
    p.setProperty(SEARCH_RANGE_END, search.rangeEnd());
    p.setProperty(SEARCH_BEFORE, Integer.toString(search.contextBefore()));
    p.setProperty(SEARCH_AFTER, Integer.toString(search.contextAfter()));
    p.setProperty(LINE_NUMBERS, Boolean.toString(state.lineNumbers()));
    p.setProperty(FILTERED_LINE_NUMBERS, Boolean.toString(state.filteredLineNumbers()));
    p.setProperty(OPEN_DIRECTORY, paths.write(state.openDirectory()));
    storeList(p, RECENT, state.recentFiles(), paths);
    storeList(p, CLOSED, state.closedFiles(), paths);
    for (Map.Entry<String, UiState.Size> entry : state.dialogs().entrySet()) {
      p.setProperty(DIALOG + entry.getKey() + ".width", Integer.toString(entry.getValue().width()));
      p.setProperty(
          DIALOG + entry.getKey() + ".height", Integer.toString(entry.getValue().height()));
    }
    // Search in a folder.
    FolderSearchSettings folderSearch = state.folderSearch();
    p.setProperty(FOLDER_SEARCH_FOLDER, paths.write(folderSearch.folder()));
    p.setProperty(FOLDER_SEARCH_NAMES, folderSearch.names());
    p.setProperty(FOLDER_SEARCH_RECURSIVE, Boolean.toString(folderSearch.recursive()));
    p.setProperty(FOLDER_SEARCH_EXCLUDE, folderSearch.exclude());
  }

  /**
   * Reports whether a path key holds a value {@link #store} writes differently.
   *
   * @param p the file's properties
   * @param paths relativizes the path keys
   * @return true when the next save rewrites a path key
   */
  static boolean stalePaths(Properties p, InstallPaths paths) {
    if (stale(p, FAVORITE, Options.MAX_FAVORITES, paths)
        || stale(p, RECENT, Options.MAX_RECENT, paths)
        || stale(p, CLOSED, UiState.MAX_CLOSED, paths)) {
      return true;
    }
    String[] keys = {OPEN_DIRECTORY, FOLDER_SEARCH_FOLDER};
    for (String key : keys) {
      String value = p.getProperty(key);
      if (value != null && paths.stale(value)) {
        return true;
      }
    }
    return false;
  }

  private static boolean stale(Properties p, String prefix, int max, InstallPaths paths) {
    for (int i = 0; i < max; i++) {
      String value = p.getProperty(prefix + i);
      if (value == null) {
        break;
      }
      if (paths.stale(value)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> list(Properties p, String prefix, int max, InstallPaths paths) {
    List<String> list = new ArrayList<String>();
    for (int i = 0; i < max; i++) {
      String value = p.getProperty(prefix + i);
      if (value == null) {
        break;
      }
      if (!value.isEmpty()) {
        list.add(paths.read(value));
      }
    }
    return list;
  }

  private static void storeList(
      Properties p, String prefix, List<String> list, InstallPaths paths) {
    for (int i = 0; i < list.size(); i++) {
      p.setProperty(prefix + i, paths.write(list.get(i)));
    }
  }

  private static void storeOptional(Properties p, String key, Integer value) {
    if (value != null) {
      p.setProperty(key, value.toString());
    }
  }

  private static long number(
      Properties p, String key, long min, long max, long fallback, Path file) {
    Integer value = optional(p, key, min, max, file);
    return value == null ? fallback : value;
  }

  /** Returns the number, or null when absent; 0 counts as absent where 0 is out of range. */
  private static Integer optional(Properties p, String key, long min, long max, Path file) {
    String text = p.getProperty(key);
    if (text == null) {
      return null;
    }
    try {
      long parsed = Long.parseLong(text.strip());
      if (parsed >= min && parsed <= max) {
        return (int) parsed;
      }
      if (parsed == 0) {
        return null;
      }
    } catch (NumberFormatException e) {
      // Reported below.
    }
    invalid(key, file);
    return null;
  }

  /** Returns the range end expression; empty when absent or longer than a history entry. */
  private static String rangeEnd(Properties p, Path file) {
    String text = p.getProperty(SEARCH_RANGE_END, "");
    if (text.length() > SearchHistory.MAX_ENTRY_CHARS) {
      invalid(SEARCH_RANGE_END, file);
      return "";
    }
    return text;
  }

  private static boolean flag(Properties p, String key, boolean fallback, Path file) {
    String value = p.getProperty(key);
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
}
