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
- A `check()` command is a check item, or a radio item when it is in
  `MenuTable.RADIO`: View > Theme, View > Filtered View's modes, Encoding >
  Auto and Time Spread.
- Every item of a menu, cascade or context menu has a mnemonic of its own;
  listed entries and the placeholders of empty lists have none.
  `MenuTableTest` checks the menu bar and the context menu tables without SWT;
  `LogViewTest.mnemonicsAreUniqueInEveryMenu` walks the built menu bar and the
  log view, tab, path field and search field context menus.
- The context menus build from `MenuTable` tables: `LOG_VIEW_MENU` and
  `LOG_VIEW_CASCADES` (`LogCanvas`), `TAB_MENU` (`TabStrip.showTabMenu`),
  `PATH_MENU` (`MainWindow.pathMenu`) and `SEARCH_FIELD_MENU` (`SearchBar`).
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
- `PlatformTraits` holds the platform differences:

| Trait | win32 | GTK 3 | GTK 4 | cocoa |
|---|---|---|---|---|
| `menuImages` | yes | yes | no: SWT ignores `MenuItem.setImage` | no |
| `padsMenuImages` | no: the menu reserves the image column | yes | - | - |
| `fixedMenuInk` | yes | no | - | - |
| `fadesDisabledIcons` | no | yes | - | - |

- On GTK 3 a label moves right when its item has an image, so every other item
  of a menu with an icon holds `ToolIcons.blank`, a transparent 16 px image. A
  menu without an icon stays bare.
- The ink follows the main toolbar's shown background on GTK
  (`PlatformTraits.shownBackground`, [theme.md](theme.md)), including a live
  theme switch: `MainWindow` calls `MenuIcons.reink` beside the toolbar's
  `reink` on the UI turn after a palette change. win32 menus keep the startup dark setting
  (`SwtThemeService.nativeDark`), and the toolbar there stays light under it.

## Listed entries

| Menu | Entries |
|---|---|
| Highlighters | The sets after None, as check items |
| Highlighters > Color Label | Filled on show: None, then the label names with their keys; acts on the focused view |
| Window > Opened Files | The open files as radio items; choosing one shows its tab |
| File > Recent Files | The recent paths before Open All, Clean Up and Clear Recent Files, refilled on show |
| File > Recently Closed | The closed paths before Open All and Clear Recently Closed, refilled on show; choosing one opens it |
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

`AppMenu` connects the application menu when `PlatformTraits.systemAppMenu`
is true (cocoa) and `Display.getSystemMenu()` is not null; elsewhere `present` is false and
the menu bar keeps About, Preferences and Exit.

| Event | Action |
|---|---|
| About, Preferences, Quit | `ABOUT`, `PREFERENCES` and `QUIT` in the window activated last |
| Display `SWT.Close` (Cmd-Q, logout) | Vetoed; runs `WindowSet.quit` |
| `SWT.OpenDocument` | The file goes to `WindowSet.openHandoff` |
