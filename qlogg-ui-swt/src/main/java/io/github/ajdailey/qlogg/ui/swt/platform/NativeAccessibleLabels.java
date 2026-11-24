package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.widgets.Control;

/** win32 and GTK: screen readers read the accessible name. */
final class NativeAccessibleLabels implements AccessibleLabels {
  @Override
  public void describe(Control control, AccessibleListener description) {}
}
