package io.github.ajdailey.qlogg.app.config;

/**
 * Follow options.
 *
 * @param onLoad {@code follow.onLoad}: every file opens following
 * @param onOverscroll {@code follow.onOverscroll}: scrolling down past the last line engages follow
 */
public record FollowSettings(boolean onLoad, boolean onOverscroll) {
  /** Files open without following; overscroll engages follow. */
  public static final FollowSettings DEFAULTS = new FollowSettings(false, true);
}
