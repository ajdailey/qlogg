package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * cocoa menus: no item icons, the system application menu, and the owner's menus on each tool
 * window.
 */
final class CocoaMenuBehavior implements MenuBehavior {
  @Override
  public void decorate(Menu menu, ItemImages images) {}

  @Override
  public <T> T ink(Display display, Ink<T> ink) {
    return ink.live();
  }

  @Override
  public Image disabledOnDark(Image plain, FadedImage faded) {
    return plain;
  }

  @Override
  public void toolWindowMenus(Shell tool, ToolMenus menus) {
    menus.build(tool);
  }

  @Override
  public Set<CommandId> systemCommands(Display display) {
    return display.getSystemMenu() != null ? MenuTable.APP_MENU_COMMANDS : Set.of();
  }

  @Override
  public void hookAppMenu(Display display, final AppMenuActions actions) {
    Menu menu = display.getSystemMenu();
    if (menu == null) {
      return;
    }
    for (MenuItem item : menu.getItems()) {
      final int id = item.getID();
      if (id == SWT.ID_ABOUT || id == SWT.ID_PREFERENCES || id == SWT.ID_QUIT) {
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                actions.select(id);
              }
            });
      }
    }
    display.addListener(
        SWT.Close,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            actions.quit();
          }
        });
    display.addListener(
        SWT.OpenDocument,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.text != null && !event.text.isEmpty()) {
              actions.open(event.text);
            }
          }
        });
  }
}
