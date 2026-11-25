package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/** Menus that show item icons: win32 and GTK 3. */
abstract class ImageMenuBehavior implements MenuBehavior {
  @Override
  public final void decorate(Menu menu, ItemImages images) {
    boolean any = false;
    List<MenuItem> plain = new ArrayList<MenuItem>();
    for (MenuItem item : menu.getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) != 0) {
        continue;
      }
      if (item.getMenu() != null) {
        decorate(item.getMenu(), images);
      }
      if (images.apply(item)) {
        any = true;
      } else {
        plain.add(item);
      }
    }
    if (any) {
      align(plain, images);
    }
  }

  /**
   * Keeps the labels of a menu's items without an icon in line with the others.
   *
   * @param plain the items without an icon
   * @param images sets the blank image
   */
  abstract void align(List<MenuItem> plain, ItemImages images);

  @Override
  public final void toolWindowMenus(Shell tool, ToolMenus menus) {}

  @Override
  public final Set<CommandId> systemCommands(Display display) {
    return MenuTable.TITLE_BAR_COMMANDS;
  }

  @Override
  public final List<MenuItem> applicationMenuItems(Display display) {
    return List.of();
  }

  @Override
  public final void hookAppMenu(Display display, AppMenuActions actions) {}
}
