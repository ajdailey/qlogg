package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.model.MarkComments;
import java.util.List;

/**
 * The open tabs of a window, as {@code qlogg_session.conf} keeps them.
 *
 * @param tabs the tabs in order
 * @param active the shown tab's index, or -1
 */
public record Session(List<Tab> tabs, int active) {
  /** No tabs. */
  public static final Session EMPTY = new Session(List.of(), -1);

  /**
   * One tab.
   *
   * @param path the file's path
   * @param name the name set by Rename Tab, or empty
   * @param follow true when the tab followed its file
   * @param marks marked line ranges as pairs of first and last 0-based line, ascending
   * @param comments the marks' comments, ascending by line
   */
  public record Tab(
      String path, String name, boolean follow, long[] marks, List<MarkComments.Comment> comments) {
    /** Checks the components and copies the comments. */
    public Tab {
      if (path == null || name == null || marks == null || marks.length % 2 != 0) {
        throw new IllegalArgumentException("path, name and paired marks are required");
      }
      comments = List.copyOf(comments);
    }

    /**
     * Creates a tab without mark comments.
     *
     * @param path the file's path
     * @param name the name set by Rename Tab, or empty
     * @param follow true when the tab followed its file
     * @param marks marked line ranges as pairs of first and last 0-based line, ascending
     */
    public Tab(String path, String name, boolean follow, long[] marks) {
      this(path, name, follow, marks, List.of());
    }
  }

  /** Checks the components and copies the list. */
  public Session {
    tabs = List.copyOf(tabs);
    if (active < -1 || active >= tabs.size()) {
      active = tabs.isEmpty() ? -1 : 0;
    }
  }
}
