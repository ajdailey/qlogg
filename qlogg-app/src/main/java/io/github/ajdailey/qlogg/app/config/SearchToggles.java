package io.github.ajdailey.qlogg.app.config;

/**
 * The search toggles a new tab starts with, as {@code searchOption.*} keeps them. In Selection is
 * never remembered.
 *
 * @param regex regex rather than literal text
 * @param caseSensitive match case
 * @param wholeWord whole words only
 * @param inverse lines that do not match
 * @param combining combined expressions
 * @param autoRefresh extend a finished search over new lines
 * @param keepResults keep the current results when the next search runs
 * @param range range search
 * @param rangeEnd the range end expression; never null
 * @param contextBefore context lines above each result
 * @param contextAfter context lines below each result
 */
public record SearchToggles(
    boolean regex,
    boolean caseSensitive,
    boolean wholeWord,
    boolean inverse,
    boolean combining,
    boolean autoRefresh,
    boolean keepResults,
    // Context lines and range filter.
    boolean range,
    String rangeEnd,
    int contextBefore,
    int contextAfter) {
  /** Most context lines above or below a result, as {@code SearchOptions.MAX_CONTEXT}. */
  public static final int MAX_CONTEXT = 1000;

  /** Regex, case-sensitive, every other toggle off, no range end and no context. */
  public static final SearchToggles DEFAULTS =
      new SearchToggles(true, true, false, false, false, false, false);

  /** Checks the range end. */
  public SearchToggles {
    if (rangeEnd == null) {
      throw new IllegalArgumentException("rangeEnd is required");
    }
  }

  /**
   * Creates toggles with no range and no context.
   *
   * @param regex see the record
   * @param caseSensitive see the record
   * @param wholeWord see the record
   * @param inverse see the record
   * @param combining see the record
   * @param autoRefresh see the record
   * @param keepResults see the record
   */
  public SearchToggles(
      boolean regex,
      boolean caseSensitive,
      boolean wholeWord,
      boolean inverse,
      boolean combining,
      boolean autoRefresh,
      boolean keepResults) {
    this(
        regex,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        autoRefresh,
        keepResults,
        false,
        "",
        0,
        0);
  }
}
