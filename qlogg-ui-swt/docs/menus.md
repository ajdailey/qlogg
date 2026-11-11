# Menus

`MainMenuBar` builds a window's menu bar; `TimeSyncMenu` fills the Time Sync
cascades and `AppMenu` connects the macOS application menu. Command order,
labels and checked and enabled state come from `qlogg-app` (`CommandId`,
`MenuTable`, `CommandStates`); these classes only apply them. Module overview:
[../README.md](../README.md).

## Menu bar

`MainWindow.attach(controller)` creates the `MainMenuBar` from the controller's
`keymap()`: one menu per `CommandGroup` (File, Edit, View, Tools,
Highlighters, Encoding, Favorites, Help), items in `MenuTable.commands` order.
Consecutive commands with the same `submenu()` form one cascade, Edit > Search.

- Each item's text shows all its keys. Only a `WINDOW` binding becomes an SWT
  accelerator, so `LOG_VIEW` keys such as `Ctrl+C` reach the log view and never
  a text field.
- A `check()` command is a check item.
- Showing a top menu syncs that menu's items and listed entries, no other
  menu's.
- Every item's enablement is also applied on each document open, activation,
  replacement and close (`syncEnablement`), because a disabled item's
  accelerator does not fire.
- `MainWindow.syncMenu` syncs every menu; `MainWindow` also keeps the menu
  accessors the tests use.

## Listed entries

| Menu | Entries |
|---|---|
| Highlighters | The sets after None, as check items |
| View > Opened Files | The open files as radio items; choosing one shows its tab |
| File > Open Recent | The recent paths before Open All and Clean Up, refilled on show |
| File > Recently Closed | The closed paths before Open All and Clear, refilled on show; choosing one opens it |
| Favorites | The favorites after Add to Favorites and Remove from Favorites, refilled on show |
| Encoding | Auto, the common charsets as radio items, then Other with every other charset the runtime supports (`EncodingChoices`) |
| View > Timestamps > Format | Filled on show from `CommandStates.timeFormats`: the built-ins, the user formats and None as radio items, the shown file's checked |
| View > Timestamps > Time Spread | The `MenuTable.radio` commands, in the cascade `MenuTable.nestedCascade` names |

A `&` in a path or title is doubled so it shows.

Reload and the Edit menu's path commands are disabled for text opened from the
clipboard.

### Encoding menu

The common items are created with the menu bar. Other stays empty and
disabled until `ApplicationController.loadEncodingChoices` delivers the full
list from a worker; `encodingsLoaded` then fills it once. A show sets the
items' enablement and checks the shown file's forced charset, else Auto. The
items under Other stay enabled; the disabled Other item keeps them out of
reach. Each show keeps the same item objects.

### Radio cascades

The Time Spread listeners ignore the event of the item a choice clears.

## Time Sync menus

`TimeSyncMenu` fills the Time Sync cascade of the tab menu (built with the
menu; the rebuild disposes the old cascade) and of the log-view menu (on
`SWT.Show`) from `TimeSyncGroups.entries`: a check item per other tab with a
format, or a disabled line when there is none, then Leave Time Sync, enabled
in a group. `&` in a title is escaped.

## macOS application menu

`AppMenu` connects the application menu when `SWT.getPlatform()` is `cocoa`
and `Display.getSystemMenu()` is not null; elsewhere `present` is false and
the menu bar keeps About, Options and Exit.

| Event | Action |
|---|---|
| About, Preferences, Quit | `ABOUT`, `OPTIONS` and `QUIT` in the window activated last |
| Display `SWT.Close` (Cmd-Q, logout) | Vetoed; runs `WindowSet.quit` |
| `SWT.OpenDocument` | The file goes to `WindowSet.openHandoff` |
