package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.swt.SWT;

/** Known SWT 3.135.0 GTK 4 failures, skipped only on the GTK 4 backend ({@code SWT_GTK4=1}). */
final class Gtk4 {
  /**
   * {@code Control.print} returns false: the GTK 4 branch snapshots the widget's paintable, and the
   * snapshot yields no render node (SWT 3.135.0 bytecode; measured under Xvfb).
   */
  static final String NO_PRINT = "SWT GTK 4: Control.print returns false (empty widget snapshot)";

  /** GTK 4 has no API to move a toplevel window, so {@code Shell.getLocation} reads 0,0. */
  static final String NO_POSITION = "GTK 4: a shell cannot be positioned; getLocation reads 0,0";

  /** {@code GC.copyArea} on a Display GC yields a white image (measured under Xvfb). */
  static final String NO_SCREEN_READ = "SWT GTK 4: GC.copyArea from the Display reads white";

  private Gtk4() {}

  /**
   * Returns whether SWT runs on GTK 4.
   *
   * @return true with {@code SWT_GTK4=1} on GTK
   */
  static boolean active() {
    return "gtk".equals(SWT.getPlatform()) && "1".equals(System.getenv("SWT_GTK4"));
  }

  /**
   * Asserts that {@code Control.print} painted; on GTK 4 a false result skips the test instead.
   *
   * @param printed the result of {@code print}
   */
  static void assertPrinted(boolean printed) {
    assumeTrue(printed || !active(), NO_PRINT);
    assertTrue(printed, "print");
  }
}
