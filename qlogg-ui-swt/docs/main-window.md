# Main window

`MainWindow` implements `MainView` and `ApplicationListener`. It holds the top
row, the tab row, the log views, the search row, the filtered view and the
status bar. Module overview: [../README.md](../README.md); the menu bar is in
[menus.md](menus.md) and the log view in [log-view.md](log-view.md).

## Windows and placement

Each main window is one `MainWindow`; the launcher creates more for New Window
and a restored session.

- `place` reads every monitor's bounds and work area and applies
  `WindowGeometry.place` for the shell trim the platform's `WindowPlacement`
  supplies: the `computeTrim` trim, and on win32 again with the trim measured
  from the sized shell, then inside the monitor's work area, so a window with
  no saved position (Windows picks one, such as 114,114) or a maximized start's
  restore bounds stays above the taskbar. It skips the position on GTK under the GDK backend
  `wayland` (`org.eclipse.swt.internal.gdk.backend`, a system property read
  that fails safe when absent).
- `open` shows the shell, maximized when `setMaximized(true)` came last, then
  calls `WindowPlacement.afterOpen`: GTK 4 drops a maximize requested before
  the shell maps, so `Gtk4WindowPlacement` maximizes again. On win32 a
  `setBounds` or `setSize` after `setMaximized(true)` on the hidden shell
  cancels the maximize (`Decorations.setBoundsInPixels`), so callers place the
  shell first.
- `windowBounds` and `windowMaximized` report the normal bounds kept from the
  last unmaximized move or resize, and the maximized state; both keep the
  state before [Full Screen](#full-screen).
- `SWT.Activate` reports the window to `ApplicationController.windowActivated`.
  `toFront` (unminimize, then `Shell.forceActive`) raises it for handed files.
- A close request from the window system is vetoed and passed to the handler
  given to `onCloseRequest`. The controller disposes the window when shutdown
  ends.
- The palette listener leaves the shared theme when the shell is disposed.
- Preferences, Keyboard Shortcuts, the highlighter, filter and history dialogs,
  text windows and Show Log, the user guide and the tool windows (Search in
  Folder, Search in Open Files, Marks List, Scratchpad) open at their remembered size and report it when they
  close (`dialog.<name>` in `qlogg.conf`).

## Top row

Left to right: a `ToolBar` with Open, Reload and Follow, a read-only path
field, a `ToolBar` with Stop, then the shown file's size, encoding, position
and caret time, each after a separator.

- Tool items carry `ToolIcons` images and a tooltip naming the command and its
  keys. Follow is a check item set from the shown document's follow state on
  every state change. Reload and Follow are disabled without a file, Stop
  without a tab.
- The size's tooltip is `Last modified` and the time, updated with the file.
  The encoding adds "(forced)" when the user chose it.
- The position field (`PositionText`) is at least 100 px wide and takes the
  width of a longer text, such as a selection's time difference; the path field
  gives up the difference down to its 160 px minimum, then the time field is
  cut. The selection part describes the filtered view's selection while that
  view has focus and a selection over more than one row, else the main view's.
- The time field (`TimeText`) shows the main view's caret time in the display
  zone with the offset, and is empty for a file without a format. Its tooltip
  names the format, the zone and the offset; a click runs Go to Time. Position
  and time update on the views' `timeChanged`.
- The path field shows the shown file's full path, or a clipboard document's
  tab name; with no file it is empty and disabled. Its context menu holds Copy
  Full Path, Copy File Name and Open Containing Folder (the Edit and File
  menus' commands, disabled for clipboard text), then Copy and Select All for the
  field's text.

## Tab row

The tab row under the top row (`TabStrip`, which `MainWindow` drives from its
`ApplicationListener` calls) is a `CTabFolder` with one closable tab per open
file: text `tabTitle` (the file name unless renamed), tooltip the path, data
its `DocumentController`. It shows while a file is open, one included, and is
excluded from the layout with none. Its height hint is 1 px, because the folder
adds its tab row as trim around the hint.

The items have no controls. The views below show the active tab:
`activeDocumentChanged` disposes them and creates them over the other
document's controllers, which hold the viewport, selection and rows.
`documentReplaced` points the tab at the reopened document's controller.

| Input | Effect |
|---|---|
| Select a tab | `activate` |
| Close button, middle click | `closeDocument`, the last tab included |
| Mouse wheel over the tab row | Selects the previous or next tab; see [Tab strip buttons](#tab-strip-buttons) |
| Drag onto another tab of the strip | Recreates the item at that position (a `CTabItem` cannot move) and calls `moveDocument` |
| Release off every tab of the strip | `dropTab`; see [Tab drag between windows](#tab-drag-between-windows) |
| Right click | `showTabMenu` |

Disposing the selected item makes `CTabFolder` select a neighbor and send
`SWT.Selection`; the tab listener ignores it during a move, so the dragged
document stays shown, and during `removeTab`, so the controller picks the tab
shown after a close. Pressing a tab selects it, so a dragged tab always ends
selected.

Closing the last tab leaves an empty window, as File > Close does. With
`tabs.closeWindowWithLastTab` on, `ApplicationController.closeDocument` and
`closeAll` then close the window, and on the last window quit through
`WindowSet.close`. Moving the last tab to another window leaves the source
window empty either way.

### Tab strip buttons

`TabStripButtons` is the folder's top-right control: Open (File > Open), the
tab list, and Close (File > Close, the current tab). Each is a `Canvas` painting
a `ToolIcons` image (`tab-new`, `tab-list-down`, `tab-close`) on the palette's
`gutter`, the strip's color, in the ink that color calls for. Tool tips and
accessible names come from the command titles with their keys and from
`UiText.TAB_LIST`; the buttons take no focus and have no default key.

- The list button shows the count of hidden tabs (`CTabItem.isShowing`), read on
  each strip `Paint`, capped at `99+`. Its tool tip reads "Show All Tabs (N
  hidden)".
- `TabList.show` opens one pop-up menu refilled per show: every tab in strip
  order as a radio item with the tab's text and dot image, the selected one
  checked, a separator on each side of the run the strip shows, and a repeated
  name followed by its folder. Choosing an item calls `TabList.choose`, which
  selects the tab and sends `SWT.Selection` as a tab click does. GTK 4 SWT shows
  no image on a radio menu item, so the dots show on GTK 3, win32 and cocoa
  only.
- `CTabFolder` has no public way to hide its chevron. A `CTabFolderRenderer`
  subclass draws nothing for `PART_CHEVRON_BUTTON`, and the chevron's
  `ToolBar` is held at zero width on every resize; the strip still reserves its
  width. A chevron request (`CTabFolder2Listener.showList`) opens `TabList`.
- A `SWT.MouseVerticalWheel` display filter in `TabStripButtons` takes a wheel
  event whose point lies over the tab row (the folder above its client area,
  or the whole folder when it holds no content) in the folder's shell,
  whatever control receives it, and cancels it.
  `TabWheel` adds the counts: each 3 units select the next tab (negative count)
  or the previous one through `TabList.choose`, a direction reversal restarts
  the sum, and a step past the first or last tab clears it without wrapping. A
  Win32 or GTK notch sends about 3; a cocoa trackpad gesture sends many counts
  of 1 or 2. Hit-testing in a filter makes hover alone work where the platform
  delivers the wheel to the focus control.
- `TabList.showSelection` repairs an SWT 3.135 defect: a selected new last tab
  that fits beside the run already shown keeps no width and stays hidden.
  Turning MRU mode on and off once rebuilds the show order.

The results strip (`ResultsPane`) carries the list button alone: 11 kept
searches of short patterns overflow it at 1100 px. Its context menu
(`ResultTabMenu`) holds Close, Close Others and Close All over the tabs that
show a close button, each through `ResultsPane.closeTab` (kept sets by identity
first, then the current search's tab), and Keep Results
(`TOGGLE_KEEP_RESULTS`), checked as the toggle is.

### Tab strip height

`TabStripHeight` lays the parent out again when a strip's height differs from
`computeSize` at its width and height hint. `CTabFolder` measures its top-right
control on a later UI turn (`updateFolder`, through `asyncExec`). Until then a
control at least as tall as the tab height counts as a wrapped row under the
tabs, so a strip filled and laid out in one turn (a rebuilt window, a results
strip created with its document) takes that row as trim. Once the folder
measures the control, the row becomes an empty client area under the tabs
(33 px at 150 % on win32) until the parent lays out again. Under GTK 3 the
strips keep their height without it (measured under Xvfb).

The tab menu is one menu refilled on each call: Close, Close Others, Close to
the Left, Close to the Right, Close All; Copy Full Path, Copy File Name, Open
Containing Folder; Rename Tab, Reset Tab Name; the Time Sync cascade
([menus.md](menus.md#time-sync-menus)), Move to New Window. The side items are
disabled at the ends, the path and name items for clipboard text.

### Ampersands in tab text

A `&` is doubled in every tab text and tab tooltip, document and result tabs
alike: `CTabItem` draws text with mnemonics, and SWT drops a single `&` from a
tooltip. `tabTitle` stays unescaped; each consumer escapes for its widget.
Result tabs take their text and tips already escaped from `ResultTabs`
(`qlogg-app`).

### Tab drag between windows

A tab drag released off the strip's tabs goes to `dropTab` with the display
point and the control under the pointer (`Display.getCursorControl`,
through a replaceable `PointerProbe`). The press grabs the pointer, so the
folder also gets a release outside the window. No `DragSource` is used.

| Release point | Effect |
|---|---|
| Over another main window (found through its shell's data) | `moveToWindow`: before the tab under the point, else last |
| No QLogg control under the pointer, outside this shell's bounds | `moveToNewWindow` opens a window with its corner at the point; GTK 4 and Wayland ignore the position |
| Anything else | Nothing |

`documentMovedOut` removes the tab and views as a close does;
`documentMovedIn` inserts the tab at its index.

### Tab dots

A tab whose `TabDataModel` state is not `NONE` shows a `TabDots` image: a dot
in the palette's `newLinesDot`, `newMatchesDot` or `followStoppedDot` color
with a darker rim. Its tooltip (`TabTips`) adds "New lines", "New matching
lines" or "Follow stopped by a highlighter" under the path, then "Time synced,
group N" for a time sync member; `timeSyncChanged` rebuilds every tab's.

`TabDots` renders each image per zoom through an `ImageDataProvider`. A
palette change creates the next palette's dots, sets them on every tab, then
disposes the old ones. A tab with no state has no image, so a dot widens its
tab. The folder shows images on unselected tabs
(`setUnselectedImageVisible`).

## Window body

The body is a vertical `SashForm`: the main log view, with the `OverviewBar`
between its text and its vertical scroll bar, over the bottom pane, split by
`view.splitPercent`. The sash is 7 px and paints a dotted grip. View > Overview
Bar shows or hides the bar. `+` and `-` move the split by 5 points between 10%
and 90%; View > Filtered View > Save Splitter Position stores it.

`MainWindow.fitSplit` gives the filtered view at least `MIN_FILTERED_ROWS` (3)
rows while the main view keeps one, on open, on resize, after a sash drag and
after a split change; the main view takes the rest. A window too short for
that gives the main view one row and the filtered view the remainder, at least
one row while the space allows. The minimum is computed from the search row,
the result strip and each view's line height and scroll bar trim, and is
applied as pixel weights. `splitPercent` returns the split as set (by
`view.splitPercent`, the saved split, `+`, `-` or a drag), not the applied
one, so a saved split applies again once the window has room.

The bottom pane holds the `SearchBar`, then the `ResultsPane`. Showing a tab
binds the `SearchBar` to its document.

### Search row

`SearchBar` is one full-width row: the filtered view's visibility combo; icon
toggles for Match case, Regex, Whole word, Inverse, Combine, In selection,
Auto-refresh and Range; the Predefined filters button; the pattern field (a
`Combo` whose list is the search history, at least 160 px); the range end
field, shown only while Range is on; two digit fields for the context lines
above and below each result (0 to 1,000); Clear, Search, the Keep results
toggle and Stop; and the search status. With no document the row is disabled.

- Each tooltip names the option and its key. The keys come from the
  controller's keymap from `attach` on and change with it
  (`SearchBar.setKeymap`).
- Enter searches and Escape stops.
- Clear empties the field and focuses it; the results stay until the next
  search or Search > Clear Results.
- Typing completes inline with the most recent history entry that extends the
  text, compared under the Match case rule. Backspace and Delete do not
  complete.
- Predefined filters is a push item whose icon carries the down arrow, drawn in
  the toolbar ink on every platform: win32 draws an `SWT.DROP_DOWN` item's
  arrow `#010101` whatever the toolbar's colors (1.36:1 on the dark row). A
  click opens a menu under it of the filters, each checked when its pattern
  stands in the field. Toggling one rewrites the field from the checked
  filters, switches to regex and searches.
- The field's context menu holds Save as Filter, Edit Search History and Clear
  Search History.
- The toggles follow `SearchController.options()`, which keys `4` to `9`
  change.

### Filtered view

`ResultsPane` is a full-width `CTabFolder` strip, aligned with the search row's
left edge, over a `LogCanvas` on the `FilteredController`. The strip holds the
current search and one closable tab per kept set, labeled with the text and
count; a range search's label shows the start and end expressions. Selecting a
tab shows that set; closing one removes it. The current search's tab shows a
close button while it holds a search; closing it runs `SearchController.clear`
and the tab stays.

The main view's canvas, the filtered view's canvas and the overview bar carry
accessible names through `AccessibleName` (`UiText.ACCESSIBLE_*`): Log,
Overview, and Filtered results, which adds the shown set's match count once a
search has run. A screen reader reads the name on request; no name-change event
is sent. On cocoa (`CocoaAccessibleLabels`) the name is also the
accessible description and the control reports `ACC.ROLE_GROUP`: VoiceOver
reads a `Canvas` without a role as `SWTComposite` with a blank description.
Unverified on a Mac.

### Quick find bar

`QuickFindBar` is the row above the status bar while quick find is open: the
field, Match case (the inverse of `QuickFindState.ignoreCase`), Incremental and
Regex (the `quickFind.*` preferences), Previous, Next, the last notice and
Close. Enter finds in the direction the bar was opened for (`'` forward, `"`
backward), Shift+Enter finds backward, and Escape in any of the bar's controls
closes it.

### Status bar

The status bar shows the document notes from `StatusText.notes` (index percent,
Following or Waiting to reopen, File missing, Loading stopped, the
reduced-index notice, a failure), a notice for 8 s (`showNotice`, with `&`
shown as itself) and the pending key prefix (`Ctrl+E -`).

It also holds the warning marker, an SWT `Link` hidden until
`showWarningMarker(true)`; clicking it runs Show Log. `showLog` opens one
`TextWindow` per main window at the first line with Close focused; a second
call replaces its text and activates it.

## Input and commands

- A Display key filter runs a window binding that is not a menu accelerator
  (`Keymap.windowCommand`), such as `Ctrl`+keypad `+`, while the event is for
  this window and no key sequence is pending.
- While a log view's two-stroke sequence is pending (the prefix listener has
  text), `AcceleratorHold` sets every accelerator of the menu bar and of
  `MenuBehavior.applicationMenuItems` to 0 and keeps each value; empty prefix
  text restores them. The menu takes a key before the focused control: without
  the hold `Ctrl+E Ctrl+,` opens Preferences (measured on GTK 3) and `Cmd+E
  Cmd+M` minimizes the window (measured on macOS). A menu bar built while held
  is cleared too (`menuBarBuilt`); a restore skips disposed items. Every
  accelerator is cleared, not only the colliding ones, because a shortcut
  override can give any window key to a command, and any stroke after the
  prefix belongs to the sequence: it completes or cancels it.
- `keymapChanged` rebuilds the menu bar, the tooltips and the shown views from
  a later UI task, because the event that changed the key bindings may come
  from a menu item or a view the rebuild disposes. The focused log view keeps
  the focus, and an open Keyboard Shortcuts window closes.
- `fontChanged` calls `LogCanvas.setFont` on both views, which remeasures and
  refits in place, so a zoom keeps the menu bar, the views and the focus.
  `SWT.ZoomChanged` (a DPI change) takes the same path.
- A `DropTarget` on the shell takes `FileTransfer`; each dropped path opens a
  tab through `ApplicationController.openDropped`, on a worker. `dragEnter`,
  `dragOver` and `dragOperationChanged` all set Copy, so a modifier pressed
  during the drag never returns Move, after which a file manager deletes the
  source. Where `FileTransfer.nativeToJava` returns the paths during the drag,
  `dragEnter` starts `ApplicationController.checkDragged` on a worker, and a
  drag of folders only gets no-drop. Expected on win32 and cocoa, where SWT
  reads the drag data on demand; GTK SWT receives it only for the drop, so a
  GTK drag of folders shows Copy and the drop shows the notice. Neither is
  measured: no posted drag between processes runs under Xvfb.

## Always on Top

`MainWindow.onTopSupported` and `setOnTop` pass to `WindowStacking.PLATFORM`,
the `PlatformTraits.stacking` implementation chosen on first use, after the
Display exists (GTK reads the backend the Display records). SWT has no public call, so each implementation
reaches SWT internals by reflection and the product compiles against every
platform's SWT jar. `ArchitectureTest.reflectionOnlyInExceptionClasses` allows
reflection in these three classes, `Gtk4ToolItemSizing`,
`Win32ComboListSelection`, and the two
foreign-function callers `Win32MenuThemes` and `WindowsForegroundRights` only;
`noSwtInternals` is unchanged.

| Platform | Class | Call | Verified |
|---|---|---|---|
| win32 | `Win32Stacking` | `OS.SetWindowPos` on `Control.handle` with `HWND_TOPMOST` or `HWND_NOTOPMOST`, no move, size or activation | CI test: `WS_EX_TOPMOST` each way |
| cocoa | `CocoaStacking` | `NSWindow.setLevel` on `Shell.window`, floating (3) or normal (0), also on the window's dialogs; `removeChildWindow`, `addChildWindow` and `orderFront` for Minimize | CI test: the window's level and a dialog's, each way; a tool window minimized without its owner |
| GTK 3 on X11 | `GtkX11Stacking` | `GTK3.gtk_window_set_keep_above` on `Shell.shellHandle` | Under Xvfb: `_NET_WM_STATE_ABOVE` set and cleared as the window maps |
| GTK 3 on Wayland, GTK 4 | `WindowManagerStacking` | None | Item disabled |

- A member that does not resolve, or a call that fails, logs one WARN and
  leaves the stacking unsupported (`PlatformStacking`); nothing throws to the
  caller, and the item is disabled at the next menu sync; on cocoa Window >
  Minimize is disabled too.
- `WindowStackingTest` fails on its platform when an SWT upgrade removes a
  reflected member.
- On a mapped GTK window the toggle is a request to the window manager. Xvfb
  has none, so a live toggle is unverified here.
- Dialogs stay above the window. On GTK a `Dialogs.shell` dialog is transient
  for the window (`WM_TRANSIENT_FOR`) and the search history list is an
  override-redirect popup, both measured under Xvfb. Windows keeps a topmost
  window's owned windows above it. On cocoa a Display `SWT.Show` filter in
  `CocoaStacking` raises each shell opened under a floating window, at any
  depth, to the floating level and returns it to normal with the window; an
  `SWT.ON_TOP` shell keeps its level. Native file and message dialogs are
  sheets or modal panels, which AppKit stacks itself.
- When the item is disabled at menu creation, `MainMenuBar` calls
  `WindowStacking.explainUnsupported`: on GTK (`WindowManagerStacking`, and a
  `GtkX11Stacking` whose members did not resolve) the item's tool tip names the
  window manager's window menu; win32 and cocoa do nothing. SWT keeps no menu
  item tool tip on GTK 4.

### Minimize

`CommandId.MINIMIZE` is a Window menu item on cocoa only; the command palette
runs it on every platform. It runs `MainWindow.minimize`, which calls
`WindowStacking.minimize` on the active shell when the window owns it at any
depth (`ActiveWindow.ownedBy`), else on the window; outside cocoa that is
`Shell.setMinimized(true)`, Always on Top supported or not. Menu sync enables
it from `MainWindow.minimizeSupported` (`WindowStacking.minimizeSupported`),
false only on cocoa while the stacking is unsupported, when
`CocoaStacking.minimize` does nothing. SWT makes a shell with a parent
an AppKit child window of it (`Shell.updateParent`), and AppKit takes the
parent off screen while a child window is miniaturized. `CocoaStacking.minimize`
removes a tool window from its owner's children (`NSWindow.removeChildWindow`)
before it miniaturizes and adds it back above the owner (`addChildWindow`) on
`SWT.Deiconify`, unless the owner is hidden or minimized then. The title bar's
button minimizes without `WindowStacking`: a Display `SWT.Iconify` filter in
`CocoaStacking`, added on the Display current when the stacking is created,
removes such a tool window from the children and brings the owner back
(`NSWindow.orderFront`); it rejoins on restore the same way. The button stays
enabled; while the stacking is unsupported the filter does nothing, and AppKit
takes the owner off screen with the tool window. `isVisible` stays
true for the hidden owner; `occlusionState` shows it. `WindowStackingTest`
checks both paths on the macOS CI runners
(`cocoaMinimizesAToolWindowWithoutItsOwner`,
`cocoaKeepsTheOwnerWhenTheTitleBarMinimizesAToolWindow` through
`performMiniaturize:`).

## Full Screen

View > Full Screen (`F11`; `Ctrl+Cmd+F` on macOS) calls
`MainView.setFullScreen` with the opposite of `fullScreen`; `MainWindow` passes
both to its `FullScreen`, one per window. The state is not saved. While full
screen, the bounds tracking skips every event, so `windowBounds` and
`windowMaximized` report the window before full screen, which `qlogg.conf`
`window.*` and the session's `window.W.*` save. `Esc` stays Stop Loading.

| | win32, GTK (`NativeFullScreen`) | cocoa (`CocoaFullScreen`) |
|---|---|---|
| State | The last request: set before `Shell.setFullScreen(true)` and cleared after `setFullScreen(false)`, so the size events win32 sends inside the call count as full screen | `Shell.getFullScreen`, which the title bar button also changes |
| Change | During the call on win32; a window manager request on GTK | An AppKit animation into a space of its own, after the call returns. AppKit ignores a change requested during the animation, so `CocoaFullScreen` holds a request made within `SETTLE_MS` (1 s) of the previous change until then, makes it again while the shell does not show it (at most `RETRIES`, 5), all through the UI dispatcher's timer, and the check item shows the held request |
| Menu bar | Stays in the window | The system menu bar, shown when the pointer reaches the top edge |

SWT's `Shell.setBounds` and `setSize` leave full screen on cocoa and GTK, so
QLogg calls neither on a main shell after it opens: `place`, the command-line
size, a session window's saved bounds and the maximized state apply before
`open`, and Move to New Window places a new shell.

## Platform traits

`PlatformTraits` holds the view layer's platform differences, answered by
`Win32Traits`, `CocoaTraits`, `Gtk3Traits` and `Gtk4Traits`; the GTK pair share
`GtkTraits`. A trait is either a value a caller passes on as data, or the
factory of a seam: an interface with one class per platform, which callers call
on every platform without checking the platform first. Everything lives in
package `ui.swt.platform`; the traits classes and the per-platform classes are
package-private. `LoadedToolkit` chooses `PlatformTraits.PLATFORM` when the
interface is first used, from `SWT.getPlatform()` and, on GTK, the major
version of the GTK that SWT loaded: it initializes the `Display` class, which
loads SWT's GTK library, then reads `org.eclipse.swt.internal.gtk.version`. SWT
loads GTK 3 when `SWT_GTK4=1` is set and GTK 4 fails to load; QLogg then uses
the GTK 3 traits and logs one WARN. Without `SWT_GTK4`, SWT loads GTK 4 when
GTK 3 fails to load, and QLogg uses the GTK 4 traits with no WARN. When neither
loads, `LoadedToolkit` logs the failure at DEBUG and chooses the GTK 3 traits,
and `Gui` exits 3.

`MenuBehavior`, `WindowPlacement`, `LaunchDocuments`, `ToolItemSizing`,
`ComboListSelection` and `WindowStacking` hold the running platform's instance as `PLATFORM`.

`PlatformTraits.of` builds any platform's traits, so `PlatformTraitsTest` checks
every value and seam class on Linux and fails on a boolean `PlatformTraits`
method other than a value trait; `PlatformSeamsTest` and `WindowPlacementTest`
construct each platform's seam classes directly. `ArchitectureTest.platformOnlyInTraits` fails
on a `SWT.getPlatform()` call, a `SWT_GTK4` string or a string starting
`org.eclipse.swt.internal.` outside `ui.swt.platform`, and on a `System.getenv`
call in `ui.swt` outside that package or in the launcher.
`ArchitectureTest.behaviorOnlyThroughSeams` fails on a call to a boolean
`PlatformTraits` or `OsTraits` method outside the two platform packages, except
the value traits `macKeys`, `macKeymap`, `takesCtrlShiftZero`,
`runsInsteadOfOpening`, `keepFileClosedByDefault` and `watchServiceByDefault`.

`platformQueriesOnlyInThePlatformPackage` fails on a call outside
`ui.swt.platform` of `Display.getSystemMenu`, `getSystemTray`,
`getSystemTaskBar`, `isSystemDarkTheme` or `Program.findProgram`;
`SwtThemeService` reads the system setting through `NativeTheme.systemDark` and
`MainWindow.launchAsText` finds the text program through `NativePrograms.text`.
`valueTraitsOnlyInReviewedCallers` lists the callers of each value trait
(`VALUE_TRAIT_CALLERS`: `SwtKeys`, `Keymap`, `PathCommands`) and fails on any
other. ArchUnit sees the call, not the condition around it, so a new caller is
reviewed before it is added: one that passes the value on stays, and one that
would choose platform code calls a seam instead.

### Values

| Method | win32 | cocoa | GTK 3 | GTK 4 | Caller |
|---|---|---|---|---|---|
| `name` | `win32` | `cocoa` | `gtk` | `gtk` | `MainWindow.toolkitDescription` |
| `toolkitVersion` | empty | empty | `GTK` and `org.eclipse.swt.internal.gtk.version` | as GTK 3 | `MainWindow.toolkitDescription` |
| `monospaceFamily`, `monospaceSize` | Consolas 10 | Menlo 12 | Monospace 10 | Monospace 10 | `Dialogs.monospaceName`, `monospaceSize` |
| `macKeys` | - | yes | - | - | `SwtKeys.mac`: key names and the keymap's platform |
| `busyCursor` | `CURSOR_APPSTARTING` | `CURSOR_WAIT` | `CURSOR_APPSTARTING` | `CURSOR_APPSTARTING` | `WindowBusyCursor` |
| `shownBackground` | `getBackground` | `getBackground` | a drawn pixel of a visible control | as GTK 3 | `ToolIcons.reink` |

### Seams

| Interface (factory) | win32 | cocoa | GTK 3 | GTK 4 | Caller |
|---|---|---|---|---|---|
| `ThemeFollow` (`themeFollow`) | `Win32ThemeFollow`: sets the native theme at startup in every mode; polls the system setting every 500 ms; `showOnWindows` sets a changed side and rebuilds the windows | `CocoaThemeFollow`: pins the theme at startup in every mode; polls; `showOnWindows` pins a changed side and `CocoaChrome` recolors shells | `GtkThemeFollow`: sets the theme at startup for Light and Dark and again at each switch; `showOnWindows` does nothing; `colorLink` and `settingsChanged` raise links to 4.5:1 | as GTK 3 | `SwtThemeService` ([theme.md](theme.md#native-widget-theme)) |
| `FullScreen` (`fullScreen`) | `NativeFullScreen` | `CocoaFullScreen` | `NativeFullScreen` | `NativeFullScreen` | `MainWindow` ([Full Screen](#full-screen)) |
| `MenuBehavior` (`menuBehavior`) | `Win32MenuBehavior`: icons, fixed ink of the native side the Display started with | `CocoaMenuBehavior`: no icons; the application menu; the owner's menus on tool windows | `Gtk3MenuBehavior`: icons, blank images, live ink, faded disabled copy | `Gtk4MenuBehavior`: no icons | `MenuIcons`, `ToolIcons`, `MainMenuBar`, `AppMenu`, `Dialogs.shell` ([menus.md](menus.md)) |
| `LaunchDocuments` (`launchDocuments`) | `NativeLaunchDocuments`: no wait | `CocoaLaunchDocuments`: runs the `OpenDocument` wait | no wait | no wait | `OpenDocuments.await` |
| `AccessibleLabels` (`accessibleLabels`) | `NativeAccessibleLabels`: nothing | `CocoaAccessibleLabels`: description and group role | nothing | nothing | `AccessibleName.attach` |
| `WindowPlacement` (`windowPlacement`) | `Win32WindowPlacement`: places again with the measured trim, then fits the shell into its monitor's work area (`WindowGeometry.fit`), since a placement without a location keeps the position Windows chose | `NativeWindowPlacement` | `GtkWindowPlacement`: no position under Wayland | `Gtk4WindowPlacement`: as GTK 3; `afterOpen` maximizes a shell that should be maximized and opened restored | `MainWindow.place`, `MainWindow.open` |
| `GuideDisplay` (`guideDisplay`) | `BrowserGuideDisplay` | `BrowserGuideDisplay` | `BrowserGuideDisplay` | `NoticeGuideDisplay`: a notice names the guide's file, since WebKitGTK 6.0 aborts the JVM under GTK 4 | `MainWindow.showGuide` |
| `DialogColors` (`dialogColors`) | `Win32DialogColors`: dark surfaces, drawn grid lines and dark header dividers after a dark start, native otherwise | `NativeDialogColors` | `NativeDialogColors` | `NativeDialogColors` | `DialogColors.platform`: `Dialogs` |
| `DialogTabs` (`dialogTabs`) | `Win32DialogTabs` after a dark start: a `CTabFolder` in the dialog colors; else `NativeDialogTabs` | `CocoaDialogTabs`: the native `TabFolder`, keeping keyboard focus on a control across a tab change | `NativeDialogTabs` | `NativeDialogTabs` | `Dialogs.tabs`: Preferences, Configure Highlighters ([theme.md](theme.md#dialog-colors)) |
| `CellImages` (`cellImages`) | `Win32CellImages`: draws the image in `SWT.PaintItem`, its first opaque column 3 pt into the cell and centered in the pixel rows above the rule or grid line, on a row pitch of whole pixels that is the same with and without grid lines, since an item image gives column 0 an empty image slot in every row | `NativeCellImages`: `TableItem.setImage` | `NativeCellImages`; owner drawing would draw the cell text in the list color GTK reported at startup | `NativeCellImages` | `FiltersDialog`: the Regex check boxes |
| `ChromeColors` (`chromeColors`) | `Win32ChromeColors` | `NativeChromeColors` | `NativeChromeColors` | `NativeChromeColors` | `MainWindow`: the window's rows and fields under a dark theme ([theme.md](theme.md#chrome-colors)) |
| `WindowStacking` (`stacking`) | `Win32Stacking` | `CocoaStacking` | `GtkX11Stacking` on X11, else `WindowManagerStacking` | `WindowManagerStacking` | `MainWindow` (Always on Top, Minimize), `MainMenuBar` (tool tip) |
| `ToolItemSizing` (`toolItemSizing`) | `NativeToolItemSizing` | `NativeToolItemSizing` | `NativeToolItemSizing` | `Gtk4ToolItemSizing` | `ToolIcons` |
| `ComboListSelection` (`comboListSelection`) | `Win32ComboListSelection`: paints a drop-down combo's open list selection under the dark native setting | `NativeComboListSelection` | `NativeComboListSelection` | `NativeComboListSelection` | every `Combo` at creation ([theme.md](theme.md#combo-list-selection)) |

## Platform services

| Method | Implementation |
|---|---|
| `clipboardText` | Reads the text of the `TextTransfer` clipboard, which is created on first use and disposed with the shell |
| `setText` | Writes the text to that clipboard; an `SWTError` from `setContents` gets one retry, and a second shows the Copy failed notice and logs one WARN |
| `launch` | `Program.launch` on a folder or file: the desktop's file association (GIO on GTK, `ShellExecuteEx` on Windows), no shell |
| `launchAsText` | `NativePrograms.text` (`Program.findProgram("txt")`), then `Program.execute` on the file: the plain-text application started with the file as its argument (`CreateProcess` on Windows, GIO on GTK, LaunchServices on macOS), no shell; `NO_PROGRAM` when `.txt` has no association |
| `system` | `OsTraits.current()` |
| `launchUrl` | `Program.launch` on the fixed issue or project page |
| `setActive`, `setIdle` | A `BusyCounter` driving `WindowBusyCursor`; see [Busy cursor](#busy-cursor) |

## Busy cursor

`setActive` and `setIdle` count overlapping operations in a `BusyCounter`. In
the launcher the count lives in the window's `MainWindowSlot`, so it survives a
[window rebuild](theme.md#window-rebuild) and the new window takes the cursor.
While any is active, `WindowBusyCursor` sets `PlatformTraits.busyCursor` on the
main shell and on every shell it owns at any depth (`Shell.getShells`): the
dialogs and tool windows from `Dialogs.shell`. The busy cursor keeps the window
clickable; cocoa uses `CURSOR_WAIT` because SWT maps `CURSOR_APPSTARTING` to a
plain arrow there (read in the SWT 3.135.0 sources, not run on a Mac). While
busy, a Display `SWT.Show` filter sets the cursor on an owned shell that opens;
idle removes the filter and clears each shell's cursor. A control with a
cursor of its own keeps it (the time field's hand). Disposed shells are
skipped, and the system cursor is not disposed. A running Search in Folder holds a
count, so the busy cursor shows on the main window and the Search in Folder
window until the search ends, is stopped or the window closes.
