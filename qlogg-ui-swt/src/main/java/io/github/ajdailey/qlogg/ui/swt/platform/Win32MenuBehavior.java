package io.github.ajdailey.qlogg.ui.swt.platform;

import java.util.List;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;

/** win32 menus: icons in the reserved image column, inked for the native side. */
final class Win32MenuBehavior extends ImageMenuBehavior {
  @Override
  void align(List<MenuItem> plain, ItemImages images) {}

  @Override
  public <T> T ink(Display display, Ink<T> ink) {
    return ink.fixed(DialogColors.nativeDark(display));
  }

  @Override
  public Image disabledOnDark(Image plain, FadedImage faded) {
    return plain;
  }
}
