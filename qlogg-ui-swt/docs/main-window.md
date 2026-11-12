# Main window

`MainWindow` implements `MainView` and `ApplicationListener`. It holds the top
row, the tab row, the log views, the search row, the filtered view and the
status bar. Module overview: [../README.md](../README.md); the menu bar is in
[menus.md](menus.md) and the log view in [log-view.md](log-view.md).

## Windows and placement

Each main window is one `MainWindow`; the launcher creates more for New Window
and a restored session.

- `place` reads every monitor's bounds and work area and the shell trim
  (`computeTrim`), and applies `WindowGeometry.place`. It skips the position
  when SWT reports the GDK backend `wayland`
  (`org.eclipse.swt.internal.gdk.backend`, a system property read that fails
  safe when absent).
- `windowBounds` and `windowMaximized` report the normal bounds kept from the
  last unmaximized move or resize, and the maximized state.
- `SWT.Activate` reports the window to `ApplicationController.windowActivated`.
  `toFront` (unminimize, then `Shell.forceActive`) raises it for handed files.
- A close request from the window system is vetoed and passed to the handler
  given to `onCloseRequest`. The controller disposes the window when shutdown
  ends.
- The palette listener leaves the shared theme when the shell is disposed.
- Options, Keyboard Shortcuts, the highlighter, filter and history dialogs and
  text windows open at their remembered size and report it when they close.

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
  Full Path, Copy File Name and Open Containing Folder (the Edit menu's
  commands, disabled for clipboard text), then Copy and Select All for the
  field's text.

## Tab row

The tab row under the top row is a `CTabFolder` with one closable tab per open
file: text `tabTitle` (the file name unless renamed), tooltip the path, data
its `DocumentController`. It shows while two or more files are open and is
excluded from the layout otherwise. Its height hint is 1 px, because the
folder adds its tab row as trim around the hint.

The items have no controls. The views below show the active tab:
`activeDocumentChanged` disposes them and creates them over the other
document's controllers, which hold the viewport, selection and rows.
`documentReplaced` points the tab at the reopened document's controller.

| Input | Effect |
|---|---|
| Select a tab | `activate` |
| Close button, middle click | `closeDocument` |
| Drag onto another tab of the strip | Recreates the item at that position (a `CTabItem` cannot move) and calls `moveDocument` |
| Release off the tab under the press | `dropTab`; see [Tab drag between windows](#tab-drag-between-windows) |
| Right click | `showTabMenu` |

Disposing the selected item makes `CTabFolder` select a neighbor and send
`SWT.Selection`; the tab listener ignores it during a move, so the dragged
document stays shown. Pressing a tab selects it, so a dragged tab always ends
selected.

The tab menu is one menu refilled on each call: Close This, Close Others, Close
to the Left, Close to the Right, Close All; Copy Full Path, Copy File Name,
Open Containing Folder; Rename Tab, Reset Tab Name; Move to New Window; the
Time Sync cascade ([menus.md](menus.md#time-sync-menus)). The side items are
disabled at the ends, the path and name items for clipboard text.

### Ampersands in tab text

A `&` is doubled in every tab text and tab tooltip, document and result tabs
alike: `CTabItem` draws text with mnemonics, and SWT drops a single `&` from a
tooltip. `tabTitle` stays unescaped; each consumer escapes for its widget.

### Tab drag between windows

A tab drag released off the tab under the press goes to `dropTab` with the
display point and the control under the pointer (`Display.getCursorControl`,
through a replaceable `PointerProbe`). The press grabs the pointer, so the
folder also gets a release outside the window. No `DragSource` is used.

| Release point | Effect |
|---|---|
| Over another main window (found through its shell's data) | `moveToWindow`: before the tab under the point, else last |
| No QLogg control under the pointer, outside this shell's bounds | `moveToNewWindow` opens a window with its corner at the point; GTK 4 and Wayland ignore the position |
| Another tab of the same strip | Reorders |
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
and 90%; View > Save Splitter Position stores it.

The bottom pane holds the `SearchBar`, then the `ResultsPane`. Showing a tab
binds the `SearchBar` to its document.

### Search row

`SearchBar` is one full-width row: the filtered view's visibility combo; icon
toggles for Match case, Regex, Whole word, Inverse, Combine, In selection,
Auto-refresh and Range; the Predefined filters drop-down; the pattern field (a
`Combo` whose list is the search history, at least 160 px); the range end
field, shown only while Range is on; two digit fields for the context lines
above and below each result (0 to 1,000); Clear, Search, the Keep results
toggle and Stop; and the search status. With no document the row is disabled.

- Each tooltip names the option and its key. The keys come from the
  controller's keymap from `attach` on and change with it
  (`SearchBar.setKeymap`).
- Enter searches and Escape stops.
- Typing completes inline with the most recent history entry that extends the
  text, compared under the Match case rule. Backspace and Delete do not
  complete.
- Predefined filters opens a menu of the filters, each checked when its pattern
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
tab shows that set; closing one removes it.

### Quick find bar

`QuickFindBar` is the row above the status bar while quick find is open: the
field, Ignore case, Incremental and Regex (the `quickFind.*` preferences),
Previous, Next, the last notice and Close. Enter finds in the direction the bar
was opened for (`'` forward, `"` backward), Shift+Enter finds backward, and
Escape closes it.

### Status bar

The status bar shows the document notes from `StatusText.notes` (index percent,
Following or Waiting to reopen, File missing, Loading stopped, the
reduced-index notice, a failure), a notice for 5 s (`showNotice`) and the
pending key prefix (`Ctrl+E -`).

It also holds the warning marker, an SWT `Link` hidden until
`showWarningMarker(true)`; clicking it runs Show Log. `showLog` opens one
`TextWindow` per main window at the first line with Close focused; a second
call replaces its text and activates it.

## Input and commands

- A Display key filter runs a window binding that is not a menu accelerator
  (`Keymap.windowCommand`), such as `Ctrl`+keypad `+`, while the event is for
  this window.
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
one implementation chosen on first use, after the Display exists (GTK reads the
backend the Display records). SWT has no public call, so each implementation
reaches SWT internals by reflection and the product compiles against every
platform's SWT jar. `ArchitectureTest.reflectionOnlyInStackingClasses` allows
reflection in these three classes only; `noSwtInternals` is unchanged.

| Platform | Class | Call | Verified |
|---|---|---|---|
| win32 | `Win32Stacking` | `OS.SetWindowPos` on `Control.handle` with `HWND_TOPMOST` or `HWND_NOTOPMOST`, no move, size or activation | CI test: `WS_EX_TOPMOST` each way |
| cocoa | `CocoaStacking` | `NSWindow.setLevel` on `Shell.window`, floating (3) or normal (0), also on the window's dialogs | CI test: the window's level and a dialog's, each way |
| GTK 3 on X11 | `GtkX11Stacking` | `GTK3.gtk_window_set_keep_above` on `Shell.shellHandle` | Under Xvfb: `_NET_WM_STATE_ABOVE` set and cleared as the window maps |
| GTK 3 on Wayland, GTK 4 | `WindowStacking.WINDOW_MANAGER` | None | Item disabled |

- A member that does not resolve, or a call that fails, logs one WARN and
  leaves the stacking unsupported (`PlatformStacking`); nothing throws to the
  caller, and the item is disabled at the next menu sync.
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
- Where `WindowStacking.windowManagerMenu` is true, the disabled item's tool
  tip names the window manager's window menu. SWT keeps no menu item tool tip
  on GTK 4.

## Platform services

| Method | Implementation |
|---|---|
| `clipboardText` | Reads the text of the `TextTransfer` clipboard, which is created on first use and disposed with the shell |
| `launch` | `Program.launch` on a folder or file: the desktop's file association (GIO on GTK, `ShellExecuteEx` on Windows), no shell |
| `launchUrl` | `Program.launch` on the fixed issue page |
| `setActive`, `setIdle` | A `BusyCounter`; while any operation is active the shell cursor is `SWT.CURSOR_APPSTARTING`, which keeps the window usable, and the last idle call restores the default. The system cursor is not disposed |
