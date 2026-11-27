package io.github.ajdailey.qlogg.ui.swt.platform;

import java.util.List;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;

/**
 * win32 menus: icons in the reserved image column, in the ink of the side pop-up menus show ({@link
 * Win32MenuThemes#shownDark}).
 */
final class Win32MenuBehavior extends ImageMenuBehavior {
  @Override
  void align(List<MenuItem> plain, ItemImages images) {}

  @Override
  public <T> T ink(Display display, Ink<T> ink) {
    return ink.fixed(Win32MenuThemes.shownDark(display));
  }

  @Override
  public Image disabledOnDark(Image plain, FadedImage faded) {
    return plain;
  }
}
