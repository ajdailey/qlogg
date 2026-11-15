package io.github.ajdailey.qlogg.ui.swt;

/**
 * Turns wheel counts over a tab row into tab steps. Counts add up; each {@link #UNITS} units step
 * one tab, toward the next tab for a negative count and the previous for a positive one. A count
 * against the sum's direction restarts the sum at that count. A step past the first or last tab is
 * dropped and clears the sum; the selection does not wrap.
 *
 * <p>A Win32 or GTK wheel notch sends one count of about 3; a cocoa trackpad gesture sends many
 * counts of 1 or 2.
 */
final class TabWheel {
  /** The units one tab step takes. */
  static final int UNITS = 3;

  private int sum;

  /**
   * Adds a wheel count and returns the tab to select.
   *
   * @param count the wheel count; positive toward the previous tab
   * @param index the selected tab
   * @param tabs the number of tabs
   * @return the tab to select, {@code index} for none
   */
  int add(int count, int index, int tabs) {
    if (count == 0) {
      return index;
    }
    if (Integer.signum(sum) == -Integer.signum(count)) {
      sum = 0;
    }
    sum += count;
    int target = index;
    while (Math.abs(sum) >= UNITS) {
      int step = sum < 0 ? 1 : -1;
      if (target + step < 0 || target + step >= tabs) {
        sum = 0;
        break;
      }
      target += step;
      sum += step * UNITS;
    }
    return target;
  }
}
