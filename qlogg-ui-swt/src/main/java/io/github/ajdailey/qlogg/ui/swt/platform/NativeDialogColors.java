package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Dialog colors on GTK and cocoa: the native theme draws every surface, so nothing is set. {@code
 * CocoaChrome} recolors cocoa shells after an appearance change.
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
  public Button check(Composite parent) {
    return new Button(parent, SWT.CHECK);
  }

  @Override
  public Color disabledText(Display display) {
    return display.getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND);
  }
}
