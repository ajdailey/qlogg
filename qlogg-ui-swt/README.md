# qlogg-ui-swt

The SWT views. They draw and forward input; behavior lives in `qlogg-app`.
Compiled and tested against the build host's SWT fragment.

## Main window

`MainWindow` implements `MainView` and `ApplicationListener`.

- `place` reads every monitor's bounds and work area and the shell trim
  (`computeTrim`), and applies `WindowGeometry.place`. It skips the position
  when SWT reports the GDK backend `wayland`
  (`org.eclipse.swt.internal.gdk.backend`).
- Each main window is one `MainWindow`; the launcher creates more for New
  Window and a restored session. `SWT.Activate` reports the window to
  `ApplicationController.windowActivated`, and `toFront` (unminimize, then
  `Shell.forceActive`) raises it for handed files. The palette listener leaves
  the shared theme when the shell is disposed.
- The status bar holds a warning marker, an SWT `Link` hidden until
  `showWarningMarker(true)`; clicking it runs Show Log. `showLog` opens one
  `TextWindow` per main window at the first line with Close focused; a second
  call replaces its text and activates it. `launchUrl` passes the fixed issue
  page to `Program.launch`.
- A close request from the window system is vetoed and passed to the handler
  given to `onCloseRequest`; the controller disposes the window when shutdown
  ends.
- `MainMenuBar`, created by `attach(controller)`, builds the menu bar from the
  controller's `keymap()`:
  one menu per `CommandGroup` (File, Edit, View, Tools, Highlighters,
  Encoding, Favorites, Help), items in
  `MenuTable.commands` order; consecutive commands with the same `submenu()` form one
  cascade, Edit > Search. Each item's text shows all its
  keys; only a `WINDOW` binding becomes an SWT accelerator, so `LOG_VIEW` keys
  such as `Ctrl+C` reach the log view and never a text field. A `check()`
  command is a check item. Checked and enabled state comes from
  `CommandStates` (`qlogg-app`); the menu bar only applies it. Showing a top
  menu syncs that menu's items and listed entries, no other menu's. Every
  item's enablement is also applied on each document open, activation,
  replacement and close (`syncEnablement`): a disabled item's accelerator
  does not fire. The Highlighters menu lists the sets after None as check
  items. View > Opened Files lists the open files as radio items; choosing one
  shows its tab. Reload and the Edit menu's path commands are disabled for
  text opened from the clipboard. `MainWindow` keeps `syncMenu`, which syncs
  every menu, and the menu accessors the tests use.
- File > Open Recent lists the recent paths before Clean Up; the Favorites
  menu lists the favorites after Add to Favorites and Remove from Favorites.
  Both are refilled when their menu shows. The Encoding menu lists, after Auto,
  the common charsets as radio items and every other charset the runtime
  supports in Other (`EncodingChoices`). The common items are created with
  the menu bar; Other stays empty and disabled until
  `ApplicationController.loadEncodingChoices` delivers the full list from a
  worker, then `encodingsLoaded` fills it once. A show sets the items'
  enablement and checks the shown file's forced charset, else Auto. Other's
  items stay enabled; the disabled Other item keeps them out of reach. A `&`
  in a path is doubled so it shows.
- `keymapChanged` rebuilds the menu bar, the tool tips and the shown views
  from a later UI task, because the event that changed the key bindings may
  come from a menu item or a view the rebuild disposes; the focused log view
  keeps the focus and an open Keyboard Shortcuts window closes.
  `fontChanged` calls `LogCanvas.setFont` on both views: the font is
  replaced, the line height and advance remeasured and the viewport refitted
  in place, so a zoom keeps the menu bar, the views and the focus.
  `SWT.ZoomChanged` (a DPI change) remeasures through the same path. The
  search row's tool tips name the controller's keymap from `attach` on and
  change with it (`SearchBar.setKeymap`).
- A Display key filter runs a window binding that is not a menu accelerator
  (`Keymap.windowCommand`), such as `Ctrl`+keypad `+`, while the event is for
  this window.
- `windowBounds` and `windowMaximized` report the normal bounds kept from the last unmaximized move
  or resize and the maximized state. Options, Keyboard Shortcuts, the
  highlighter, filter and history dialogs and text windows open at their
  remembered size and report it when they close.
- The top row holds, left to right: a `ToolBar` with Open, Reload and Follow
  (a check item set from the shown document's follow state on every state
  change), a read-only path field, a `ToolBar` with Stop, then the shown
  file's size,
  modification time, encoding (with "(forced)" when the user chose it) and
  position (`PositionText`), each after a separator. Tool items carry
  `ToolIcons` images and a tooltip with the command and its keys. Reload and
  Follow are disabled without a file, Stop without a tab.
- The path field shows the shown file's full path (a clipboard document's
  tab name); with no file it is empty and disabled. Its context menu holds
  Copy Full Path, Copy File Name and Open Containing Folder, which run the
  Edit menu's commands and are disabled for clipboard text, then Copy and
  Select All for the field's text.
- A `DropTarget` on the shell takes `FileTransfer`; each dropped path opens a
  tab through `ApplicationController.openDropped`, on a worker.
- The tab row under the top row is a `CTabFolder` with one closable tab per
  open file (text: `tabTitle`, the file name unless renamed; tooltip: the
  path; data: its `DocumentController`). It shows while two or more files are
  open and is excluded from the layout otherwise. Its height hint is 1 px,
  because the folder adds its tab row as trim around the hint. The items have no
  controls: the views below show the active tab, and `activeDocumentChanged`
  disposes them and creates them over the other document's controllers,
  which hold the viewport, selection and rows. Selecting a tab calls
  `activate`; its close button and a middle click call `closeDocument`.
  Dragging a tab onto
  another recreates it at that position, because a `CTabItem` cannot move,
  and calls `moveDocument`. Disposing the selected item makes `CTabFolder`
  select a neighbor and send `SWT.Selection`; the tab listener ignores it
  during the move, so the dragged document stays shown. Pressing a tab
  selects it, so a dragged tab always ends selected. `documentReplaced`
  points the tab at the reopened document's controller. The tab's context
  menu (`showTabMenu`, one menu refilled on each call) holds Close This, Close
  Others, Close to the Left, Close to the Right, Close All, then Copy Full Path, Copy File Name and Open
  Containing Folder, then
  Rename Tab and Reset Tab Name, then Move to New Window; the side items are
  disabled at the ends and the path and name items for clipboard text.
- A tab drag released off the tab under the press goes to `dropTab` with the
  display point and the control under the pointer
  (`Display.getCursorControl`, through a replaceable `PointerProbe`). The
  press grabs the pointer, so the folder also gets a release outside the
  window. Over another main window (found through its
  shell's data) the tab moves there through `moveToWindow`: before the tab
  under the point, else last. With no QLogg control under the pointer and the
  point outside this shell's bounds, `moveToNewWindow` opens a window with its
  corner at the point; GTK 4 and Wayland ignore the position. Any other
  release does nothing, and a release on another tab of the same strip still
  reorders. `documentMovedOut` removes the tab and views as a close does;
  `documentMovedIn` inserts the tab at its index. No `DragSource` is used.
- The window body is a vertical `SashForm`: the main log view with the
  `OverviewBar` at its right over the bottom pane, split by
  `view.splitPercent`. The sash is 7 px and paints a dotted grip. View >
  Overview Bar shows or hides the bar. `+` and `-` move the split by 5 points
  between 10% and 90%; View > Save Splitter Position stores it.
- The bottom pane holds the `SearchBar`, then the `ResultsPane`. Showing a tab
  binds the `SearchBar` to its document. The status bar shows the document
  notes from `StatusText.notes` (index percent, Following, File missing,
  Loading stopped, the reduced-index notice, a failure), a notice for 5 s
  (`showNotice`) and the pending key prefix (`Ctrl+E -`).
- `SearchBar` is one full-width row: the filtered view's visibility combo;
  icon toggles for Match case, Regex, Whole word, Inverse, Combine, In
  selection and Auto-refresh; the Predefined filters drop-down; the pattern
  field (a `Combo` whose list is the search history, at least 160 px); Clear,
  Search, the Keep results toggle and Stop; and the search status. Each
  tooltip names the option and its key. Enter searches and Escape stops.
  Typing completes inline with the most recent history entry that extends the
  text, compared under the Match case rule; Backspace and Delete do not
  complete. Predefined filters opens a menu of the filters, each checked when
  its pattern stands in the field; toggling one rewrites the field from the
  checked filters, switches to regex and searches. The field's context menu
  holds Save as Filter, Edit Search History and Clear Search History. The
  toggles follow `SearchController.options()`, which keys `4` to `9` change.
  With no document the row is disabled.
- `ResultsPane` is the filtered view: a full-width `CTabFolder` strip with
  the current search and one closable tab per kept set (label: text and
  count), aligned with the search row's left edge, over a `LogCanvas` on the
  `FilteredController`. Selecting a tab shows that set; closing one removes
  it.
- `QuickFindBar` is the row above the status bar while quick find is open:
  the field, Ignore case, Incremental and Regex (the `quickFind.*`
  preferences), Previous, Next, the last notice and Close. Enter finds in the
  direction the bar was opened for (`'` forward, `"` backward), Shift+Enter
  finds backward, and Escape closes it.
- The clipboard (`TextTransfer`) is created on first use and disposed with
  the shell. `clipboardText` reads its text for Open from Clipboard.
- `launch` opens a folder or file with `Program.launch`, which hands the path
  to the desktop's file association (GIO on GTK, `ShellExecuteEx` on
  Windows) and runs no shell.
- `setActive` and `setIdle` go through a `BusyCounter`. While any operation is
  active the shell cursor is `SWT.CURSOR_APPSTARTING`, which keeps the window
  usable, not `CURSOR_WAIT`; the last idle call restores the default. The
  system cursor is not disposed.
- Every label, title and message comes from `UiText`.
- The palette comes from `ApplicationController.theme()`. A change recolors
  both log views and the overview bar at once: each disposes its
  `PaletteColors` and creates them from the new palette. Native widgets
  (menus, scroll bars, fields, buttons, tabs) take the theme from
  `Display.setDarkThemePreferred`. On GTK `SwtThemeService.preferDark` calls
  it and the theme's dark variant applies at once. win32 widgets read it only
  when created, so `SwtThemeService.open` sets it before the first Shell,
  `preferDark` does nothing, and `showsNow` reports whether the startup
  setting matches a request. On cocoa native widgets always follow the system
  appearance and nothing calls it: cocoa SWT caches its widget colors under
  the system appearance, and a different application appearance draws text
  in that appearance over the cached backgrounds (white on light gray). `SwtThemeService` reads `Display.isSystemDarkTheme()` and turns
  `SWT.Settings` into a change event; on GTK SWT reads the system setting once
  at Display creation, so a change made while QLogg runs applies at the next
  start.
- Only SWT's public API is used, so the views run unchanged on GTK3 and
  GTK4 (`ArchitectureTest` rejects `org.eclipse.swt.internal`). Platform
  differences go through `SWT.getPlatform()`. The one read of an internal
  name is the GDK backend system property above, which fails safe when
  absent.
- GTK3 is the Linux default. GTK4 is an experimental opt-in: set
  `SWT_GTK4=1` before starting QLogg (linux-x86_64 only; linux-aarch64 has no
  SWT GTK4 natives and stays on GTK3). Open SWT GTK4 defects affect the log
  view (slow scrolling, custom painting, missing mouse events). Under GTK4,
  Help > Documentation shows a notice naming the guide's copy and creates no
  `Browser`: WebKitGTK 6.0 aborts the JVM when the browser starts (measured,
  Ubuntu 24.04, WebKitGTK 2.52.6). The `test-gtk4`
  CI job runs these tests with `SWT_GTK4=1`; a test that hits a known GTK 4
  failure skips itself there through the test class `Gtk4`.

Dialogs (`Dialogs`, `LineNumberDialog`, `ErrorDialog`, `AboutDialog`,
`TextWindow`, `ShortcutsWindow`, `HistoryDialog`, `FiltersDialog`,
`NameDialog`, `SwitchFileDialog`, `HighlightersDialog`) open without a nested
event loop:
`open` returns at once and buttons act through listeners. The platform
`FileDialog` and `ColorDialog` are the exceptions. Modal dialogs use
`APPLICATION_MODAL`; Enter presses the default button and Escape closes.

| Window | Contents |
|---|---|
| Go to Line | 1-based line; OK is enabled only for a positive number |
| Edit Search History | One entry per line and the count kept; OK saves both, dropping blank lines |
| Predefined Filters | Add and Remove icons at the left, Import and Export centered, Move Up and Move Down icons at the right; a table of Name, Pattern and Regex, where a click on a selected row's name or pattern edits it in place and a click on Regex toggles it; Import appends the filters of a chosen file not in the list yet, Export writes the list, both on a worker with the result reported in the dialog; OK saves and closes, Apply saves |
| Save as Filter, Rename Tab | `NameDialog`: the filter's name, prefilled with the pattern cut at 64 chars; or the tab's name, empty to reset it |
| Switch to File | `SwitchFileDialog`: the open files by path and a filter field matching any part of it, ignoring case; Up and Down in the field move the selection, Enter or a double click shows the tab |
| Configure Highlighters | Highlighter Sets tab: the sets (Add, Remove, Move Up, Move Down, Import, Export, name) and the selected set's highlighters (Add, Remove, Move Up, Move Down) with the editor (pattern, Regex, Ignore case, whole line, Enabled, text and background colors through `ColorField`, opacity, variance); a pattern that does not compile is reported below. Color Labels tab: name, colors and cycle membership per label. Match Highlight tab: on or off, theme color or a chosen one, variance. Import adds the sets of a chosen file whose ids are new. The list edits and the pattern check run in `HighlightSetsEditor` (qlogg-app); the dialog holds the widgets. OK saves |
| Keyboard Shortcuts | Modeless table (group, command, keys) from `Keymap`, filter field, Copy as plain text |
| Command-Line Options, Licenses | Read-only monospace text with Copy; modeless, or modal and owned by the dialog when opened over a modal one (Licenses from About), because GTK gives input only to the newest modal shell |
| User guide | `GuideWindow`: an SWT `Browser` with JavaScript off on the guide's copy, and Close. A `LocationListener` refuses every location but that file (with or without an anchor) and `about:blank`. An `SWTException` or `SWTError` from a browser call, or a page not loaded 15 s after the load request (a `UiDispatcher.timerExec`, so the UI thread does not wait), closes the window and calls `ApplicationController.showGuideElsewhere`: the OS browser, else a notice naming the copy. A failure during `create` returns null instead, and the controller takes the same fallback. A bound that expires during one of `GuideWindow`'s own browser or shell calls closes the window after that call returns: on Cocoa an SWT timer runs inside any nested run loop a native call spins. A second Documentation reloads the page, restarts the bound and activates the window |
| About QLogg | Logo at the top left (the icon variant for the window background's luminance, at 48 logical px), a bold name and version beside it, then tagline, project link, copyright, license and the runtime details in a smaller font; Licenses and Copy Details at the bottom left, OK at the bottom right. The link calls `Program.launch` with the fixed project URL |

## Log view

`LogCanvas` (`SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND`) paints a
`LineViewController` snapshot, the main view's or the filtered view's: the
visible rows of its `LineBlock` with their highlight spans, a bullet column
(a mark bullet for a marked line, else a match bullet for a line in the shown
results), each row's source line number in a gutter sized to the document's
line count, the current line and the selection. Rows
the block does not hold yet stay blank, numbers included, until the next read
arrives, so painting never touches the file. One implementation serves both
views. The first paint in the process that draws a loaded line logs
`First paint of file text` at DEBUG; the benchmark harness's `startup`
command waits for that line.

- Focus, a click, a key and the context menu set it as the view that
  commands go to (`ApplicationController.setFocusedView`).
- The right-click menu lists Mark or Unmark, Copy, Copy This Line, find the
  selection forward and backward, add, exclude and replace search, search
  start, end and clear, selection start and end, Save Splitter Position, Copy
  with Line Numbers, Save Selection to File, Send Selection to Scratchpad,
  Toggle Fold, a Highlighters submenu (None and the sets) and a Color Label submenu (None
  and the label names). A right click outside the selection first moves the
  caret to that row. The menu is built on the canvas's first `MenuDetect`,
  not with the canvas: it costs 35-60 ms per view under GTK 4, which every
  file open would otherwise pay twice on the UI thread.
- A double click selects the word (letters, digits, `_`) under the pointer
  within the decoded column window of a printable-ASCII row
  (`LineViewController.selectWordAt`).
- A left click in the bullet column toggles the mark of the clicked row's
  line. `Ctrl`+wheel (`Cmd` on macOS) calls `zoomFont`.

- Text sits on a grid of the `FontSettings` font (empty family: Monospace,
  Consolas or Menlo; bold when set; forced text anti-aliasing when set).
  `TextGrid` holds the geometry, with no SWT types: the column advance is the
  `GC.textExtent` width of 1000 digits divided by 1000, kept fractional, and
  column `c` sits at `round(c * advance)`. It also sizes the gutter and maps
  points to columns (`hit`, `wrappedHit`, `wrapFit`); hit testing always uses
  the grid. A printable-ASCII row is drawn as one string with the font's own
  advances, so an advance rounded to whole pixels drifts off the glyphs by
  the fraction per column (macOS Menlo, GTK 4, fractional Windows scaling).
- `RowPainter` paints each screen row, in the plain and the wrapped paint
  alike: the background (base, current line, then the whole-line
  highlight), the span backgrounds, the selection, the text in the line's
  color, the text again clipped to each span with a text color and to the
  selection, then the whitespace markers. A translucent color is blended
  over the color under it. Measured on Xvfb at 900x600: a full repaint of 22
  rows with 10 spans each takes about 2 ms.
- A row outside printable ASCII is laid out once per paint in the painter's
  one `TextLayout`, which draws it; a column table read from the layout
  (`getLocation`) places the spans, the selection and the markers on its
  glyphs. A prefix measured alone has the wrong width: GTK draws spaces that
  lead CJK text in the CJK font of the run they join. Line and paragraph
  separators are laid out as spaces so the row stays one line. The layout
  takes its line ascent and leading from the tallest font in the line, so
  the painter draws it shifted to put its baseline on the GC font's
  (ascent plus leading); drawn at the row's top, a CJK row sits 1-3 px low.
  `GC.setTextAntialias` does not reach
  a `TextLayout` on GTK, so forced anti-aliasing applies to printable-ASCII
  rows only.
- The vertical scroll bar takes `Viewport`'s maximum, thumb and selection.
  Arrow and page clicks scroll by lines; a drag goes through
  `setScrollbarSelection`, which maps past `int` range. The wheel scrolls by
  its event count; with `Alt` it scrolls that many columns, with `Shift` one
  page per notch.
- An `Alt`-drag, or any drag with Edit > Block Selection on, selects a block
  of columns, painted per row between the two corners' columns.
- A drag that leaves the view above or below scrolls one line at once, then
  one line every 50 ms from a `SwtUiDispatcher.timerExec` timer while the button
  stays down outside, extending the selection to the edge row. Release,
  focus loss and dispose cancel the timer.
- A collapsed entry's row has a triangle at the gutter's right edge and a
  rule under it.
- With whitespace markers on, a tab's first cell shows an arrow, a trailing
  space a dot and a lone CR a left arrow, each inside its cell; after the
  line's last column an LF shows as a corner and a CRLF as a left arrow and a
  corner. Only lines whose end lies in the read window get the end marker.
- With text wrap on, each line paints as rows of the view's width in
  columns, the number and bullet on its first row; hit testing walks the
  wrapped rows. A click at or past a row's right edge maps to the boundary
  that row shares with the next; a double click there maps to the row's last
  column instead (`TextGrid.wrappedWordHit`), so it selects the word at the
  row's end. A segment's glyphs wider than the grid run past the right
  edge, clipped. After each new snapshot the canvas counts the lines from the
  top that fit whole, a line not read yet counting one row, and reports it
  (`wrapFitted`); the horizontal scroll bar has nothing to scroll.
- Keys pass through `SwtKeys` to `KeySequencer`; arrow, page, Escape and
  Return traversal is turned off so those keys reach it.
- The font, `PaletteColors` and the painter's `TextLayout` are disposed with
  the canvas, the colors also on a palette change. Painting allocates only a highlight color seen for the
  first time; `PaletteColors` keeps at most 512 and empties the cache before a
  paint past that.

`OverviewBar` (14 px wide) paints the `OverviewController`'s last bins: a
mark line where a pixel row holds marks, a match line over it where it holds
matches, and a frame around the visible lines. A click or drag shows the line
under the pointer; a resize reports the height, and the controller computes
nothing while the bar is hidden.

## Tool windows

- `ScratchpadWindow` (Tools > Scratchpad, modeless) holds editable text tabs;
  New Tab or `Ctrl+N` adds one. The Transform menu applies a `Transforms`
  kind to the selection, or to the whole tab without one; a failure shows its
  reason in the line under the tabs, which otherwise lists the selection's
  derived values. Text sent from a log view appends to or replaces the shown
  tab. Transforms, values and sent text take at most 1 Mi chars.
  `ApplicationController.transform` runs an input over 64 Ki chars on a CPU
  worker; the tab is read-only and the line shows Transforming until the
  result arrives, text sent meanwhile goes to a new tab, and a result for a
  closed tab is dropped.
- `MultiSearchWindow` (Edit > Search All Open Files, modeless) lists one row
  per file group with its match count and state under a summary line;
  choosing a row shows that tab with its search's results. Stop cancels every
  search of the group.
- `SelectorsDialog` (Highlighters > Automatic Highlighters) edits the
  ordered selectors: a table of match kind, pattern and set over an editor
  row, with Add, Update, Remove and Move Up and Down; OK saves them.

## Other classes

- `SwtUiDispatcher` implements `UiDispatcher` over the Display. It drops a
  submission once the Display is disposed, including when disposal races the
  call. It is the only class that calls `Display.asyncExec` or `timerExec`,
  and nothing calls `syncExec` (`ArchitectureTest`). `cancelTimer` cancels a
  `timerExec` task that has not run.
- `Icons` loads the 16 to 256 px PNGs. `DARK` is the window and taskbar icon;
  `LIGHT` is for in-app use with the light palette. The PNGs are rendered by
  `tools/icons.sh` and owned by the views that create them.
- `ToolIcons` loads the toolbar, toggle and dialog icons from
  `icons/tool/NAME-light-16.png` (dark ink) or `NAME-dark-16.png` (light
  ink), with the 32 px PNG at 200% zoom. The ink follows the owning
  control's background, because toolbars are native: on Windows they stay
  light under the dark palette. `reink` reads the background again and swaps
  the image of every item given to `apply`; it runs on `SWT.Settings` and on
  the UI turn after a palette change, once native controls have restyled.
  Images are created on first use and disposed with the owner.
- `src/icons/*.svg` is the hand-authored icon set (16 px grid, strokes in
  `currentColor`). At `generate-resources` the build copies JSVG (MIT, build
  only) and runs `src/build/IconRenderer.java` with the Java source launcher,
  which renders every SVG in both inks at 16 and 32 px into
  `target/generated-resources/icons`; the jar includes them.

View classes never block the UI thread: no `Future.get`, `Thread.join`,
`Thread.sleep`, `Object.wait`, `Lock.lock`, `Condition.await`,
`Semaphore.acquire`, `BlockingQueue.put` or `take`, `CountDownLatch.await`,
`OperationHandle.await` or `java.nio.channels` (`ArchitectureTest`). They do
not use `LogDocument`, `MutableLineSet`, `LineReader` or `FoldMap`, do not
call the `Viewport` mutators, and use only the enums of `core.search` and
`core.watch`.

`OptionsDialog` (Tools > Options) is modeless with General, View, File,
Shortcuts, Advanced and Folding tabs; OK and Apply call `setOptions`,
`setSearchHistory`, `setQuickFindOptions`, `setFollowSettings` and
`setPreferences`. The View tab's family list is `Display.getFontList`.
Changing the size or family clears the zoom. The Shortcuts tab lists every
command with its keys (an override marked `*`); the key field records one or
two strokes, and Add refuses keys `Keymap.conflicts` reports, naming the
command that holds them. The Folding tab edits the entry start pattern and
the continuation patterns, one per line, with Restore Defaults; OK and Apply
check them first (`FoldRulesText`), and a refused pattern applies nothing,
selects the tab and shows the reason under the patterns.

`AppMenu` connects the macOS application menu when `SWT.getPlatform()` is
`cocoa` and `Display.getSystemMenu()` is not null; elsewhere `present` is
false and the menu bar keeps About, Options and Exit. About, Preferences and
Quit run `ABOUT`, `OPTIONS` and `QUIT` in the window activated last; a
Display `SWT.Close` (Cmd-Q, logout) is vetoed and runs `WindowSet.quit`; an
`SWT.OpenDocument` file goes to `WindowSet.openHandoff`.

## Tests

The test classes are tagged `swt`. `MainWindowTest` needs X11 (Xvfb) for its
position assertion. `FilteredPaneTest` drives the filtered pane over a real
search: the search row sits in the bottom pane with its controls in order
and the result tabs full width below it, a click on a row moves the main view
to the line, visibility keys and the combo agree, the sash has its grip, `+` and `-` move the sash and Save Splitter Position stores
it, both views carry the context menu and build it on the first right
click, a double click selects a word and `*`
finds it through the quick find bar to an end-of-file notice, kept sets show
as tabs, the filters menu and history list work, and the Predefined Filters
table edits a row in place, toggles Regex and applies. `TabsTest` opens
two files: the top row holds toolbar, path field and its menu, Stop and file
fields; the tab row shows with two files, hides when one is left and shows
again for a second; the last one shows, selecting a tab shows its document
and file fields,
dragging a tab moves it, a dragged tab stays selected with its document
shown in the views and the path field, closing and Close All remove tabs,
`F` follows, the tab menu's items and a middle click close tabs, a second
tab menu reuses the first with new items, Reload and the document check
items are enabled after an open with no menu shown since, File > Close empties and
disables the path field, a rename shows in the tab,
the toolbar's Follow tracks the document, View > Opened Files and Switch to
File list and show tabs, and dropped files open.
`TabMoveGuiTest` opens two windows over one `WindowSet`: View > Move to New
Window and the tab menu item each move the shown tab to a new window, the
emptied window stays open without views and its menu item is disabled; a
release inside the window's own bounds does nothing and one outside opens a
new window with the tab; a drag released over another window off its tab
strip appends the tab there and shows it, and `dropTab` over that strip
inserts it at the tab under the point; a drag inside the strip still reorders
without looking for a window. The strip drop calls `dropTab` directly because
GTK 4 opens every window at 0,0 under Xvfb, where the release point would also
hit the source strip. With
`-Dqlogg.shots=<dir>` it writes `tabmove-source.png` and `tabmove-target.png`.
`HelpItemsTest` checks that Debug Log File is checked only while the log
file is attached, and that a WARN line shows the status-bar marker, Show Log
clears it and lists the lines, a later ERROR shows it again, and a second Show
Log refreshes the same window. It opens Help > Documentation and waits for the
guide window to load the copy or to close and take the fallback (or, without a
browser widget or under GTK4, for the notice naming it); it reads the
location from the `changed` event, never `Browser.getUrl`, which blocks on a
WebView2 still starting. Without WebView2 it checks that a factory throwing Edge's timeout
`SWTException` leaves no window and that the load bound expires once unless
met. It also checks the locations `GuideWindow` allows, and that without the
application menu the menu bar keeps About and Options.
`GuideScreenshotsTest` runs only with `-Dqlogg.guide.shots=<dir>`
(`tools/screenshots.sh`) and writes the user guide's images.
`OptionsAndMenusTest` checks a bullet-column click marking and unmarking,
`Ctrl`+wheel zoom over the focused filtered view applying in place (same
views, menu bar and focus, fewer rows, the height restored by Reset Zoom),
the Encoding, Favorites and Open Recent menus, and the shortcut editor
refusing `Ctrl+O` for Reload, adding `Ctrl+Alt+R` and the File menu showing it after Apply, a new key for Match
Case reaching the search row's tool tip, and the Folding tab refusing an
unclosed class and applying an entry start pattern; with
`-Dqlogg.shots=<dir>` it writes `options.png` and `main.png`.
`LogViewTest`
composes the window over a real document and drives it with SWT events: painted text and line-number pixels (through
`Control.print`), a selection of columns 150-160 at 6 pt lying on its
glyphs, keys including `Ctrl+E Ctrl+T`, mouse selection and
`Ctrl+C` to the clipboard, scroll bar drag and wheel, the menu bar's menus,
order, labels and accelerators, Go to Line, the Help windows and the About
layout, the search bar with `N` and
`Shift+N` walking its matches, and the busy cursor across overlapping
operations. `ThemeAndHighlightTest` switches the palette and reads painted
pixels of both canvases, clicks the overview bar, and toggles a set from the
Highlighters menu and reads the span's painted color; it also drives
`HighlightersDialog` through add, an invalid pattern, move and remove.
`EventLoop.runUntil`
dispatches events until a condition holds, with a bound and no fixed sleep.
`ViewFeaturesGuiTest` drives `Alt` and `Shift` with the wheel, an `Alt`-drag
block selection, a drag held still below the view scrolling until release,
focus loss or tab close, a click on the second wrapped row of a long line and the
reported fit, a double click past a wrapped row's end selecting the word
there, whitespace and fold painting, scratchpad transforms inline and
on a worker (the tab read-only until the result) with the epoch value and
`Ctrl+N`, the search across two files with its row selecting
a tab, and the selectors editor row. `SwtUiDispatcherTest` checks that a
canceled timer does not run and that cancel after dispose does nothing.

`ResourceLeakTest` starts its Display with `DeviceData.tracking`, runs a
warm-up round of tab open and close, light and dark theme, zoom in and out and
a menu sync, then four more rounds, and requires each tracked type's count
(`Font`, `Image`, `Cursor` and `TextLayout` on GTK 3) to return to the
warm-up's; a failure lists each new object's allocation stack. SWT 3.135.0
does not track `Color`.
`MainMenuBarTest` shows one top menu and checks that no other menu's items or
sets changed, and that the Encoding menu's charset items are the same objects
after each show while the check follows Auto, a forced charset and a choice
from Other, with one charset checked at a time. With the list held back
through `MainWindow.setEncodingSource`, Other is empty and disabled while a
forced charset is checked, and fills in once when the list arrives.
`CommandStatesTest` records every check item's checked and enabled state, and
the file commands' enabled state, after `syncMenu` with no document, with a
document and with its filtered view focused. `TextGridCasesTest` records
`hit`, `wrappedRows`, `wrapFit` and `gutterWidth` in grid units: nearest
column boundary, gutter and negative coordinates, clamping below the last line,
a tab row, a scrolled view, line numbers on and off, and wrapped segments at
two widths with the click past a segment's end clamped to it. `TextGridTest`
runs the same cases against `TextGrid` with no display, at a fractional and
an integer advance, plus `wrappedWordHit`. `RowPixelsTest` compares painted extents with glyph ink:
a span and the selection on the second row of a wrapped ASCII line and of a
wrapped CJK line, and a span over CJK glyphs at the start of an unwrapped row
and after leading spaces, and ASCII glyphs at the same height on a laid-out
row and an ASCII row. A wrapped case waits for the wrapped read, because the
block read before it holds one screen width of each line. Extents are read on
a row's bottom pixel line, which glyph ink reaches when a laid-out row sits
below the grid baseline. With
`-Dqlogg.shots=<dir>` it writes `rows-plain.png`, `rows-cjk.png`,
`rows-wrapped.png` and `rows-wrapped-selection.png` at 1100x700.

`HangWatchdog`, registered for every test in the module through
`META-INF/services` and `junit-platform.properties`, prints every thread's
stack to stderr when a test, its `@BeforeEach` and `@AfterEach` included, runs
past `-Dqlogg.test.hangSeconds` (120 by default), and halts the test JVM 5 s
later. A test blocked in a native call on the main thread then ends the build
with its stack in the log, and surefire reports the fork crash.
