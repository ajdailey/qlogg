package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * Dialog colors on GTK and cocoa: the native theme draws every surface, so nothing is set. {@code
 * CocoaChrome} recolors cocoa shells after an appearance change.
 */
final class NativeDialogColors implements DialogColors {
  static final NativeDialogColors INSTANCE = new NativeDialogColors();

  private NativeDialogColors() {}

  @Override
  public void apply(Shell shell) {}

  @Override
  public boolean colored(Control control) {
    return false;
  }

  @Override
  public Color background(Shell owner) {
    return owner.getBackground();
  }

  @Override
  public void gridLines(Table table) {
    table.setLinesVisible(true);
  }

  @Override
  public Color disabledText(Display display) {
    return display.getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND);
  }
}
