package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.util.os.OsTraits;

/**
 * Follow options.
 *
 * @param onLoad {@code follow.onLoad}: every file opens following
 * @param onOverscroll {@code follow.onOverscroll}: scrolling down past the last line engages follow
 * @param keepClosed {@code follow.keepClosed}: files opened or reloaded afterwards stay open only
 *     for each read
 */
public record FollowSettings(boolean onLoad, boolean onOverscroll, boolean keepClosed) {
  /** Files open without following; overscroll engages follow; keep file closed is off. */
  static final FollowSettings BASE = new FollowSettings(false, true, false);

  /**
   * Returns a system's defaults: {@link #BASE} with keep file closed on where {@link
   * OsTraits#keepFileClosedByDefault} reports it.
   *
   * @param os the system
   * @return the defaults
   */
  public static FollowSettings defaults(OsTraits os) {
    return new FollowSettings(BASE.onLoad(), BASE.onOverscroll(), os.keepFileClosedByDefault());
  }
}
