package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;

/** Known SWT 3.135.0 GTK 4 failures, skipped only when SWT loaded GTK 4. */
public final class Gtk4 {
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
   * Returns whether SWT runs on GTK 4. SWT loads GTK 3 when {@code SWT_GTK4=1} is set and GTK 4
   * does not load.
   *
   * @return true when the GTK version SWT loaded is 4
   */
  public static boolean active() {
    if (!"gtk".equals(SWT.getPlatform())) {
      return false;
    }
    // The Display class initializer loads SWT's GTK library, which sets the version.
    Display.getCurrent();
    String version = System.getProperty(PlatformTraits.GTK_VERSION);
    return version != null && version.startsWith("4.");
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
