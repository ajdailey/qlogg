package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.ui.swt.platform.MenuBehavior;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * The icons of a window's menu items: {@link #icon} maps a command to its icon, and {@link
 * #decorate} shows them on the push items of a built menu through the platform's {@link
 * MenuBehavior}. Check, radio, cascade and listed items show none. Call on the UI thread.
 */
final class MenuIcons {
  private final ToolIcons icons;
  private final MenuBehavior menus;

  /**
   * Creates the icons of the running platform.
   *
   * @param owner the control whose background the theme shows; the images are disposed with it
   */
  MenuIcons(Control owner) {
    this(owner, PlatformTraits.PLATFORM.menus());
  }

  /**
   * Creates the icons of a platform.
   *
   * @param owner the control whose background the theme shows; the images are disposed with it
   * @param menus the platform's menus
   */
  MenuIcons(final Control owner, MenuBehavior menus) {
    this.menus = menus;
    icons =
        menus.ink(
            owner.getDisplay(),
            new MenuBehavior.Ink<ToolIcons>() {
              @Override
              public ToolIcons live() {
                return new ToolIcons(owner);
              }

              @Override
              public ToolIcons fixed(boolean dark) {
                return new ToolIcons(owner, dark);
              }
            });
  }

  /**
   * Returns a command's icon.
   *
   * @param command the command
   * @return a {@link ToolIcons} name, or null for a command without one
   */
  static String icon(CommandId command) {
    return switch (command) {
      case OPEN -> ToolIcons.OPEN;
      case CLOSE -> ToolIcons.CLOSE;
      case QUIT -> ToolIcons.QUIT;
      case COPY -> ToolIcons.COPY;
      case SELECT_ALL -> ToolIcons.SELECT_ALL;
      case FIND -> ToolIcons.SEARCH;
      case FIND_NEXT -> ToolIcons.FIND_NEXT;
      case FIND_PREVIOUS -> ToolIcons.FIND_PREVIOUS;
      case SEARCH_FOLDER -> ToolIcons.SEARCH_FOLDER;
      case GO_TO_LINE -> ToolIcons.GO_TO_LINE;
      case RELOAD -> ToolIcons.RELOAD;
      case STOP -> ToolIcons.STOP;
      case PREFERENCES -> ToolIcons.PREFERENCES;
      case NEW_WINDOW -> ToolIcons.TAB_NEW;
      case USER_GUIDE -> ToolIcons.HELP;
      case ABOUT -> ToolIcons.ABOUT;
      default -> null;
    };
  }

  /**
   * Shows the icons of a menu and of its cascades; call once its items exist. An item's {@link
   * CommandId} is its widget data.
   *
   * @param menu the menu
   */
  void decorate(Menu menu) {
    menus.decorate(
        menu,
        new MenuBehavior.ItemImages() {
          @Override
          public boolean apply(MenuItem item) {
            String name = pushIcon(item);
            return name != null && icons.apply(item, name);
          }

          @Override
          public void applyBlank(MenuItem item) {
            icons.applyBlank(item);
          }
        });
  }

  private static String pushIcon(MenuItem item) {
    boolean push = (item.getStyle() & SWT.PUSH) != 0;
    return push && item.getData() instanceof CommandId command ? icon(command) : null;
  }

  /**
   * Shows the image of an item's enabled state; call after changing it.
   *
   * @param item the item
   */
  void showState(MenuItem item) {
    icons.showState(item);
  }

  /** Reads the owner's background again; see {@link ToolIcons#reink}. */
  void reink() {
    icons.reink();
  }

  /**
   * Returns the image set.
   *
   * @return the icons
   */
  ToolIcons icons() {
    return icons;
  }
}
