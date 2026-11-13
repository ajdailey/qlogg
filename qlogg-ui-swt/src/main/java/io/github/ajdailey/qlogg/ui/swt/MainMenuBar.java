package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandGroup;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.CommandStates;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.EncodingChoices;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * The main window's menu bar: one menu per {@link CommandGroup} with its {@link MenuTable}
 * commands, Window > Opened Files, File > Recent Files, Recently Closed and Favorites, the
 * highlighter sets, Highlighters > Color Label and the encodings. Item state comes from {@link
 * CommandStates}; this class only applies it. {@link MenuIcons} gives the command items their
 * icons.
 *
 * <p>Showing a top menu syncs that menu's items and its listed entries only. Enablement of every
 * item also follows each document event ({@link #syncEnablement}), because a disabled item's
 * accelerator does not fire. The Encoding menu's common charsets are created with the menu bar and
 * the Other cascade, disabled and empty, is filled once by {@link #encodingsLoaded}; neither is
 * rebuilt, and a show updates their enablement and radio selection. Call on the UI thread.
 */
final class MainMenuBar {
  /** Widget data of the items the menus list after their commands. */
  private static final String LISTED = "qlogg.listed";

  private final Shell shell;
  private final ApplicationController controller;
  private final CommandStates states;
  private final Map<CommandId, MenuItem> items = new EnumMap<CommandId, MenuItem>(CommandId.class);
  private final Map<CommandGroup, Menu> menus = new EnumMap<CommandGroup, Menu>(CommandGroup.class);
  private final Map<String, MenuItem> encodingItems = new HashMap<String, MenuItem>();
  private final List<MenuItem> commonEncodingItems = new ArrayList<MenuItem>();

  /** Charset items that may show a selection: the one last checked and any the user chose. */
  private final List<MenuItem> selectedEncodings = new ArrayList<MenuItem>();

  private MenuItem otherEncodings;
  private boolean otherLoaded;
  private Menu openedFilesMenu;
  private CommandGroup openedFilesGroup;
  private Menu recentMenu;
  private CommandGroup recentGroup;
  // Recently Closed.
  private Menu closedMenu;
  private CommandGroup closedGroup;
  // Timestamps.
  private Menu timeFormatMenu;
  private CommandGroup timeFormatGroup;
  // Time spread.
  private Menu nestedMenu;
  // Menu structure.
  private Menu favoritesMenu;
  private CommandGroup favoritesGroup;
  private Menu colorLabelMenu;
  private Keymap keymap;
  // Menu item icons.
  private final MenuIcons icons;

  /**
   * Creates the menu bar's owner; {@link #build} creates the menus.
   *
   * @param shell the main window's shell
   * @param controller the window's controller
   * @param icons the items' icons
   */
  MainMenuBar(Shell shell, ApplicationController controller, MenuIcons icons) {
    this.shell = shell;
    this.controller = controller;
    this.states = new CommandStates(controller);
    this.icons = icons;
  }

  /**
   * Returns the state the items apply.
   *
   * @return the command states over this window's controller
   */
  CommandStates states() {
    return states;
  }

  /**
   * Creates the menu bar with the keys of a keymap, sets it on the shell and disposes the previous
   * one.
   *
   * @param keymap the key bindings the items show and the accelerators come from
   */
  void build(Keymap keymap) {
    Menu old = shell.getMenuBar();
    items.clear();
    menus.clear();
    encodingItems.clear();
    commonEncodingItems.clear();
    selectedEncodings.clear();
    otherLoaded = false;
    openedFilesMenu = null;
    recentMenu = null;
    // Recently Closed.
    closedMenu = null;
    timeFormatMenu = null;
    nestedMenu = null;
    favoritesMenu = null;
    colorLabelMenu = null;
    this.keymap = keymap;
    Menu bar = new Menu(shell, SWT.BAR);
    boolean mac = SwtKeys.mac();
    boolean appMenu = AppMenu.present(shell.getDisplay());
    for (final CommandGroup group : CommandGroup.values()) {
      if (!group.menu()) {
        continue;
      }
      MenuItem header = new MenuItem(bar, SWT.CASCADE);
      header.setText(group.label());
      Menu menu = new Menu(shell, SWT.DROP_DOWN);
      header.setMenu(menu);
      menus.put(group, menu);
      Menu target = menu;
      String cascade = null;
      for (final CommandId command : MenuTable.commands(group, appMenu)) {
        String submenu = command.submenu();
        if (submenu == null) {
          target = menu;
          cascade = null;
        } else if (!submenu.equals(cascade)) {
          if (command.separatorBefore() && menu.getItemCount() > 0) {
            new MenuItem(menu, SWT.SEPARATOR);
          }
          MenuItem holder = new MenuItem(menu, SWT.CASCADE);
          holder.setText(submenu);
          target = new Menu(shell, SWT.DROP_DOWN);
          holder.setMenu(target);
          cascade = submenu;
        }
        if (command.separatorBefore() && target.getItemCount() > 0) {
          new MenuItem(target, SWT.SEPARATOR);
        }
        if (MenuTable.cascadeBefore(command) != null) {
          timeFormatMenu = listedCascade(target, MenuTable.cascadeBefore(command));
          timeFormatGroup = group;
          new MenuItem(target, SWT.SEPARATOR);
        }
        final boolean radio = MenuTable.radio(command);
        MenuItem item =
            new MenuItem(
                nested(target, MenuTable.nestedCascade(command)),
                radio ? SWT.RADIO : command.check() ? SWT.CHECK : SWT.PUSH);
        item.setData(command);
        String keys = keymap.menuKeyText(command, mac);
        item.setText(keys.isEmpty() ? command.label() : command.label() + "\t" + keys);
        KeyStroke accelerator = keymap.accelerator(command);
        if (accelerator != null) {
          item.setAccelerator(SwtKeys.accelerator(accelerator));
        }
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                // The item a radio choice clears also fires.
                if (!radio || ((MenuItem) event.widget).getSelection()) {
                  controller.execute(command);
                }
              }
            });
        items.put(command, item);
        // Always on Top.
        if (command == CommandId.ALWAYS_ON_TOP && WindowStacking.PLATFORM.windowManagerMenu()) {
          item.setToolTipText(UiText.ALWAYS_ON_TOP_UNSUPPORTED.text());
        }
        if (command == CommandId.CLEAN_RECENT) {
          recentMenu = target;
          recentGroup = group;
        }
        // Recently Closed.
        if (command == CommandId.CLEAR_CLOSED) {
          closedMenu = target;
          closedGroup = group;
        }
        // Menu structure.
        if (command == CommandId.REMOVE_FAVORITE) {
          favoritesMenu = target;
          favoritesGroup = group;
        }
        UiText after = MenuTable.cascadeAfter(command);
        if (after == UiText.MENU_OPENED_FILES) {
          openedFilesMenu = listedCascade(target, after);
          openedFilesGroup = group;
        } else if (after == UiText.MENU_COLOR_LABEL) {
          colorLabelMenu = listedCascade(target, after);
          final Menu labels = colorLabelMenu;
          labels.addListener(
              SWT.Show,
              new Listener() {
                @Override
                public void handleEvent(Event event) {
                  ColorLabelMenu.fill(labels, controller, MainMenuBar.this.keymap, null);
                }
              });
        }
      }
      if (group == CommandGroup.ENCODING) {
        buildEncodings(menu);
      }
      // Menu item icons.
      icons.decorate(menu);
      menu.addListener(
          SWT.Show,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              sync(group);
            }
          });
    }
    shell.setMenuBar(bar);
    if (old != null) {
      old.dispose();
    }
    syncEnablement();
  }

  /** Adds a cascade whose items are filled from the controller's state. */
  private Menu listedCascade(Menu parent, UiText label) {
    MenuItem holder = new MenuItem(parent, SWT.CASCADE);
    holder.setText(label.text());
    Menu listed = new Menu(shell, SWT.DROP_DOWN);
    holder.setMenu(listed);
    return listed;
  }

  /** Returns the menu an item goes in: the cascade, created at its first item, or the target. */
  private Menu nested(Menu target, UiText cascade) {
    if (cascade == null) {
      return target;
    }
    if (nestedMenu == null) {
      MenuItem holder = new MenuItem(target, SWT.CASCADE);
      holder.setText(cascade.text());
      nestedMenu = new Menu(shell, SWT.DROP_DOWN);
      holder.setMenu(nestedMenu);
    }
    return nestedMenu;
  }

  /** Syncs every menu, as showing each would. */
  void syncAll() {
    for (CommandGroup group : menus.keySet()) {
      sync(group);
    }
  }

  /**
   * Syncs one top menu: its items' checked and enabled state and the entries it lists.
   *
   * @param group the menu's group
   */
  void sync(CommandGroup group) {
    for (Map.Entry<CommandId, MenuItem> entry : items.entrySet()) {
      CommandId command = entry.getKey();
      MenuItem item = entry.getValue();
      if (command.group() != group || item.isDisposed()) {
        continue;
      }
      setEnabled(item, states.enabled(command));
      if (command.check()) {
        item.setSelection(states.checked(command));
      }
    }
    if (group == openedFilesGroup) {
      syncOpenedFiles();
    }
    if (group == recentGroup) {
      syncRecent();
    }
    // Recently Closed.
    if (group == closedGroup) {
      syncClosed();
    }
    if (group == timeFormatGroup) {
      syncTimeFormats();
    }
    if (group == CommandGroup.HIGHLIGHT) {
      syncHighlightSets();
    }
    if (group == favoritesGroup) {
      syncFavorites();
    }
    if (group == CommandGroup.ENCODING) {
      syncEncodings();
    }
  }

  /** Enables every item from {@link CommandStates#enabled}. */
  void syncEnablement() {
    for (Map.Entry<CommandId, MenuItem> entry : items.entrySet()) {
      if (!entry.getValue().isDisposed()) {
        setEnabled(entry.getValue(), states.enabled(entry.getKey()));
      }
    }
  }

  private void setEnabled(MenuItem item, boolean enabled) {
    if (item.getEnabled() != enabled) {
      item.setEnabled(enabled);
      // Menu item icons.
      icons.showState(item);
    }
  }

  /** Lists the open files as radio items, the shown one checked; choosing one shows it. */
  private void syncOpenedFiles() {
    if (openedFilesMenu == null || openedFilesMenu.isDisposed()) {
      return;
    }
    for (MenuItem item : openedFilesMenu.getItems()) {
      item.dispose();
    }
    for (CommandStates.OpenedFile file : states.openedFiles()) {
      final DocumentController document = file.document();
      MenuItem item = new MenuItem(openedFilesMenu, SWT.RADIO);
      item.setText(file.text());
      item.setSelection(file.shown());
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (((MenuItem) event.widget).getSelection()) {
                controller.activate(document);
              }
            }
          });
    }
  }

  /**
   * Lists the timestamp formats as radio items, the shown file's checked: the built-ins, the user
   * formats, then None, each group after a separator. Choosing one applies it to the shown file.
   */
  private void syncTimeFormats() {
    if (timeFormatMenu == null || timeFormatMenu.isDisposed()) {
      return;
    }
    for (MenuItem item : timeFormatMenu.getItems()) {
      item.dispose();
    }
    boolean shown = controller.activeDocument() != null;
    boolean user = false;
    for (CommandStates.FormatChoice choice : states.timeFormats()) {
      final TimestampFormat format = choice.format();
      boolean userFormat = format instanceof UserFormat;
      if ((userFormat && !user) || format == null) {
        new MenuItem(timeFormatMenu, SWT.SEPARATOR);
      }
      user |= userFormat;
      MenuItem item = new MenuItem(timeFormatMenu, SWT.RADIO);
      item.setText(choice.label().replace("&", "&&"));
      item.setSelection(choice.chosen());
      item.setEnabled(shown);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              DocumentController document = controller.activeDocument();
              if (((MenuItem) event.widget).getSelection() && document != null) {
                controller.setTimeFormat(document, format);
              }
            }
          });
    }
  }

  /**
   * Returns View > Timestamps > Format.
   *
   * @return the cascade, filled when its top menu shows
   */
  Menu timeFormatMenu() {
    return timeFormatMenu;
  }

  /** Lists the highlighter sets after None as check items, the active ones checked. */
  private void syncHighlightSets() {
    Menu menu = menus.get(CommandGroup.HIGHLIGHT);
    MenuItem none = items.get(CommandId.NO_HIGHLIGHTERS);
    if (menu == null || menu.isDisposed() || none == null) {
      return;
    }
    for (MenuItem item : menu.getItems()) {
      if (item.getData() instanceof HighlightSet) {
        item.dispose();
      }
    }
    int at = menu.indexOf(none) + 1;
    for (CommandStates.SetChoice choice : states.highlightSets()) {
      final HighlightSet set = choice.set();
      MenuItem item = new MenuItem(menu, SWT.CHECK, at++);
      item.setText(set.name());
      item.setData(set);
      item.setSelection(choice.active());
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              controller.toggleHighlightSet(set.id());
            }
          });
    }
  }

  /** Lists the recent paths before Clean Up, or a disabled placeholder. */
  private void syncRecent() {
    if (recentMenu == null || recentMenu.isDisposed()) {
      return;
    }
    clearListed(recentMenu);
    List<String> recent = controller.preferences().state().recentFiles();
    int at = 0;
    for (String path : recent) {
      listedItem(recentMenu, at++, path);
    }
    if (recent.isEmpty()) {
      MenuItem none = new MenuItem(recentMenu, SWT.PUSH, at++);
      none.setText(UiText.RECENT_NONE.text());
      none.setEnabled(false);
      none.setData(LISTED);
    }
    new MenuItem(recentMenu, SWT.SEPARATOR, at).setData(LISTED);
  }

  // Recently Closed.

  /** Lists the recently closed paths before Open All, or a disabled placeholder. */
  private void syncClosed() {
    if (closedMenu == null || closedMenu.isDisposed()) {
      return;
    }
    clearListed(closedMenu);
    List<String> closed = controller.preferences().state().closedFiles();
    int at = 0;
    for (String path : closed) {
      listedItem(closedMenu, at++, path);
    }
    if (closed.isEmpty()) {
      MenuItem none = new MenuItem(closedMenu, SWT.PUSH, at++);
      none.setText(UiText.CLOSED_NONE.text());
      none.setEnabled(false);
      none.setData(LISTED);
    }
    new MenuItem(closedMenu, SWT.SEPARATOR, at).setData(LISTED);
  }

  /**
   * Returns File > Recently Closed.
   *
   * @return the cascade, filled when its top menu shows
   */
  Menu closedMenu() {
    return closedMenu;
  }

  /** Lists the favorites after Add and Remove, or a disabled placeholder. */
  private void syncFavorites() {
    Menu menu = favoritesMenu;
    if (menu == null || menu.isDisposed()) {
      return;
    }
    clearListed(menu);
    new MenuItem(menu, SWT.SEPARATOR).setData(LISTED);
    List<String> favorites = controller.preferences().options().favorites();
    for (String path : favorites) {
      listedItem(menu, menu.getItemCount(), path);
    }
    if (favorites.isEmpty()) {
      MenuItem none = new MenuItem(menu, SWT.PUSH);
      none.setText(UiText.FAVORITES_NONE.text());
      none.setEnabled(false);
      none.setData(LISTED);
    }
  }

  private static void clearListed(Menu menu) {
    for (MenuItem item : menu.getItems()) {
      if (LISTED.equals(item.getData()) || item.getData() instanceof Path) {
        item.dispose();
      }
    }
  }

  private void listedItem(Menu menu, int index, final String path) {
    MenuItem item = new MenuItem(menu, SWT.PUSH, index);
    item.setText(path.replace("&", "&&"));
    item.setData(LISTED);
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            controller.openListed(path);
          }
        });
  }

  /** Adds the common charsets after Auto, then Other, disabled until its charsets arrive. */
  private void buildEncodings(Menu menu) {
    new MenuItem(menu, SWT.SEPARATOR).setData(LISTED);
    for (Charset charset : EncodingChoices.commonCharsets()) {
      commonEncodingItems.add(encodingItem(menu, charset));
    }
    otherEncodings = new MenuItem(menu, SWT.CASCADE);
    otherEncodings.setText(UiText.MENU_ENCODING_OTHER.text());
    otherEncodings.setData(LISTED);
    otherEncodings.setEnabled(false);
    otherEncodings.setMenu(new Menu(shell, SWT.DROP_DOWN));
  }

  /**
   * Fills the Other cascade with every charset not listed above it and applies the current check.
   * Later calls for the same menu bar do nothing.
   *
   * @param choices the runtime's choices
   */
  void encodingsLoaded(EncodingChoices choices) {
    if (otherLoaded || otherEncodings == null || otherEncodings.isDisposed()) {
      return;
    }
    Menu all = otherEncodings.getMenu();
    for (Charset charset : choices.other()) {
      if (!encodingItems.containsKey(charset.name())) {
        encodingItem(all, charset);
      }
    }
    otherLoaded = true;
    syncEncodings();
  }

  private MenuItem encodingItem(Menu menu, final Charset charset) {
    MenuItem item = new MenuItem(menu, SWT.RADIO);
    item.setText(charset.name());
    item.setData(charset);
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            MenuItem chosen = (MenuItem) event.widget;
            DocumentController shown = controller.activeDocument();
            if (chosen.getSelection()) {
              selectedEncodings.add(chosen);
              if (shown != null) {
                controller.setEncoding(shown, charset);
              }
            }
          }
        });
    encodingItems.put(charset.name(), item);
    return item;
  }

  /**
   * Enables the charsets with Auto and checks the shown file's forced one. The Other cascade's
   * items stay enabled; the disabled cascade item keeps them out of reach.
   */
  private void syncEncodings() {
    if (otherEncodings == null || otherEncodings.isDisposed()) {
      return;
    }
    boolean enabled = states.enabled(CommandId.ENCODING_AUTO);
    for (MenuItem item : commonEncodingItems) {
      setEnabled(item, enabled);
    }
    setEnabled(otherEncodings, enabled && otherLoaded);
    String forced = states.forcedEncoding();
    MenuItem target = forced.isEmpty() ? null : encodingItems.get(forced);
    for (MenuItem item : selectedEncodings) {
      if (item != target && !item.isDisposed()) {
        item.setSelection(false);
      }
    }
    selectedEncodings.clear();
    if (target != null) {
      target.setSelection(true);
      selectedEncodings.add(target);
    }
  }

  /**
   * Returns Window > Opened Files.
   *
   * @return the cascade, filled when its top menu shows
   */
  Menu openedFilesMenu() {
    return openedFilesMenu;
  }

  /**
   * Returns the Encoding menu.
   *
   * @return the menu
   */
  Menu encodingMenu() {
    return menus.get(CommandGroup.ENCODING);
  }

  /**
   * Returns File > Favorites.
   *
   * @return the cascade, its listed entries filled when its top menu shows
   */
  Menu favoritesMenu() {
    return favoritesMenu;
  }

  /**
   * Returns Highlighters > Color Label.
   *
   * @return the cascade, filled when it shows
   */
  Menu colorLabelMenu() {
    return colorLabelMenu;
  }

  /**
   * Returns File > Recent Files.
   *
   * @return the cascade, filled when its top menu shows
   */
  Menu recentMenu() {
    return recentMenu;
  }
}
