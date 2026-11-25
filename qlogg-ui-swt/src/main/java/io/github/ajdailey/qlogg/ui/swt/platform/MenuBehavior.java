package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.command.CommandId;
import java.util.Set;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Menu bar differences, one implementation per platform from {@link PlatformTraits#menus}: item
 * icons, their ink and disabled image, tool window menus, and the commands the system supplies.
 * Confined to the UI thread.
 */
public interface MenuBehavior {
  /**
   * Shows icons on a built menu's items and on its cascades' items. win32 and GTK 3 show them;
   * cocoa menus show none and GTK 4 SWT ignores {@code MenuItem.setImage}. GTK 3 reserves no image
   * column, so a label moves right when its item has an image: every other item of a menu with an
   * icon there takes the blank image.
   *
   * @param menu the menu
   * @param images sets an item's icon
   */
  void decorate(Menu menu, ItemImages images);

  /**
   * Chooses the menu icons' ink source. win32 pop-up menus keep the native side the Display started
   * with through every live switch and window rebuild, while the toolbar follows the live theme, so
   * there the ink is fixed for that side; GTK menus show the theme the owner toolbar shows.
   *
   * @param <T> the icon set type
   * @param display the Display
   * @param ink creates the set
   * @return the set: {@link Ink#fixed} on win32, {@link Ink#live} elsewhere
   */
  <T> T ink(Display display, Ink<T> ink);

  /**
   * Returns the image a disabled item shows on a dark background. GTK's disabled effect brightens
   * an image there, so GTK takes a faded copy.
   *
   * @param plain the item's icon
   * @param faded creates the faded copy
   * @return the image
   */
  Image disabledOnDark(Image plain, FadedImage faded);

  /**
   * Gives a tool window its owner's menus on cocoa, where the menu bar shows the key window's menus
   * and a window without a menu bar shows only the application menu. Does nothing elsewhere, where
   * a menu bar draws inside its window.
   *
   * @param tool a modeless window a main window owns
   * @param menus builds the owner's menus on the tool window
   */
  void toolWindowMenus(Shell tool, ToolMenus menus);

  /**
   * Returns the commands the system supplies outside the menu bar, which the menu bar leaves out.
   *
   * @param display the Display
   * @return on cocoa, About, Preferences and Quit when {@code Display.getSystemMenu()} is not null,
   *     else empty; elsewhere {@code MenuTable.TITLE_BAR_COMMANDS}; unmodifiable
   */
  Set<CommandId> systemCommands(Display display);

  /**
   * Connects the macOS application menu on cocoa: its About, Preferences and Quit items, the
   * Display's {@code SWT.Close} (Cmd-Q, logout), which is vetoed, and {@code SWT.OpenDocument}.
   * Does nothing elsewhere. Call once, on the UI thread.
   *
   * @param display the Display
   * @param actions runs what the menu and the system request
   */
  void hookAppMenu(Display display, AppMenuActions actions);

  /** Runs what the application menu and the system request. */
  interface AppMenuActions {
    /**
     * Runs an application menu item.
     *
     * @param id the item's {@code MenuItem.getID()}: {@code SWT.ID_ABOUT}, {@code
     *     SWT.ID_PREFERENCES} or {@code SWT.ID_QUIT}
     */
    void select(int id);

    /** Quits on a system request. */
    void quit();

    /**
     * Opens a file the Finder or the Dock sends.
     *
     * @param file the path, not empty
     */
    void open(String file);
  }

  /** Builds a main window's menus on one of its tool windows. */
  interface ToolMenus {
    /**
     * Sets the owner's menus as the tool window's menu bar; Close closes the tool window and every
     * other item acts on the owner.
     *
     * @param tool the tool window
     */
    void build(Shell tool);
  }

  /** Sets menu item icons. */
  interface ItemImages {
    /**
     * Sets an item's icon.
     *
     * @param item a menu item that is not a separator
     * @return true when the item has an icon
     */
    boolean apply(MenuItem item);

    /**
     * Sets the blank image that keeps an item's label in line with its siblings'.
     *
     * @param item the item
     */
    void applyBlank(MenuItem item);
  }

  /**
   * Creates a menu icon set.
   *
   * @param <T> the set type
   */
  interface Ink<T> {
    /**
     * Creates a set inked from the owner's shown background.
     *
     * @return the set
     */
    T live();

    /**
     * Creates a set of fixed ink.
     *
     * @param dark true for the light ink
     * @return the set
     */
    T fixed(boolean dark);
  }

  /** Creates a faded copy of an icon. */
  interface FadedImage {
    /**
     * Returns the faded copy.
     *
     * @return the image
     */
    Image get();
  }
}
