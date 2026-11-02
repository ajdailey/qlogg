package io.github.ajdailey.qlogg.app.view;

/**
 * What the quick find bar shows, pushed to views on the UI thread.
 *
 * @param open the bar shows
 * @param text the pattern
 * @param ignoreCase the ignore-case box
 * @param regex patterns are regexes, else fixed text ({@code quickFind.regex})
 * @param incremental the bar searches while typing ({@code quickFind.incremental})
 * @param running a find is in progress
 * @param notice the last outcome, such as an end-of-file notice or an invalid pattern, or empty
 */
public record QuickFindState(
    boolean open,
    String text,
    boolean ignoreCase,
    boolean regex,
    boolean incremental,
    boolean running,
    String notice) {
  /** Closed, with fixed-text incremental find. */
  public static final QuickFindState CLOSED =
      new QuickFindState(false, "", true, false, true, false, "");
}
