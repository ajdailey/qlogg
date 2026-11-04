package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.app.command.ShortcutOverrides;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;

/**
 * The settings of the Options dialog that {@link Preferences} does not hold elsewhere, plus the
 * favorites. Immutable.
 *
 * @param font the log views' font
 * @param recentCount {@code recent.count}: entries File > Open Recent keeps, 1 to {@link
 *     #MAX_RECENT}
 * @param monitor {@code monitor.pollMillis} and {@code monitor.watchService}
 * @param defaultEncoding {@code encoding.default}: the charset detection falls back to
 * @param loadLastSession {@code session.loadLast}: a start without files restores the session
 * @param shortcuts {@code shortcut.*}: bindings set in place of the defaults
 * @param favorites {@code favorite.N}: file paths, at most {@link #MAX_FAVORITES}
 * @param debugLogFile {@code log.file}: Help > Debug Log File; the log also goes to the file
 */
public record Options(
    FontSettings font,
    int recentCount,
    MonitorSettings monitor,
    String defaultEncoding,
    boolean loadLastSession,
    ShortcutOverrides shortcuts,
    List<String> favorites,
    boolean debugLogFile) {
  /** Largest {@code recent.count}. */
  public static final int MAX_RECENT = 50;

  /** Most favorites kept. */
  public static final int MAX_FAVORITES = 100;

  /** Shortest poll interval, in milliseconds. */
  public static final int MIN_POLL_MILLIS = 100;

  /** Longest poll interval, in milliseconds. */
  public static final int MAX_POLL_MILLIS = 60_000;

  /** The settings when the file holds none. */
  public static final Options DEFAULTS =
      new Options(
          FontSettings.DEFAULTS,
          20,
          MonitorSettings.DEFAULTS,
          StandardCharsets.ISO_8859_1.name(),
          true,
          ShortcutOverrides.NONE,
          List.of(),
          false);

  /** Checks the components and copies the list. */
  public Options {
    if (font == null
        || monitor == null
        || defaultEncoding == null
        || shortcuts == null
        || favorites == null) {
      throw new IllegalArgumentException("every component is required");
    }
    if (recentCount < 1 || recentCount > MAX_RECENT) {
      throw new IllegalArgumentException("recentCount out of range");
    }
    if (monitor.pollMillis() < MIN_POLL_MILLIS || monitor.pollMillis() > MAX_POLL_MILLIS) {
      throw new IllegalArgumentException("pollMillis out of range");
    }
    if (charset(defaultEncoding) == null) {
      throw new IllegalArgumentException("unsupported encoding");
    }
    favorites =
        List.copyOf(
            favorites.size() > MAX_FAVORITES ? favorites.subList(0, MAX_FAVORITES) : favorites);
  }

  // Monitor settings as plain values, for views that use core.watch only for its enums.

  /**
   * Creates the settings with the monitor settings as values.
   *
   * @param font the log views' font
   * @param recentCount {@code recent.count}
   * @param pollMillis {@code monitor.pollMillis}
   * @param useWatchService {@code monitor.watchService}
   * @param defaultEncoding {@code encoding.default}
   * @param loadLastSession {@code session.loadLast}
   * @param shortcuts {@code shortcut.*}
   * @param favorites {@code favorite.N}
   * @param debugLogFile {@code log.file}
   */
  public Options(
      FontSettings font,
      int recentCount,
      int pollMillis,
      boolean useWatchService,
      String defaultEncoding,
      boolean loadLastSession,
      ShortcutOverrides shortcuts,
      List<String> favorites,
      boolean debugLogFile) {
    this(
        font,
        recentCount,
        new MonitorSettings(pollMillis, useWatchService),
        defaultEncoding,
        loadLastSession,
        shortcuts,
        favorites,
        debugLogFile);
  }

  /**
   * Returns {@code monitor.pollMillis}.
   *
   * @return the interval between checks of a watched file, in milliseconds
   */
  public int pollMillis() {
    return monitor.pollMillis();
  }

  /**
   * Returns {@code monitor.watchService}.
   *
   * @return true when directory events also trigger a check
   */
  public boolean useWatchService() {
    return monitor.useWatchService();
  }

  /**
   * Returns a supported charset.
   *
   * @param name the charset name
   * @return the charset, or null when the name is not supported
   */
  public static Charset charset(String name) {
    try {
      return Charset.forName(name);
    } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
      return null;
    }
  }

  /**
   * Returns the fallback charset.
   *
   * @return the charset of {@link #defaultEncoding}
   */
  public Charset defaultCharset() {
    return charset(defaultEncoding);
  }

  /**
   * Returns a copy with another font.
   *
   * @param value the font
   * @return the copy
   */
  public Options withFont(FontSettings value) {
    return new Options(
        value,
        recentCount,
        monitor,
        defaultEncoding,
        loadLastSession,
        shortcuts,
        favorites,
        debugLogFile);
  }

  /**
   * Returns a copy with other shortcuts.
   *
   * @param value the overrides
   * @return the copy
   */
  public Options withShortcuts(ShortcutOverrides value) {
    return new Options(
        font,
        recentCount,
        monitor,
        defaultEncoding,
        loadLastSession,
        value,
        favorites,
        debugLogFile);
  }

  /**
   * Returns a copy with other favorites.
   *
   * @param value the paths, in menu order
   * @return the copy
   */
  public Options withFavorites(List<String> value) {
    return new Options(
        font,
        recentCount,
        monitor,
        defaultEncoding,
        loadLastSession,
        shortcuts,
        value,
        debugLogFile);
  }

  /**
   * Returns a copy with Help > Debug Log File on or off.
   *
   * @param value true writes the log file too
   * @return the copy
   */
  public Options withDebugLogFile(boolean value) {
    return new Options(
        font, recentCount, monitor, defaultEncoding, loadLastSession, shortcuts, favorites, value);
  }

  /**
   * Returns a copy with a favorite added at the end, unless it is listed or the list is full.
   *
   * @param path the path
   * @return the copy
   */
  public Options addFavorite(String path) {
    if (favorites.contains(path) || favorites.size() >= MAX_FAVORITES) {
      return this;
    }
    List<String> list = new ArrayList<String>(favorites);
    list.add(path);
    return withFavorites(list);
  }

  /**
   * Returns a copy without a favorite.
   *
   * @param path the path
   * @return the copy
   */
  public Options removeFavorite(String path) {
    List<String> list = new ArrayList<String>(favorites);
    list.remove(path);
    return withFavorites(list);
  }
}
