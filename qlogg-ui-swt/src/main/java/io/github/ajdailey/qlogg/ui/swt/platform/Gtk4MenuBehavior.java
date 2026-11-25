package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import java.util.List;
import java.util.Set;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/** GTK 4 menus: SWT ignores {@code MenuItem.setImage}, so no item takes an icon. */
final class Gtk4MenuBehavior implements MenuBehavior {
  @Override
  public void decorate(Menu menu, ItemImages images) {}

  @Override
  public <T> T ink(Display display, Ink<T> ink) {
    return ink.live();
  }

  @Override
  public Image disabledOnDark(Image plain, FadedImage faded) {
    return faded.get();
  }

  @Override
  public void toolWindowMenus(Shell tool, ToolMenus menus) {}

  @Override
  public Set<CommandId> systemCommands(Display display) {
    return MenuTable.TITLE_BAR_COMMANDS;
  }

  @Override
  public List<MenuItem> applicationMenuItems(Display display) {
    return List.of();
  }

  @Override
  public void hookAppMenu(Display display, AppMenuActions actions) {}
}
