package io.github.ajdailey.qlogg.app.config;

/**
 * The log views' font: {@code font.family}, {@code font.size}, {@code font.zoom}, {@code font.bold}
 * and {@code font.antialias}.
 *
 * @param family the family; empty for the platform's monospace font
 * @param size the size set in Preferences, in points; 0 for the platform's default
 * @param zoom the size zooming chose, in points; 0 when not zoomed
 * @param bold true draws bold where the font has it
 * @param antialias true forces anti-aliased text
 */
public record FontSettings(String family, int size, int zoom, boolean bold, boolean antialias) {
  /** Smallest size, in points. */
  public static final int MIN_SIZE = 4;

  /** Largest size, in points. */
  public static final int MAX_SIZE = 72;

  /** Longest family name kept. */
  public static final int MAX_FAMILY_CHARS = 128;

  /** The platform's monospace font at its default size. */
  public static final FontSettings DEFAULTS = new FontSettings("", 0, 0, false, false);

  /** Checks the components. */
  public FontSettings {
    if (family == null || family.length() > MAX_FAMILY_CHARS) {
      throw new IllegalArgumentException("family is required and at most 128 chars");
    }
    if (size != 0 && (size < MIN_SIZE || size > MAX_SIZE)) {
      throw new IllegalArgumentException("size out of range");
    }
    if (zoom != 0 && (zoom < MIN_SIZE || zoom > MAX_SIZE)) {
      throw new IllegalArgumentException("zoom out of range");
    }
  }

  /**
   * Returns the size the views draw with.
   *
   * @param platformSize the platform's default size in points
   * @return the zoomed size, else the Preferences size, else the platform's
   */
  public int effectiveSize(int platformSize) {
    if (zoom != 0) {
      return zoom;
    }
    return size != 0 ? size : platformSize;
  }

  /**
   * Returns a copy zoomed to a size, clamped to {@link #MIN_SIZE} and {@link #MAX_SIZE}.
   *
   * @param points the size
   * @return the copy
   */
  public FontSettings withZoom(int points) {
    int clamped = Math.max(MIN_SIZE, Math.min(MAX_SIZE, points));
    return new FontSettings(family, size, clamped, bold, antialias);
  }

  /**
   * Returns a copy without zoom.
   *
   * @return the copy
   */
  public FontSettings withoutZoom() {
    return new FontSettings(family, size, 0, bold, antialias);
  }
}
