package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Dialog colors on GTK, cocoa and a light win32 start: the native theme draws every surface, so
 * nothing is set. {@link CocoaChrome} recolors cocoa shells after an appearance change.
 */
final class NativeDialogColors implements DialogColors {
  @Override
  public void prepare(Shell shell) {}

  @Override
  public void apply(Control control) {}

  @Override
  public Color background(Shell owner) {
    return owner.getBackground();
  }

  @Override
  public Color disabledText(Display display) {
    return display.getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND);
  }
}
