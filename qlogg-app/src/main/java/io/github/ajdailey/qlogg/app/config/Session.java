package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.model.MarkComments;
import java.util.List;

/**
 * The open tabs of a window, as {@code qlogg_session.conf} keeps them.
 *
 * @param tabs the tabs in order
 * @param active the shown tab's index, or -1
 * @param droppedComments mark comments the last save left out at the cap; the store keeps one count
 *     for all windows and loads it on the first
 * @param onTop true when the window stays above other windows (View > Always on Top)
 * @param window the window's normal bounds and maximized state, {@link WindowState#UNKNOWN} when
 *     not saved
 */
public record Session(
    List<Tab> tabs, int active, long droppedComments, boolean onTop, WindowState window) {
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
   * @param timeOffset the tab's time offset in nanoseconds
   * @param syncGroup the tab's time sync group number, or 0
   * @param rotated true when the tab showed the rotated set of {@code path}
   * @param members a rotated tab's marks and comments per member; {@code marks} and {@code
   *     comments} are then empty
   * @param derived a filter tab's definition, with {@code path} its root's file and no marks; null
   *     for a file tab
   */
  public record Tab(
      String path,
      String name,
      boolean follow,
      long[] marks,
      List<MarkComments.Comment> comments,
      long timeOffset,
      int syncGroup,
      boolean rotated,
      List<MemberMarks> members,
      Derived derived) {
    /** Checks the components and copies the lists. */
    public Tab {
      if (path == null || name == null || marks == null || marks.length % 2 != 0) {
        throw new IllegalArgumentException("path, name and paired marks are required");
      }
      comments = List.copyOf(comments);
      members = List.copyOf(members);
    }

    /**
     * Creates a file tab.
     *
     * @param path the file's path
     * @param name the name set by Rename Tab, or empty
     * @param follow true when the tab followed its file
     * @param marks marked line ranges as pairs of first and last 0-based line, ascending
     * @param comments the marks' comments, ascending by line
     * @param timeOffset the tab's time offset in nanoseconds
     * @param syncGroup the tab's time sync group number, or 0
     * @param rotated true when the tab showed the rotated set of {@code path}
     * @param members a rotated tab's marks and comments per member
     */
    public Tab(
        String path,
        String name,
        boolean follow,
        long[] marks,
        List<MarkComments.Comment> comments,
        long timeOffset,
        int syncGroup,
        boolean rotated,
        List<MemberMarks> members) {
      this(path, name, follow, marks, comments, timeOffset, syncGroup, rotated, members, null);
    }

    /**
     * Creates a tab of one file.
     *
     * @param path the file's path
     * @param name the name set by Rename Tab, or empty
     * @param follow true when the tab followed its file
     * @param marks marked line ranges as pairs of first and last 0-based line, ascending
     * @param comments the marks' comments, ascending by line
     * @param timeOffset the tab's time offset in nanoseconds
     * @param syncGroup the tab's time sync group number, or 0
     */
    public Tab(
        String path,
        String name,
        boolean follow,
        long[] marks,
        List<MarkComments.Comment> comments,
        long timeOffset,
        int syncGroup) {
      this(path, name, follow, marks, comments, timeOffset, syncGroup, false, List.of());
    }

    /**
     * Creates a tab outside a time sync group.
     *
     * @param path the file's path
     * @param name the name set by Rename Tab, or empty
     * @param follow true when the tab followed its file
     * @param marks marked line ranges as pairs of first and last 0-based line, ascending
     * @param comments the marks' comments, ascending by line
     * @param timeOffset the tab's time offset in nanoseconds
     */
    public Tab(
        String path,
        String name,
        boolean follow,
        long[] marks,
        List<MarkComments.Comment> comments,
        long timeOffset) {
      this(path, name, follow, marks, comments, timeOffset, 0);
    }

    /**
     * Creates a tab without a time offset.
     *
     * @param path the file's path
     * @param name the name set by Rename Tab, or empty
     * @param follow true when the tab followed its file
     * @param marks marked line ranges as pairs of first and last 0-based line, ascending
     * @param comments the marks' comments, ascending by line
     */
    public Tab(
        String path,
        String name,
        boolean follow,
        long[] marks,
        List<MarkComments.Comment> comments) {
      this(path, name, follow, marks, comments, 0);
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
      this(path, name, follow, marks, List.of(), 0);
    }
  }

  /**
   * A filter tab's definition: its parent tab and its search. Restore derives the rows again.
   *
   * @param parentWindow the parent tab's window number in the session
   * @param parentTab the parent tab's number in that window
   * @param text the search text
   * @param toggles the search's toggles
   * @param scopeFirst the first root line of a scoped search, or -1 for every line
   * @param scopeEnd the first root line after the scope, or -1
   * @param column the search's column, or empty
   */
  public record Derived(
      int parentWindow,
      int parentTab,
      String text,
      SearchToggles toggles,
      long scopeFirst,
      long scopeEnd,
      String column) {
    /** Checks the components. */
    public Derived {
      if (text == null || toggles == null || column == null || parentWindow < 0 || parentTab < 0) {
        throw new IllegalArgumentException("parent, text, toggles and column are required");
      }
    }
  }

  /**
   * One member's marks in a rotated tab, keyed by the member's content so they find the member
   * after a roll-over renames it.
   *
   * @param digest CRC32C of the member's first 4 KiB ({@code SetFiles.Loaded#headDigest})
   * @param marks ranges as pairs of first and last 0-based line in the member, ascending
   * @param comments the marks' comments, lines in the member, ascending
   */
  public record MemberMarks(long digest, long[] marks, List<MarkComments.Comment> comments) {
    /** Checks the ranges and copies the comments. */
    public MemberMarks {
      if (marks == null || marks.length % 2 != 0) {
        throw new IllegalArgumentException("paired marks are required");
      }
      comments = List.copyOf(comments);
    }
  }

  /**
   * Creates a session without window bounds.
   *
   * @param tabs the tabs in order
   * @param active the shown tab's index, or -1
   * @param droppedComments mark comments the last save left out at the cap
   * @param onTop true when the window stays above other windows
   */
  public Session(List<Tab> tabs, int active, long droppedComments, boolean onTop) {
    this(tabs, active, droppedComments, onTop, WindowState.UNKNOWN);
  }

  /**
   * Creates a session that is not on top.
   *
   * @param tabs the tabs in order
   * @param active the shown tab's index, or -1
   * @param droppedComments mark comments the last save left out at the cap
   */
  public Session(List<Tab> tabs, int active, long droppedComments) {
    this(tabs, active, droppedComments, false);
  }

  /**
   * Creates a session that left out no comment.
   *
   * @param tabs the tabs in order
   * @param active the shown tab's index, or -1
   */
  public Session(List<Tab> tabs, int active) {
    this(tabs, active, 0, false);
  }

  /** Checks the components and copies the list. */
  public Session {
    tabs = List.copyOf(tabs);
    if (window == null) {
      window = WindowState.UNKNOWN;
    }
    if (active < -1 || active >= tabs.size()) {
      active = tabs.isEmpty() ? -1 : 0;
    }
  }
}
