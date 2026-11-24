package io.github.ajdailey.qlogg.ui.swt.platform;

import java.util.List;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;

/** GTK 3 menus: icons with blank images that align labels, inked from the live theme. */
final class Gtk3MenuBehavior extends ImageMenuBehavior {
  @Override
  void align(List<MenuItem> plain, ItemImages images) {
    for (MenuItem item : plain) {
      images.applyBlank(item);
    }
  }

  @Override
  public <T> T ink(Display display, Ink<T> ink) {
    return ink.live();
  }

  @Override
  public Image disabledOnDark(Image plain, FadedImage faded) {
    return faded.get();
  }
}
