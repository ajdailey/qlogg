package io.github.ajdailey.qlogg.core.time;

/** What the overview bar's time-spread layer shades. */
public enum SpreadMode {
  /** No layer. */
  OFF("off"),
  /** Rows with more lines per second than the average. */
  ACTIVITY("activity"),
  /** Rows spanning more time than the average. */
  PAUSES("pauses");

  private final String id;

  SpreadMode(String id) {
    this.id = id;
  }

  /**
   * Returns the value stored in the settings.
   *
   * @return for example {@code pauses}
   */
  public String id() {
    return id;
  }

  /**
   * Finds a mode by its {@link #id()}.
   *
   * @param id a stored value
   * @return the mode, or null for an unknown value
   */
  public static SpreadMode byId(String id) {
    for (SpreadMode mode : values()) {
      if (mode.id.equals(id)) {
        return mode;
      }
    }
    return null;
  }
}
