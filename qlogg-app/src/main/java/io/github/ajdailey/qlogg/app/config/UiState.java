package io.github.ajdailey.qlogg.app.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembered UI state in {@code qlogg.conf}: restored at start, never set from the command line.
 * Immutable.
 *
 * @param window {@code window.*}: normal bounds and maximized state
 * @param split {@code window.split}: the main view's share of the split at the last exit, or 0
 * @param search {@code searchOption.*}: the toggles a new document's search starts with
 * @param lineNumbers {@code view.lineNumbers}: a new tab's main view shows line numbers
 * @param filteredLineNumbers {@code view.filteredLineNumbers}: a new tab's filtered view shows them
 * @param openDirectory {@code open.directory}: where File > Open starts, or empty
 * @param recentFiles {@code recent.N}: most recent first, at most {@link Options#MAX_RECENT}
 * @param dialogs {@code dialog.<name>.width} and {@code .height}: a dialog's last size
 * @param closedFiles {@code closed.N}: File > Recently Closed, most recent first, at most {@link
 *     #MAX_CLOSED}
 */
public record UiState(
    WindowState window,
    int split,
    SearchToggles search,
    boolean lineNumbers,
    boolean filteredLineNumbers,
    String openDirectory,
    List<String> recentFiles,
    Map<String, Size> dialogs,
    List<String> closedFiles) {
  /** Most dialog sizes kept. */
  public static final int MAX_DIALOGS = 32;

  /** Most recently closed files kept. */
  public static final int MAX_CLOSED = 20;

  /** Nothing remembered. */
  public static final UiState DEFAULTS =
      new UiState(
          WindowState.UNKNOWN,
          0,
          SearchToggles.DEFAULTS,
          true,
          true,
          "",
          List.of(),
          Map.of(),
          List.of());

  /**
   * A dialog's size in pixels.
   *
   * @param width at least 1
   * @param height at least 1
   */
  public record Size(int width, int height) {
    /** Checks the components. */
    public Size {
      if (width < 1 || height < 1) {
        throw new IllegalArgumentException("size must be positive");
      }
    }
  }

  /** Checks the components and copies the collections. */
  public UiState {
    if (window == null
        || search == null
        || openDirectory == null
        || recentFiles == null
        || dialogs == null
        || closedFiles == null) {
      throw new IllegalArgumentException("every component is required");
    }
    if (split != 0 && (split < Preferences.MIN_SPLIT || split > Preferences.MAX_SPLIT)) {
      throw new IllegalArgumentException("split out of range");
    }
    recentFiles =
        List.copyOf(
            recentFiles.size() > Options.MAX_RECENT
                ? recentFiles.subList(0, Options.MAX_RECENT)
                : recentFiles);
    dialogs = Collections.unmodifiableMap(new LinkedHashMap<String, Size>(dialogs));
    closedFiles =
        List.copyOf(
            closedFiles.size() > MAX_CLOSED ? closedFiles.subList(0, MAX_CLOSED) : closedFiles);
  }

  /**
   * Returns a copy with another window placement.
   *
   * @param value the placement
   * @param splitPercent the split, or 0
   * @return the copy
   */
  public UiState withWindow(WindowState value, int splitPercent) {
    return new UiState(
        value,
        splitPercent,
        search,
        lineNumbers,
        filteredLineNumbers,
        openDirectory,
        recentFiles,
        dialogs,
        closedFiles);
  }

  /**
   * Returns a copy with other search toggles.
   *
   * @param value the toggles
   * @return the copy
   */
  public UiState withSearch(SearchToggles value) {
    return new UiState(
        window,
        split,
        value,
        lineNumbers,
        filteredLineNumbers,
        openDirectory,
        recentFiles,
        dialogs,
        closedFiles);
  }

  /**
   * Returns a copy with other line number defaults.
   *
   * @param main the main view's
   * @param filtered the filtered view's
   * @return the copy
   */
  public UiState withLineNumbers(boolean main, boolean filtered) {
    return new UiState(
        window, split, search, main, filtered, openDirectory, recentFiles, dialogs, closedFiles);
  }

  /**
   * Returns a copy with another Open dialog directory.
   *
   * @param value the directory
   * @return the copy
   */
  public UiState withOpenDirectory(String value) {
    return new UiState(
        window,
        split,
        search,
        lineNumbers,
        filteredLineNumbers,
        value,
        recentFiles,
        dialogs,
        closedFiles);
  }

  /**
   * Returns a copy with other recent files.
   *
   * @param value most recent first
   * @return the copy
   */
  public UiState withRecentFiles(List<String> value) {
    return new UiState(
        window,
        split,
        search,
        lineNumbers,
        filteredLineNumbers,
        openDirectory,
        value,
        dialogs,
        closedFiles);
  }

  /**
   * Returns a copy with a file first in the recent list.
   *
   * @param path the path
   * @param count entries kept
   * @return the copy
   */
  public UiState addRecent(String path, int count) {
    List<String> list = new ArrayList<String>(recentFiles);
    list.remove(path);
    list.add(0, path);
    while (list.size() > count) {
      list.remove(list.size() - 1);
    }
    return withRecentFiles(list);
  }

  // Recently Closed.

  /**
   * Returns a copy with other recently closed files.
   *
   * @param value most recent first
   * @return the copy
   */
  public UiState withClosedFiles(List<String> value) {
    return new UiState(
        window,
        split,
        search,
        lineNumbers,
        filteredLineNumbers,
        openDirectory,
        recentFiles,
        dialogs,
        value);
  }

  /**
   * Returns a copy with a file first in Recently Closed, at most {@link #MAX_CLOSED} kept.
   *
   * @param path the path
   * @return the copy
   */
  public UiState addClosed(String path) {
    List<String> list = new ArrayList<String>(closedFiles);
    list.remove(path);
    list.add(0, path);
    return withClosedFiles(list);
  }

  /**
   * Returns a copy with a file left out of Recently Closed.
   *
   * @param path the path
   * @return the copy, or this one when the file is not listed
   */
  public UiState removeClosed(String path) {
    if (!closedFiles.contains(path)) {
      return this;
    }
    List<String> list = new ArrayList<String>(closedFiles);
    list.remove(path);
    return withClosedFiles(list);
  }

  /**
   * Returns a copy with a dialog's size; the oldest entry goes past {@link #MAX_DIALOGS}.
   *
   * @param name the dialog's name, {@code [A-Za-z0-9]+}
   * @param size its size
   * @return the copy
   */
  public UiState withDialog(String name, Size size) {
    if (!name.matches("[A-Za-z0-9]+")) {
      throw new IllegalArgumentException("dialog name");
    }
    Map<String, Size> copy = new LinkedHashMap<String, Size>(dialogs);
    copy.remove(name);
    copy.put(name, size);
    while (copy.size() > MAX_DIALOGS) {
      copy.remove(copy.keySet().iterator().next());
    }
    return new UiState(
        window,
        split,
        search,
        lineNumbers,
        filteredLineNumbers,
        openDirectory,
        recentFiles,
        copy,
        closedFiles);
  }
}
