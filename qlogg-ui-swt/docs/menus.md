# Menus

`MainMenuBar` builds a window's menu bar; `TimeSyncMenu` fills the Time Sync
cascades, `ColorLabelMenu` the Color Label cascades, and `AppMenu` connects the
macOS application menu. Command order,
labels and checked and enabled state come from `qlogg-app` (`CommandId`,
`MenuTable`, `CommandStates`); these classes only apply them. Module overview:
[../README.md](../README.md).

## Menu bar

`MainWindow.attach(controller)` creates the `MainMenuBar` from the controller's
`keymap()`: one menu per `CommandGroup` (File, Edit, Search, Go, View,
Highlighters, Encoding, Tools, Window, Help), items in `MenuTable.commands`
order. Consecutive commands with the same `submenu()` form one cascade (File >
Favorites, Search > Search Options, View > Folding).

- Each item's text shows one key after the tab, `Keymap.menuKeyText`: the
  command's first single-stroke binding, none when it has only sequences. GTK 3
  and GTK 4 parse that text as one accelerator, so a list (`F3, N, Ctrl+G`)
  shows another command's key (`G`) and a sequence (`Ctrl+E Ctrl+T`) its second
  stroke alone (measured under Xvfb). Tooltips, Keyboard Shortcuts and the
  Preferences shortcut editor list every key. The log view's context menu
  follows the same rule.
- Only a `WINDOW` binding becomes an SWT accelerator, so `LOG_VIEW` keys such
  as `Ctrl+C` reach the log view and never a text field.
- Every accelerator is 0 while a log view's key sequence is pending
  (`AcceleratorHold`, [main-window.md](main-window.md#input-and-commands)).
  The default second strokes that are also window keys: `Ctrl+,` (Preferences)
  on every platform and `Cmd+M` (Minimize) on macOS.
- A `check()` command is a check item, or a radio item when it is in
  `MenuTable.RADIO`: View > Theme, View > Filtered View's modes, Encoding >
  Auto and Time Spread.
- View > Syntax Colors is a check item without a shortcut; it sets
  `view.syntaxColors` for both views of every open file.
- Every item of a menu, cascade or context menu has a mnemonic of its own;
  listed entries, the placeholders of empty lists and Wrap Line have none.
  `MenuTableTest` checks the menu bar and the context menu tables without SWT;
  `LogViewTest.mnemonicsAreUniqueInEveryMenu` walks the built menu bar and the
  log view, tab, path field and search field context menus.
- The context menus build from `MenuTable` tables: `LOG_VIEW_MENU` and
  `LOG_VIEW_CASCADES` (`LogCanvas`), `TAB_MENU` (`TabStrip.showTabMenu`),
  `PATH_MENU` (`MainWindow.pathMenu`), `SEARCH_FIELD_MENU` (`SearchBar`) and
  `RESULT_TAB_MENU` (`ResultTabMenu`, a results tab's Close, Close Others and
  Close All, then Keep Results as a check item).
- Showing a top menu syncs that menu's items and listed entries, no other
  menu's.
- `syncEnablement` applies every item's enablement on each document open,
  activation, replacement and close, on the shown document's
  `navigationChanged`, and on shell activation (another window may have
  changed Recently Closed). A disabled item's accelerator does not fire.
- `MainWindow.syncMenu` syncs every menu; `MainWindow` also keeps the menu
  accessors the tests use.

## Menu item icons

`MenuIcons` shows icons on the push items of the menu bar and of the log
view's context menu. `MenuIcons.icon` maps each command to a `ToolIcons` name:

| Menu | Items |
|---|---|
| File | Open, Close, Exit |
| Edit | Copy, Select All |
| Search | Find, Find Next, Find Previous, Search in Folder |
| Go | Go to Line |
| View | Reload, Stop Loading |
| Tools | Preferences |
| Window | New Window |
| Help | User Guide, About QLogg |
| Log view context menu | Copy |

- Check, radio, cascade and listed items show no icon, so Follow has none.
- `MainMenuBar.build` and `LogCanvas` call `decorate` on each built menu, which
  covers its cascades. `MainMenuBar.setEnabled` calls `showState` after each
  enablement change.
- The platform's `MenuBehavior` holds the platform differences:

| `MenuBehavior` | win32 | GTK 3 | GTK 4 | cocoa |
|---|---|---|---|---|
| `decorate`: icons | yes | yes | no: SWT ignores `MenuItem.setImage` | no |
| `decorate`: blank images | no: the menu reserves the image column | yes | - | - |
| `ink` | fixed: the native side the Display started with | live | live | live |
| `disabledOnDark` | the icon | a faded copy | a faded copy | the icon |
| `toolWindowMenus` | nothing | nothing | nothing | the owner's menus on a tool window |
| `systemCommands` | Minimize, Zoom | Minimize, Zoom | Minimize, Zoom | About, Preferences, Quit |
| `hookAppMenu` | nothing | nothing | nothing | connects the system application menu |
| `applicationMenuItems` | none | none | none | the system menu's items (Preferences `Cmd+,`, Hide, Quit) |

`Win32MenuBehavior` and `Gtk3MenuBehavior` extend `ImageMenuBehavior`, which
decorates the menus and leaves the cocoa-only methods empty; each subclass
supplies the blank-image alignment, ink and disabled image.

- On GTK 3 a label moves right when its item has an image, so every other item
  of a menu with an icon holds `ToolIcons.blank`, a transparent 16 px image. A
  menu without an icon stays bare.
- The ink follows the main toolbar's shown background on GTK
  (`PlatformTraits.shownBackground`, [theme.md](theme.md)), including a live
  theme switch: `MainWindow` calls `MenuIcons.reink` beside the toolbar's
  `reink` on the UI turn after a palette change. win32 pop-up menus show the
  native side of the last menu theme flush, so their icons take that side's
  ink (`Win32MenuThemes.shownDark`, the start side until a flush), while the
  toolbar takes `ChromeColors` from the live theme. The icons measured
  10.80:1 (`#3a3a3a` on `#f9f9f9`) in light menus and 9.42:1 (`#d4d4d4` on
  `#2c2c2c`) in dark menus, at a fresh start and after each live switch
  (Windows, 150%).

## Listed entries

| Menu | Entries |
|---|---|
| Highlighters | The sets after None, as check items |
| Highlighters > Color Label | Filled on show: None, then the label names with their keys; acts on the focused view |
| Window > Opened Files | The open files as radio items; choosing one shows its tab |
| File > Recent Files | The recent paths before Open All, Clean Up and Clear Recent Files, refilled on show |
| File > Recently Closed | The closed paths before Open All and Clear Recently Closed, refilled on show; choosing one opens it, as a rotated set when it closed as one (`openClosed`) |
| File > Favorites | The favorites after Add to Favorites and Remove from Favorites, refilled on show |
| Encoding | Auto, the common charsets as radio items, then Other with every other charset the runtime can encode with (`EncodingChoices`) |
| View > Timestamps > Format | Filled on show from `CommandStates.timeFormats`: the built-ins, the user formats and None as radio items, the shown file's checked |
| View > Timestamps > Time Spread | The `MenuTable.radio` commands, in the cascade `MenuTable.nestedCascade` names |

A `&` in a path or title is doubled so it shows.

Reload and the path commands are disabled for text opened from the clipboard.

### Encoding menu

The common items are created with the menu bar. Other stays empty and
disabled until `ApplicationController.loadEncodingChoices` delivers the full
list from a worker; `encodingsLoaded` then fills it once. A show sets the
items' enablement and checks the shown file's forced charset, else Auto. The
items under Other stay enabled; the disabled Other item keeps them out of
reach. Each show keeps the same item objects.

### Radio items

SWT sends `Selection` to the radio item a choice clears as well as the one it
selects; the listener runs the command only for the selected item. A
separator ends a radio group, so Encoding > Auto is a group of its own; each
show syncs it with the charsets.

## Time Sync menus

`TimeSyncMenu` fills the Time Sync cascade of the tab menu (built with the
menu; the rebuild disposes the old cascade) and of the log-view menu (on
`SWT.Show`) from `TimeSyncGroups.entries`: a check item per other tab with a
format, or a disabled line when there is none, then Leave Time Sync, enabled
in a group. `&` in a title is escaped.

## macOS application menu

`AppMenu.hook` calls `MenuBehavior.hookAppMenu` on every platform:
`CocoaMenuBehavior` connects the system application menu, and the win32 and GTK
classes do nothing. `MainMenuBar` passes `MenuBehavior.systemCommands` to
`MenuTable.commands`, which leaves those commands out: About, Preferences and
Quit on cocoa when SWT supplies the system menu; elsewhere Minimize and Zoom
(`MenuTable.TITLE_BAR_COMMANDS`), so the menu bar keeps About, Preferences and
Exit.

| Event | Action |
|---|---|
| About, Preferences, Quit | `ABOUT`, `PREFERENCES` and `QUIT` in the window activated last |
| Display `SWT.Close` (Cmd-Q, logout) | Vetoed; runs `WindowSet.quit` |
| `SWT.OpenDocument` | The file goes to `WindowSet.openHandoff` |

On cocoa the Window menu starts with Minimize (`CommandId.MINIMIZE`, `Cmd+M`)
and Zoom (`CommandId.ZOOM_WINDOW`, no key, toggles `Shell.setMaximized`), then
a separator (New Window's `separatorBefore`); SWT adds neither to an
application-defined Window menu. Elsewhere the menu leaves both out and the
command palette runs them. Both act on the active shell the window owns
(`ActiveWindow.ownedBy`), so on a tool window they act on the tool window, and
minimizing it leaves the main window on screen. Minimize is disabled only while
the macOS window calls are unavailable ([Minimize](main-window.md#minimize)).

## Tool window menus

The cocoa menu bar shows the key window's menus, and a window without a menu
bar shows only the application menu. `Dialogs.shell` therefore calls
`MenuBehavior.toolWindowMenus` for every modeless shell (Preferences, Marks
List, Scratchpad, Keyboard Shortcuts, Search in Open Files, Search in Folder,
the user guide, a modeless `TextWindow` such as Show Log); on cocoa it builds
the owner's menus there through `ToolMenuBars`, which the main window records
on its shell (`ToolMenuBars.KEY`, found on the owner or an owner's owner). Each
tool window gets its own
`MainMenuBar` over the main window's controller, so File, View and the
accelerators act on that main window, except File > Close (`Cmd+W`), which
closes the tool window and stays enabled (`MainMenuBar.closesToolWindow`). `MainWindow` rebuilds these bars with its
own on a keymap change, fills their Encoding > Other cascade and syncs their
enablement with its own. A modal dialog gets none. The win32 and GTK classes do
nothing, since a menu bar draws inside its window there.
