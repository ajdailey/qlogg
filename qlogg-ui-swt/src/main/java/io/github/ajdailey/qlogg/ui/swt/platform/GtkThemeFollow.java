package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;

/**
 * GTK: the theme applies to existing controls, and the system setting is read once when the Display
 * is created, so a change made while QLogg runs applies at the next start. Links take a readable
 * link color.
 */
final class GtkThemeFollow implements ThemeFollow {
  private final Display display;

  GtkThemeFollow(Display display) {
    this.display = display;
  }

  @Override
  public boolean start(ThemeMode mode) {
    return mode != ThemeMode.SYSTEM && NativeTheme.set(display, mode);
  }

  @Override
  public void watch(boolean dark, UiDispatcher timers, Runnable refresh) {}

  @Override
  public void systemChanged(boolean dark) {}

  @Override
  public void preferDark(boolean dark) {
    if (!display.isDisposed()) {
      display.setDarkThemePreferred(dark);
    }
  }

  @Override
  public void showOnWindows(boolean dark, WindowRebuild windows) {}

  @Override
  public void settingsChanged() {
    for (Shell shell : display.getShells()) {
      recolorLinks(shell);
    }
  }

  @Override
  public void colorLink(Link link) {
    RGB theme = link.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
    link.setLinkForeground(new Color(Contrast.readable(theme, link.getBackground().getRGB())));
  }

  private void recolorLinks(Control control) {
    if (control instanceof Link link) {
      colorLink(link);
    } else if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        recolorLinks(child);
      }
    }
  }
}
