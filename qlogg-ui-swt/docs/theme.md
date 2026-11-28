# Theme and icons

`SwtThemeService` applies `ui.theme` to native widgets and reports system
appearance changes; `CocoaChrome` recolors native widgets on macOS;
`DialogColors` colors dialogs and `DialogTabs` their tab folders on a dark
Windows start; `ChromeColors` colors
the main window's rows and fields under a dark theme on Windows;
`TabStrip.colorTabs` colors the tab strips; `Icons` and `ToolIcons` load the
images. Module overview: [../README.md](../README.md).

## Palette

The palette comes from `ApplicationController.theme()`: the theme's, or the
`view.colors` scheme's; the `light` and `dark` schemes hold the theme palettes,
so the log views can take either side in either chrome. A change recolors both log views, the overview bar, the
tab strips and the tab dots at once: each view disposes its `PaletteColors` and
creates them from the new palette. Native widgets (menus, scroll bars, fields,
buttons) take `ui.theme` alone from `Display.setDarkThemePreferred`, applied per
platform as below, so a light scheme can sit in dark chrome and the reverse.

| Surface | Colors from |
|---|---|
| `LogCanvas` (main and filtered), gutter, `OverviewBar`, `TabDots`, the main, results and scratchpad tab strips, `TabStripButtons` and their icons' ink, the empty log and results areas | The palette |
| Toolbars, search row, status bar, sash, dialogs, `MarksWindow` table, the scratchpad's text | Native, `ui.theme`; `ToolIcons` inks from the native background |
| Top row, search row, quick find bar, status bar, sash and their fields on win32 | `Win32ChromeColors`, the neutral grays of `Win32DialogColors`, while `ui.theme` resolves to dark; see [Chrome colors](#chrome-colors) |
| `CocoaChrome` shell, list and header colors | The pinned side, `ui.theme`; skips `PALETTE_COLORED` controls |
| Dialogs and tool windows on win32 under the dark side | `Win32DialogColors`, `Win32DialogTabs`; see [Dialog colors](#dialog-colors) |

## Native widget theme

`SwtThemeService` reaches native widgets through the platform's `ThemeFollow`:
`GtkThemeFollow`, `Win32ThemeFollow` or `CocoaThemeFollow`.

| Platform | `Display.setDarkThemePreferred` | System appearance change |
|---|---|---|
| GTK | `SwtThemeService.open` calls it for Light and Dark and `preferDark` at each switch; the GTK theme's dark variant applies at once | SWT reads the system setting once at Display creation, so a change made while QLogg runs applies at the next start |
| win32 | Widgets read it only when created, so `SwtThemeService.open` sets it before the first Shell, for system from the system setting at that moment; at a switch `showOnWindows` sets the new side, flushes the pop-up menu theme and rebuilds every main window ([Window rebuild](#window-rebuild)) | The service reads `Display.isSystemDarkTheme()` every 500 ms, because SWT sends `SWT.Settings` only 2 s after `WM_SETTINGCHANGE` (`Display.SETTINGS_DELAY`); under System a change rebuilds the windows like a switch |
| cocoa | `start` pins the application appearance to the side `ui.theme` resolves to in every mode, System from the system setting at that moment; at a switch `showOnWindows` pins the new side and `CocoaChrome` recolors every shell ([Cocoa chrome](#cocoa-chrome)); no rebuild | No `SWT.Settings` is sent (measured, macOS 15), so the service reads `Display.isSystemDarkTheme()` every 500 ms; under System a change re-pins like a switch, since SWT has no call that unpins |

`SwtThemeService` reads the system setting through `NativeTheme.systemDark`
(`Display.isSystemDarkTheme()`) and turns `SWT.Settings`
into a change event. Its `preferDark` calls `ThemeFollow.preferDark` and then
`ThemeFollow.showOnWindows` with the `WindowRebuild` the launcher set
(`WindowRebuild.NONE` until then) on every platform.

A GTK `Link` keeps the link color of its creation through a theme change, so
`SwtThemeService` calls `ThemeFollow.settingsChanged` at each `SWT.Settings`,
and `GtkThemeFollow` gives every link the system link color raised to 4.5:1 on
the link's background (`Contrast.readable`: moved toward white or black,
whichever contrasts more with the ground). Adwaita's dark link color has 3.25:1
on its own window background. Each new `Link`, the About link and the status
bar's warnings link, calls `SwtThemeService.colorLink` when created; win32 and
cocoa do nothing there. QLogg's links under Adwaita dark are not yet measured.

## Window rebuild

On win32 the menu bar, title bar, scroll bars and buttons take the theme only
when created: a forced repaint, `setMenuBar`, a resize or a minimize and
restore leaves the menu bar `#ffffff` after `setDarkThemePreferred(true)`
(measured, `windows-2025`). `Win32ThemeFollow.showOnWindows` therefore sets the
preference and `DialogColors.NATIVE_DARK` when the side differs from the
recorded one, calls `Win32MenuThemes.flush`, and calls `WindowRebuild.rebuild`.

The pop-up menu theme (menu bar menus, cascades, context menus) is
process-wide: `setDarkThemePreferred` reaches `SetPreferredAppMode`, and
uxtheme draws menus in their first side until `FlushMenuThemes` runs, which SWT
3.135.0 never calls and no public API reaches. `Win32MenuThemes.flush` calls it
as uxtheme ordinal 136 (no exported name) through `java.lang.foreign` by
reflection, the API being a preview in Java 21: `LoadLibraryA("uxtheme.dll")`,
then `GetProcAddress` by ordinal, linked once per process. Every launcher
passes `--enable-native-access=ALL-UNNAMED`, so the link prints no
restricted-method warning. A link or call that fails logs one WARN, and menus
keep the side of the start (`NativeTheme.startDark`). `Win32MenuThemes.shownDark`
reports the side menus show, which the menu icons' ink takes. Measured on
`windows-2025` at 100% (probe run 45910126999): a pop-up menu `#f9f9f9`
after a switch to dark without the flush, `#2c2c2c` after it, and `#f9f9f9`
after the flush back to light. On `windows-11-arm` and at 150% the flush
returns true and the screen capture did not reach the menu, so those cases
are unmeasured.

`WindowViews` implements `WindowRebuild`. The launcher wraps each `MainWindow`
in a `MainWindowSlot`, which implements `MainView` by forwarding to the current
window, so the `ApplicationController`, its sub-controllers and their hosts keep
one view. `WindowViews` rebuilds one window per UI turn, starting on the turn
after the request, so no window is disposed inside its own menu or key handler.
A window waits, checked every 250 ms, while it owns a visible modal shell, a
native chooser runs over it, a menu bar menu shows, or a modeless window it owns
holds input (`WindowViews.holdWhile`): Preferences while open, Marks List while
a comment edit is open, the scratchpad while a transform runs. Disposing any of
them would drop the user's input or the dialog's result. QLogg has no print
dialog. Every `FileDialog`, `DirectoryDialog`
and `ColorDialog` opens through `NativeDialogs`, which counts it on its
top-level shell. `WindowViews` records the side each window was built under
(`DialogColors.nativeDark` when it is added or rebuilt) and rebuilds a window
only while that side differs from the current one, so a switch and back before
a window's turn leaves it alone, and a window opened after a switch is not
rebuilt. A hidden window, or one whose controller shuts down, takes the side
without a rebuild.

`MainWindowSlot.rebuild` reads a `RebuildState`, detaches the old window
(`MainWindow.detach`: it stops listening and disposes the views), builds a
`MainWindow` over the same controller, adds the tabs, applies the state, and
disposes the old shell last, so `WindowViews.anyOpen`, the launcher's event
loop condition, never sees no window. Documents, views' positions, searches,
marks, follow, quick find, kept results and the shown set live in the
controllers and carry unchanged; running work (indexing, a search, Search in
Folder, through `FolderSearchWindow.keepSearch`) continues, and the busy count
stays in the slot, so a running log or folder search shows the busy cursor on
the new window and its reopened tool windows. The tab strips of the new window
lay out again once `CTabFolder` measures their buttons on a later UI turn
(`TabStripHeight`, see [main-window.md](main-window.md#tab-strip-height)).

| Carried by `RebuildState` | How |
|---|---|
| Bounds, maximized, full screen, title | Placed before the tabs, the restore bounds before the maximize (GTK 4 maximizes again after open, `WindowPlacement.afterOpen`); full screen after open |
| Minimized | Minimized again after open, with the restore bounds kept; a minimized window rebuilds like a shown one and activates only another main window that was active |
| Always on Top | `setOnTop` before open |
| Focus (main view, filtered view, search row, quick find) and the active shell | Restored when the main window or one of its tool windows was active |
| Marks, Search in Open Files, Search in Folder, scratchpad, Show Log, Keyboard Shortcuts, user guide | Reopened at their bounds with the scratchpad's tabs and text, the log text, the shortcuts filter and the guide's last location |
| Command palette, context menus, a status notice | Closed |

A rebuild of one window takes 187 to 334 ms on `windows-2025` (1, 5 and 20
tabs; the tab count barely matters, since only the shown tab has views), over
the 100 ms UI bound; a switch is rare and user-initiated, and the stall is
accepted. The user guide reloads at its last anchor; JavaScript is off, so the
scroll position within a section is not kept.

- win32: a check box is a plain `Button` with `SWT.CHECK`, created under the
  native setting. Under the dark one win32 draws the box dark (unchecked
  interior `#242424`) and its label near-white, in a dialog and in the main
  window alike. On the quick find bar `Win32ChromeColors` gives the label
  `#ffffff` under dark and, on the light side after a dark start, the system
  widget text, which a light page shows.
- cocoa: pin the application appearance only together with `CocoaChrome`.
  cocoa SWT caches its widget colors under the system appearance at Display
  creation, and a pinned appearance that differs draws native text over the
  cached backgrounds (white on light gray).

## Cocoa chrome

While the pinned side differs from the side SWT cached its colors under,
`CocoaChrome` gives each shell the window color of the pinned side with
`INHERIT_FORCE`, from constants measured on macOS 15. A `TabFolder` stops that
inheritance, so check boxes get a transparent background over its native panel
and its titles the text color; tables, trees, lists, editable combos, fields,
spinners and a `CTabFolder` get list colors. A control with `PALETTE_COLORED` keeps its
colors. A `SWT.Show` filter covers shells opened later and an `SWT.Skin`
listener controls created later, such as a new scratchpad tab's field or the
Marks List comment field. SWT sends `SWT.Skin` from `readAndDispatch` and
layout, neither of which runs while a menu tracks, so a control whose
`CocoaChrome.STYLED` side is not the pinned one is also styled at its
`SWT.Show` and, while a menu other than a menu bar shows, by a sweep of every
shell each 100 ms through the `UiDispatcher` timer. Timers run during tracking;
on macos-15-intel a new field got no `SWT.Paint` and an `asyncExec` did not run
before the menu closed. A menu bar sends `SWT.Show` with its shell and no
`SWT.Hide`, so it does not start the sweep. Each control of a recolored shell receives `SWT.Settings`, so `ToolIcons`
re-inks. Back on the cached side the colors return to null, except Table and
Tree headers: they carry explicit list colors from creation, the Display's
list colors on the cached side, never null (null colors draw blank labels in
dark). A visible Table header is hidden and shown again when its side changes
(`ThemeFollow.HEADER_DARK`), so it repaints, the area right of the last column
included; a Tree repaints at once.

A control type that `CocoaChrome.style` does not list keeps SWT's cached
colors behind text AppKit draws in the pinned appearance; a new type that SWT
paints with cached colors needs a row there. Composites, sash forms, tool bars
and labels take the forced shell background. Links, push and toggle buttons,
scroll bars, sliders, progress bars and the guide's `Browser` take their text
and fill from AppKit (links, the slider and the progress bar unverified on
macOS). `LogCanvas`, `OverviewBar` and `TabStripButtons` paint from the
palette.

Limits on cocoa:

- A read-only `Combo`, an `NSPopUpButton`, keeps null colors on every side.
  SWT writes a Combo's foreground into each item title of its menu, and AppKit
  draws that menu in the pinned appearance, where an explicit white title draws
  `#656565` on `#323232` (2.2:1). With no color the items draw `#e0e0e0` on
  `#323232` under a dark pin and `#242424` on `#ececec` under a light pin
  (measured on macOS 15 arm64 and Intel, SWT 3.135.0).
- AppKit draws menus and the menu bar whole, so they stay readable. Only the
  read-only `Combo` menu is measured under a pin: it takes the pinned side.
- `Display.getSystemColor` returns the cached side for the life of the
  process. `COLOR_WIDGET_NORMAL_SHADOW`, the sash grip, is one gray on both
  sides. `NativeDialogColors.disabledText` stays `#dcdcdc` after a light start
  and `#464646` after a dark one; on either pinned side the command palette's
  disabled rows read dimmer than enabled ones (measured, macOS 15).

## Chrome colors

`MainWindow` takes a `ChromeColors` from `PlatformTraits.chromeColors` and calls
it on every platform: `Win32ChromeColors` on win32, where the dark native theme
leaves a `ToolBar`, `Composite`, `Label`, `Link` and every field light, and
`NativeChromeColors`, whose methods do nothing, on GTK and cocoa.

| Method | `Win32ChromeColors` | `NativeChromeColors` |
|---|---|---|
| `add(Control)` | Lists a surface and colors it for the current side; a control created later under it is colored at its `SWT.Skin` event | Nothing |
| `add(ChromeColors.Icons)` | Lists an icon set; sets its checked ink and faded disabled copies | Nothing |
| `apply(boolean)` | Colors every surface for a side, then fades and re-inks every icon set | Nothing |
| `dispose()` | Removes the `SWT.Skin` listener | Nothing |

While `ui.theme` resolves to dark (`ThemeController.dark`, reported by
`PaletteListener.themeChanged`), every control under a surface takes the
neutral grays of `Win32DialogColors` whatever the `view.colors` scheme, as
native controls do: `WINDOW` `#202020` with `TEXT` `#ffffff` (16.29:1). A `Link`
takes the system link color raised to 4.5:1 (`Contrast.readable`); each
composite takes `SWT.INHERIT_DEFAULT`, so native push buttons show no light
corners. A `Text` and an editable `Combo` take `FIELD` `#2b2b2b` (14.16:1), and
a read-only `Combo` the push button face `FACE` `#333333` (12.63:1): the dark
native theme draws its closed field and leaves its drop-down list white unless
the combo has colors. Under light the colors are null and composites take
`SWT.INHERIT_NONE`. Both directions apply live.

The grays match what win32 draws under the dark preference (measured,
`windows-2025` at 100%, probe run 45937830962; on `windows-11-arm` the screen
read returns the first-run page):

| Native surface | Color |
|---|---|
| Title bar | `#202020` |
| Push button face, closed read-only combo | `#333333`, white text |
| Scroll bar track, thumb, corner | `#171717`, `#959595`, `#383838` |
| Bordered field frame | `#646464` |
| Uncolored `Text` and editable combo field | `#ffffff`: the dark preference leaves edit fields light |

`FIELD` lies between the title bar and the button face.

`Win32ChromeColors` reads `DialogColors.nativeDark` at its first `apply` and
keeps it, one instance per window: the side the window's controls were created
under, also while the window waits for a rebuild with a dialog open. While that
side is dark, a field holding text keeps the dark colors under light: the
dark native field draws near-black under any light colors. A switch rebuilds
the window, so this holds only under a fixed `view.colors` scheme. An empty
`Text` takes the system list colors, set explicitly, because a field with no
colors draws the dark native field. While a `Text` is empty and unfocused,
`Win32EmptyField` fills it in its background on either side, the system one
under light and `FIELD` under dark, with any hint in `Win32DialogColors.HINT`
(`#9a9a9a`, 5.03:1) under dark, because the dark native field draws
`#212121` there under any colors (measured in QCaret on Windows 11; not yet
measured in QLogg). An empty editable `Combo` takes the system colors. A
field is colored again on the `SWT.Modify` that empties it or gives it text.

`MainWindow` registers the top row, the search row, the quick find bar, the
status bar and each sash of the split. The fields are the path field (read-only),
the pattern and range combos, the two context fields and the quick find field.
The visibility combo is the read-only one. The walk does not enter push and
arrow buttons, `List`, `Table`, `Tree`, `Spinner`, `ProgressBar`, `StyledText`,
`Browser`, `CTabFolder` or a control with `ChromeColors.PALETTE_COLORED`. `TabStripButtons` paints itself from the palette and is not
registered.

`apply` colors the surfaces first, then calls `fadeDisabled` and `reink` on the
toolbar's and the search row's `ToolIcons`. The ink is read from a tool bar the
walk colors, so a re-ink before the walk keeps the light-row ink;
`ChromeColorsTest` fails in that order.

A checked tool item draws the checked background of the native theme the
window was built under, not the painted row (measured on Windows 11 and
`windows-2025` after a start; a rebuilt window is built like a started one):
`#455160` after a dark start under dark, `#c0c0c0` after a dark start under light,
`#cce8ff` after a light start under either. A disabled checked item draws the
same after a dark start and the row after a light start. `add(Icons)` calls
`lightChecked(nativeDark)`, so an enabled checked item takes the light ink only
after a dark start under dark (5.45:1, 6.25:1, 8.97:1 in the three cases); its
faded disabled copy keeps the set's ink. `ToolIcons` re-inks a check item on its
`SWT.Selection`. Code checks or unchecks an inked item only through
`ToolIcons.check`, because `setSelection` sends no event.

Limits on win32, all drawn by SWT or the native control from colors no public
API sets:

- The 1 px `COLOR_WIDGET_NORMAL_SHADOW` line `CTabFolder` draws under its strip
  and around the selected tab, read in package-private renderer code.
- A 4 px `#6d6d6d` band along the top of the selected tab (2.97:1 on the row),
  `CTabFolder`'s selection highlight in the same renderer.
- The top row's separators: a `Label` with `SWT.SEPARATOR` draws an etched
  pair, 1 px `#a0a0a0` and 1 px `#ffffff`, whatever its colors.
- A table header under dark dialog colors: once `setHeaderBackground` is set,
  SWT 3.135.0 custom-draws the header with `DrawText` and no
  `DT_END_ELLIPSIS`, 6 px in from each column edge, and aligns column 0 by the
  column's style while win32 draws column 0's cells left. Every QLogg table
  creates column 0 `SWT.LEFT` (`TableColumn.setAlignment` ignores column 0 on
  win32) and opens each column at least as wide as its header text
  (`TableFit.headerMinima`). `Win32DialogColors` calls `Win32HeaderText.follow`
  beside the header colors: at each column resize a column narrower than its
  text shows the longest prefix that fits followed by `...`, with the whole
  text as the column's tool tip and under a data key that `HeaderTexts.full`
  reads; `TableFit` measures with it.
- A bordered field's 1 px frame, set when the field is created: after a light
  start it stays `#ffffff` around the dark field under dark; after a dark start
  it is `#646464` (measured, `windows-2025`). Turning the dark preference on
  around `setBackground` leaves the frame white and draws the field native dark
  (`#212121`) after the switch back to light.

### Combo list selection

Under the dark native setting Windows 11 draws the selected row of a drop-down
combo's open list, which is also the hover row, white on white, read-only or
editable, whatever the combo's colors (1.00:1, measured at 150% on build
26300); `windows-2025` at 100% draws it `#0078d4` with white text. Neither
`SetWindowTheme` nor the `WM_CTLCOLORLISTBOX` answer changes that row. Every
`Combo` is created with `ComboListSelection.PLATFORM.draw`.
`Win32ComboListSelection` subclasses the list window through `Win32ListBox`,
which reaches SWT's `internal.Callback` and `internal.win32.OS` under an
`ArchitectureTest` exception, and while `Hook.dark()` holds paints the selected row in
`Palette.DARK`'s selection, `#2f5f88` with white text, after each `WM_PAINT`
and `WM_TIMER`, after each selection move, which the list draws without one,
and into the `WM_PRINTCLIENT` DC. The opening slide renders the list through
`WM_PRINT`/`WM_PRINTCLIENT` and shows it before any `WM_PAINT` (traced at 150%
on build 26300), so the hook also posts the list a private message
(`WM_APP + 0xf42`) when it shows and invalidates the list when that arrives; a
combo `SWT.Selection` on the open list (a keyboard move) invalidates it too.
A dialog combo's side is fixed when it is created, as its dialog's colors are
and as a `List`'s is: in a dialog `DialogColors.apply` colored, `dark()` is
true for the combo's life, so the row stays dark after a live switch to light.
In a dialog created light, the native list takes the white-on-white row at a
live switch to dark (OBERON, 150% and 100%), so under the dark native setting
the hook paints the row in `SWT.COLOR_LIST_SELECTION` and
`COLOR_LIST_SELECTION_TEXT`, the selection the light list draws (`#0078d7`
with white text on Windows 11, `#0078d4` on `windows-2025`); under light it
leaves the native drawing. `ListHook.dark()` and `colors()` choose the side.
A main window combo (the search row's combos) reads
`DialogColors.nativeDark` per message, because the main window follows a live
switch: the switch applies at the next paint, and under light the native
selection stays. `Win32DialogColors.dialog` tells the two apart: `apply` marks
every shell it runs on. Disposing the combo restores the window procedure. A
`SWT.SIMPLE` combo is left native. If an SWT upgrade drops a member, one WARN
is logged, the native selection stays and `Win32ListSelectionTest` fails
on win32. `NativeComboListSelection` does nothing on GTK and cocoa.

### List selection

Under the dark native setting Windows 11 draws the selected row of a `List`
solid `#ffffff` below the dialog's white text, focused or not (OBERON, 150% and
100%). Every `List` is created with `Dialogs.list`, which calls
`ListSelection.PLATFORM.draw`; `ArchitectureTest.listsOnlyThroughDialogs`
rejects `new List` elsewhere. `Win32ListSelection` subclasses the ListBox
window through `Win32ListBox`, the subclass and row painting it shares with
`Win32ComboListSelection`, and paints the selected row `#626262` with focus
and `Win32UnfocusedSelection.BACKGROUND` (`#747474`) without, white text, as a
dark dialog tree draws its rows. It paints after each `WM_PAINT`, into the
`WM_PRINTCLIENT` DC, and after any message that moves the selection or the
focus. The side is fixed when the list is created, as its dialog's colors are:
only a list in a dialog `DialogColors.apply` colored is subclassed, and it
keeps the painted row after a live switch to light; a list in a dialog created
light stays native after a switch to dark. A dialog combo takes its side the
same way ("Combo list selection"). The painted row covers the native focus outline. Disposing the list restores
the window procedure; a `SWT.MULTI` list is left native. If an SWT upgrade
drops a member, one WARN is logged, the native selection stays and
`Win32ListSelectionTest` fails on win32. `NativeListSelection` does nothing on
GTK and cocoa.

## Dialog colors

`Dialogs.shell` calls `DialogColors.apply` on every dialog and tool window
shell before its children exist; a shell built another way calls it too.
`DialogColors.platform` returns `PlatformTraits.dialogColors` of the running
platform:

| Class | When | Colors |
|---|---|---|
| `Win32DialogColors` | win32; colors a dialog created while `DialogColors.nativeDark` holds | `WINDOW` `#202020`, fields and lists `#2b2b2b`, read-only combos `#333333`, text `#ffffff`, links the system link color raised to 4.5:1 on the body under the dark side; none set under the light one |
| `NativeDialogColors` | GTK, cocoa | None set |

A dark win32 start draws the title bar (`#202020`), push buttons (`#333333`
face, white text) and read-only combos dark and every other surface light
(measured, SWT 3.135.0, `windows-2025`). `apply` gives the shell the title bar
color with `INHERIT_DEFAULT`; when the shell shows (`SWT.Show`) it sets each
control's colors where they are still the defaults, so a color the dialog set
stays. Push buttons keep the native dark drawing. A read-only combo takes the
push button face, `#333333` with white text (12.63:1), since the dark native
theme draws its closed field and leaves its drop-down list white unless the
combo has colors. A link takes `Contrast.readable` of `COLOR_LINK_FOREGROUND`
on `#202020`: `#3f8cd8` (4.62:1) when that color is `#0066cc`. Both are
computed, not yet measured on Windows. A field's text draws white on `#2b2b2b`
(14.16:1), read-only and multi-line included. An empty, unfocused single-line
`Text` is filled with the field color and its hint (`setMessage`) drawn in
`#9a9a9a` (5.03:1) by `Win32EmptyField`, an `SWT.Paint` listener over the
native hint, which is `#6d6d6d` read-only (SWT's `COLOR_GRAYTEXT`, 2.74:1) and
`#575757` editable (1.96:1), measured on `windows-2025`. The field repaints on
focus and modification. A `ToolBar` takes the body as its own color, not only
through `INHERIT_DEFAULT`: under the dark native theme SWT fills a hot or
checked item with a shade of the bar's own color, `#d8d8d8` from the default
`#f0f0f0` (measured on Predefined Filters' Add, Windows 11 at 150%), and
`#363636` from the body (computed; `Win32DarkDialogsTest` reads it below
`#606060`). A control created after the dialog opens is colored at its
`SWT.Skin` event.

A table with grid lines calls `Dialogs.gridLines` in place of
`setLinesVisible(true)`. The win32 list view draws its native grid lines in
`COLOR_3DFACE`, `#f0f0f0` under the dark side (measured, Windows 11 at 150%),
and no public API colors them. In a dialog `Win32DialogColors` colored,
`gridLines` turns the native lines off and `Win32GridLines`, an `SWT.Paint`
listener, draws `#404040` rules (1.37:1 on `#2b2b2b`; light lines are 1.14:1
on white) one point above each shown item's top after the first, from
`TableItem.getBounds`, and the last pixel column of each column. Below the last
item the rules step by the difference of the last two tops. A multiple of
`getItemHeight` drifts: at 150% a 29 px row is 19.33 pt, rounded to 19, so the
rules fall half a pixel behind per row and cross the text by row 12 (measured,
Windows 11). Elsewhere the native lines show. A
dialog keeps its lines when the theme switches, as it keeps its colors.
Predefined Filters, the Preferences Shortcuts table and Keyboard Shortcuts
show grid lines.

SWT draws the header dividers of a table with header colors in `COLOR_3DFACE`
unless the Display data key
`org.eclipse.swt.internal.win32.Table.headerLineColor` holds a color. `apply`
sets it through `Win32HeaderLines`: `#404040` under dark (1.37:1 on the
`#2b2b2b` header), cleared under light once no dark dialog is open. The key is
Display-wide, and only a dark dialog's tables have header colors. This is a
listed Public SWT API exception; ArchUnit `headerLineKeyOnlyInWin32HeaderLines`
keeps the key in that class. If an SWT upgrade drops the key, the dividers draw
light and `DialogGridLinesTest` fails on win32.

| Stays native | Drawn |
|---|---|
| `Spinner` arrows | Light buttons beside the dark field |
| Disabled text | The system gray: a disabled label `#808080` on the body (4.1 contrast). `Dialogs.disabledText` (`#9a9a9a`) colors only the rows the command palette draws disabled |
| `List` selection | Painted by `Win32ListSelection` ("List selection") |
| `Table` and `Tree` selection | Focused: native dark with white text, `#223e55` (table) and `#626262` (tree), 6.10:1 or more (measured, `windows-2025`). Unfocused: native `#252525` and `#333333`, 1.1:1 on the field, so `Win32UnfocusedSelection` draws `#747474` with white text (3.03:1 on the field, text 4.67:1) |
| `Browser` (the user guide) | The page's own colors |
| `FileDialog`, `ColorDialog` | The system dialogs |

A native `TabFolder` keeps a light tab row and a white frame around the page
after a dark start, and no public API colors either. Preferences and Configure
Highlighters therefore take their folder from `Dialogs.tabs`, which calls
`PlatformTraits.dialogTabs`; `ArchitectureTest.tabFoldersOnlyThroughDialogTabs`
fails a `TabFolder` created outside `ui.swt.platform`:

| Class | When | Folder |
|---|---|---|
| `Win32DialogTabs` | win32 under the dark side (`DialogColors.nativeDark`, read when the dialog is built) | A flat `CTabFolder` without a border: tab row, unselected tabs and pages `#202020`, selected tab `#2b2b2b`, tab text `#ffffff` (16.3:1 and 14.2:1) |
| `NativeDialogTabs` | win32 under the light side, GTK | The native `TabFolder` |
| `CocoaDialogTabs` | cocoa | The native `TabFolder`, keeping keyboard focus on a control across a tab change |

On cocoa SWT hides the old page on a tab change, and when no ancestor of the
focused control takes focus, AppKit's first responder becomes the window:
`NSTabView` takes no focus with Full Keyboard Access off. The next Ctrl+PageDown
then reaches no control. `CocoaDialogTabs` runs on each tab change: when no
visible control has focus, the new page's first control that takes focus gets
it, else a childless 1x1 `Canvas` below the pages, whose page traversals reach
the folder. A page of check boxes has no control that takes focus there.

`CTabFolder` draws the selected tab `#2b2b2b` only while focus is in the
folder, and `#202020` while a dialog button holds it. It outlines the selected
tab, separates unselected ones and draws its selection bar in system colors (`COLOR_WIDGET_NORMAL_SHADOW`, then
`COLOR_LIST_SELECTION` with focus in the folder or
`COLOR_WIDGET_DISABLED_FOREGROUND` without), read in package-private renderer
code. Ctrl+PageDown and Ctrl+PageUp from a page control reach either folder
through SWT's traversal and wrap at the ends; Ctrl+Tab changes no tab in either,
and a mnemonic reaches the shown page's buttons only (measured on
`windows-2025`). `DialogTabsTest` posts these keys to both folders on win32 and
fails where they differ. No tab title carries a mnemonic. After a dark start the
row and the folder's edge read `#202020` and the selected tab, with focus in a
page, `#2b2b2b` (measured, `windows-2025`).

The colors follow `DialogColors.NATIVE_DARK`, which a switch sets: a dialog
opened after a switch takes the new side. A window with a modal dialog open
waits for its rebuild until the dialog closes, and the dialog keeps the colors
it was created with.
`Dialogs.background` reports a new dialog's background, from which About picks
its logo, and `Dialogs.disabledText` the gray of a disabled table row.

## Tab strip colors

`TabStrip.colorTabs` colors the main, results and scratchpad tab strips from
the palette on every platform, at creation and on each palette change.
`CTabFolder` copies the Display's list colors when created, and the Display
reads them once, so an uncolored strip keeps them through a theme change
(black on `#2c2c2c` under Dark on a light GTK desktop).

| Tab | Background | Label and close button |
|---|---|---|
| Selected, focus inside the folder | `background` | `text` |
| Selected, focus elsewhere | `gutter` | `text` |
| Unselected | `gutter` | `text` |

- `CTabFolder` draws the selection background only while focus is inside the
  folder and keeps one selection foreground for both cases, so that
  foreground must read on `gutter` and on the selection background. `text` on
  `background` and `gutter` reads at 4.5:1 or more in every bundled scheme;
  `text` on `selection` falls to 2.86:1 (Nightwater).
- The strips follow the palette, not `ui.theme`: a fixed scheme keeps them
  through a theme change.
- `colorTabs` sets `ChromeColors.PALETTE_COLORED`, and `CocoaChrome` leaves such
  a control alone: an appearance change does not change the palette, so nothing
  would restore the colors it wrote.

## Icons

| Class | Images |
|---|---|
| `Icons` | The 16 to 256 px application PNGs rendered by `tools/icons.sh`. `DARK` is the window and taskbar icon; `LIGHT` is for in-app use with the light palette. Owned by the views that create them |
| `ToolIcons` | The toolbar, toggle, dialog and menu item icons: `icons/tool/NAME-light-16.png` (dark ink) or `NAME-dark-16.png` (light ink), with the 32 px PNG at 200% zoom. Created on first use and disposed with the owner |

`ToolIcons` picks the ink from the background of its ink source, the owner or
a tool bar given as `ToolIcons(Control, Control)`, because toolbars are native
or, on Windows under dark, painted by `Win32ChromeColors`. `reink` reads the source
through `PlatformTraits.shownBackground` and swaps the image of every item
given to `apply`. It runs on `SWT.Settings`, from the Display or from
`CocoaChrome` on the owner, and on the UI turn after a palette change, once
native controls have restyled.

GTK refreshes `getBackground` and sends `SWT.Settings` only on the first
`Display.sleep` after `setDarkThemePreferred`, so on GTK `shownBackground`
prints a visible source into a 2 px image and reads its corner pixel; a hidden
source, or a `print` that fails (GTK 4), falls back to `getBackground`. A plain
`Composite` prints white in either theme, so the search row and Predefined
Filters take a tool bar of theirs as the source. win32 and cocoa read
`getBackground`.

A menu item given to `apply(MenuItem, String)` follows the same ink. On GTK a
disabled menu item on a dark background shows a copy at 40% opacity
(`showState`), because GTK's disabled effect brightens the image there.
`fadeDisabled(true)` gives every tool item of a set the same faded copy through
`setDisabledImage`; `Win32ChromeColors` turns it on under dark, where SWT's
disabled copy maps the light ink to a gray brighter than the enabled ink.
Measured on `#202020` over every icon by `ChromeColorsTest` on `windows-2025`
and `windows-11-arm`: enabled 9.64:1 or more, SWT's disabled copy 10.88:1 or
more, the faded copy 2.67:1 to 2.92:1. `fadeDisabled(false)`
returns SWT's own disabled image. A set
created with `ToolIcons(Control, boolean)` keeps that ink; `MenuIcons` uses it
on win32 with the side pop-up menus show (`Win32MenuThemes.shownDark`). Menu
icons: [menus.md](menus.md).

A set created with a background color (`ToolIcons(Control, Color)`) is for a
palette-painted surface, the tab strip buttons: `reink` leaves it alone, and
`follow` takes the ink of the palette's next `gutter`. Measured under Xvfb, GTK
3: the Close icon against the strip is 8.77:1 (Nightwater) to 12.72:1
(Phosphor) across Follow Theme, Light, Dark and the nine bundled schemes, in
light and dark chrome.

The About dialog picks the logo variant from the background of the label that
holds it, not from the palette or the main shell.

### GTK 4 tool item size

SWT 3.135.0 on GTK 4 wraps a `ToolItem`'s GtkPicture in a box that measures 0
high for any width under GTK 4.14, so the tool bar lays an image-only item out
at its padding (12 px, or 4 px for a check item) and shows no icon (measured
under Xvfb, every QLogg tool bar). `ToolIcons.apply` calls
`ToolItemSizing.PLATFORM.keepIconSize` before `setImage`.
`PlatformTraits.toolItemSizing` returns `Gtk4ToolItemSizing` on GTK 4, which
turns off the picture's shrinking through SWT internals
(`ToolItem.imageHandle`, `GTK4.gtk_picture_set_can_shrink`), and the items
measure at least 20 px each way; `NativeToolItemSizing` does nothing on the
other backends. This is a listed Public SWT API exception, and ArchUnit
`reflectionOnlyInExceptionClasses` keeps reflection to the exception classes.
If an SWT upgrade renames either internal, the items fall back to SWT's layout and
`TabStripButtonsTest.everyToolItemIsLargerThanItsIcon` fails in the GTK 4 lane.
Every item and button image goes through `ToolIcons`, which ArchUnit
`iconsOnlyThroughToolIcons` enforces; the tab dots (`TabStrip`, `TabList`) and
`NativeCellImages`, which sets the Predefined Filters check box images, are the
exemptions.

### Icon build

`src/icons/*.svg` is the hand-authored icon set (16 px grid, strokes in
`currentColor`). At `generate-resources` the build copies JSVG (MIT, build
only) and runs `src/build/IconRenderer.java` with the Java source launcher,
which renders every SVG in both inks at 16 and 32 px into
`target/generated-resources/icons`; the jar includes them.
