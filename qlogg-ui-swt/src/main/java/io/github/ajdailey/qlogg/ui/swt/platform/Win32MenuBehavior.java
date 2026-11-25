package io.github.ajdailey.qlogg.ui.swt.platform;

import java.util.List;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;

/**
 * win32 menus: icons in the reserved image column, in the ink of the native side the Display
 * started with, which pop-up menus keep after a live switch.
 */
final class Win32MenuBehavior extends ImageMenuBehavior {
  @Override
  void align(List<MenuItem> plain, ItemImages images) {}

  @Override
  public <T> T ink(Display display, Ink<T> ink) {
    return ink.fixed(NativeTheme.startDark(display));
  }

  @Override
  public Image disabledOnDark(Image plain, FadedImage faded) {
    return plain;
  }
}
