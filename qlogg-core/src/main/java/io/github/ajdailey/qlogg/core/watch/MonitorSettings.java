package io.github.ajdailey.qlogg.core.watch;

import io.github.ajdailey.qlogg.core.util.Checks;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;

/**
 * File monitoring settings.
 *
 * @param pollMillis interval between checks of a watched file, at least 1
 * @param useWatchService true to also check on the parent directory's {@code WatchService} events
 */
public record MonitorSettings(int pollMillis, boolean useWatchService) {
  /** A check every second, plus directory events. */
  public static final MonitorSettings DEFAULTS = new MonitorSettings(1000, true);

  /**
   * Returns a system's defaults: {@link #DEFAULTS} with directory events where {@link
   * OsTraits#watchServiceByDefault} reports them.
   *
   * @param os the system
   * @return the defaults
   */
  public static MonitorSettings defaults(OsTraits os) {
    return new MonitorSettings(DEFAULTS.pollMillis(), os.watchServiceByDefault());
  }

  /** Checks the interval. */
  public MonitorSettings {
    Checks.atLeast(pollMillis, 1, "pollMillis");
  }
}
