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
| Top row, search row, quick find bar, status bar, sash and their fields on win32 | `Win32ChromeColors` from `Palette.DARK` while `ui.theme` resolves to dark; see [Chrome colors](#chrome-colors) |
| `CocoaChrome` shell, list and header colors | The pinned side, `ui.theme`; skips `PALETTE_COLORED` controls |
| Dialogs and tool windows on win32 under the dark side | `Win32DialogColors`, `Win32DialogTabs`; see [Dialog colors](#dialog-colors) |

## Native widget theme

`SwtThemeService` reaches native widgets through the platform's `ThemeFollow`:
`GtkThemeFollow`, `Win32ThemeFollow` or `CocoaThemeFollow`.

| Platform | `Display.setDarkThemePreferred` | System appearance change |
|---|---|---|
| GTK | `SwtThemeService.open` calls it for Light and Dark and `preferDark` at each switch; the GTK theme's dark variant applies at once | SWT reads the system setting once at Display creation, so a change made while QLogg runs applies at the next start |
| win32 | Widgets read it only when created, so `SwtThemeService.open` sets it before the first Shell, for system from the system setting at that moment; at a switch `showOnWindows` sets the new side and rebuilds every main window ([Window rebuild](#window-rebuild)) | The service reads `Display.isSystemDarkTheme()` every 500 ms, because SWT sends `SWT.Settings` only 2 s after `WM_SETTINGCHANGE` (`Display.SETTINGS_DELAY`); under System a change rebuilds the windows like a switch |
| cocoa | `start` pins the application appearance to the side `ui.theme` resolves to in every mode, System from the system setting at that moment; at a switch `showOnWindows` pins the new side and `CocoaChrome` recolors every shell ([Cocoa chrome](#cocoa-chrome)); no rebuild | No `SWT.Settings` is sent (measured, macOS 15), so the service reads `Display.isSystemDarkTheme()` every 500 ms; under System a change re-pins like a switch, since SWT has no call that unpins |

`SwtThemeService` reads the system setting through `NativeTheme.systemDark`
(`Display.isSystemDarkTheme()`) and turns `SWT.Settings`
into a change event. Its `preferDark` calls `ThemeFollow.preferDark` and then
`ThemeFollow.showOnWindows` with the `WindowRebuild` the launcher set
(`WindowRebuild.NONE` until then) on every platform.

## Window rebuild

On win32 the menu bar, title bar, scroll bars and buttons take the theme only
when created: a forced repaint, `setMenuBar`, a resize or a minimize and
restore leaves the menu bar `#ffffff` after `setDarkThemePreferred(true)`
(measured, `windows-2025`). `Win32ThemeFollow.showOnWindows` therefore sets the
preference and `DialogColors.NATIVE_DARK` when the side differs from the
recorded one, and calls `WindowRebuild.rebuild`.

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
| Marks, Search All Files, Search in Folder, scratchpad, Show Log, Keyboard Shortcuts, user guide | Reopened at their bounds with the scratchpad's tabs and text, the log text, the shortcuts filter and the guide's last location |
| Command palette, context menus, a status notice | Closed |

A rebuild of one window takes 187 to 334 ms on `windows-2025` (1, 5 and 20
tabs; the tab count barely matters, since only the shown tab has views), over
the 100 ms UI bound; a switch is rare and user-initiated, and the stall is
accepted. The user guide reloads at its last anchor; JavaScript is off, so the
scroll position within a section is not kept.

- win32: while native controls are dark, check box labels draw near-white, which
  a light page cannot show. Every check box therefore comes from
  `DialogColors.check` (through `Dialogs.check`): `Win32DialogColors` creates it
  with the theme off, except in a dialog it colors, whose page is dark. On the quick find bar
  `Win32ChromeColors` gives an enabled box's label the palette text under dark.
- cocoa: pin the application appearance only together with `CocoaChrome`.
  cocoa SWT caches its widget colors under the system appearance at Display
  creation, and a pinned appearance that differs draws native text over the
  cached backgrounds (white on light gray).

## Cocoa chrome

While the pinned side differs from the side SWT cached its colors under,
`CocoaChrome` gives each shell the window color of the pinned side with
`INHERIT_FORCE`, from constants measured on macOS 15. A `TabFolder` stops that
inheritance, so check boxes get a transparent background over its native panel
and its titles the text color; tables, trees, lists, combos, fields, spinners
and a `CTabFolder` get list colors. A control with `PALETTE_COLORED` keeps its
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

- Menus and the menu bar follow the system appearance, not the pin. AppKit
  draws them whole, so they stay readable.
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
`PaletteListener.themeChanged`), every control under a surface takes
`Palette.DARK.gutter` (`#162638`, the tab strip's background) and
`Palette.DARK.text` (`#dce6f0`, 12.15:1 on it); a `Link` takes the system link
color moved toward white to 4.5:1; each composite takes `SWT.INHERIT_DEFAULT`,
so native push buttons show no light corners. A `Text` and an editable `Combo`
take `Palette.DARK.background` (`#1b2e44`, text 10.9:1) and `Palette.DARK.text`, and
so does a read-only `Combo`: the dark native theme draws its closed field and
leaves its drop-down list white unless the combo has colors. Under light
the colors are null and composites take `SWT.INHERIT_NONE`. Both directions
apply live. The colors follow `ui.theme`, not the `view.colors` scheme, as
native controls do.

`Win32ChromeColors` reads `DialogColors.nativeDark` at its first `apply` and
keeps it, one instance per window: the side the window's controls were created
under, also while the window waits for a rebuild with a dialog open. While that
side is dark, a field holding text keeps the dark colors under light: the
dark native field draws near-black under any light colors. A switch rebuilds
the window, so this holds only under a fixed `view.colors` scheme. An empty
field takes the system colors. A field is colored again on the `SWT.Modify`
that empties it or gives it text.

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
- The selected item of a combo's drop-down list under the dark theme at a
  fractional display scale draws white on white (1.00:1, measured at 150% on
  Windows 11 build 26300); the other items stay readable. Uncolored combos,
  colored ones and ones created while the dark theme is not preferred draw it
  the same. At 100% (`windows-2025`) and under light the row is `#0078d4` or
  `#0078d7` with white text (4.5:1).
- A bordered field's 1 px frame, set when the field is created: after a light
  start it stays `#ffffff` around the dark field under dark; after a dark start
  it is `#646464` (measured, `windows-2025`). Turning the dark preference on
  around `setBackground` leaves the frame white and draws the field native dark
  (`#212121`) after the switch back to light.

## Dialog colors

`Dialogs.shell` calls `DialogColors.prepare` on every dialog and tool window
shell, and `Dialogs.open` calls `DialogColors.apply` before the shell shows. A
shell built another way calls both. `DialogColors.of` takes the class from
`PlatformTraits.dialogColors`:

| Class | When | Colors |
|---|---|---|
| `Win32DialogColors` | win32; colors a dialog prepared while `DialogColors.nativeDark` holds | Body `#202020`, fields and lists `#2b2b2b`, text `#ffffff`, links `#99ebff` under the dark side; none set under the light one |
| `NativeDialogColors` | GTK, cocoa | None set |

A dark win32 start draws the title bar (`#202020`), push buttons (`#333333`
face, white text) and read-only combos dark and every other surface light
(measured, SWT 3.135.0, `windows-2025`). `Win32DialogColors` gives the shell the
title bar color with `INHERIT_DEFAULT`, then sets each control's colors where
they are still the defaults, so a color the dialog set stays. Push buttons keep
the native dark drawing. A read-only combo takes the field colors, since the
dark native theme draws its closed field and leaves its drop-down list white
unless the combo has colors. A field's text draws white on `#2b2b2b`
(14.16:1), read-only and multi-line included. An empty, unfocused single-line
`Text` is filled with the field color and its hint (`setMessage`) drawn in
`#9a9a9a` (5.03:1) by `Win32EmptyField`, an `SWT.Paint` listener over the
native hint, which is `#6d6d6d` read-only (SWT's `COLOR_GRAYTEXT`, 2.74:1) and
`#575757` editable (1.96:1), measured on `windows-2025`. The field repaints on
focus and modification. A control created after the
dialog opens is colored at its `SWT.Skin` event.

| Stays native | Drawn |
|---|---|
| `Table` grid lines and header dividers | Light lines between dark rows and header cells |
| `Spinner` arrows | Light buttons beside the dark field |
| A hovered or focused `ToolItem` | A light highlight under the light-ink icon (seen on Predefined Filters' focused Add; hover assumed the same) |
| Disabled text | The system gray: a disabled label `#808080` on the body (4.1 contrast). `Dialogs.disabledText` (`#9a9a9a`) colors only the rows the command palette draws disabled |
| `Table` and `Tree` selection | Native dark with white text: focused `#223e55` (table) and `#626262` (tree), unfocused `#252525` and `#333333`, 6.10:1 or more (measured, `windows-2025`) |
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
| `NativeDialogTabs` | win32 under the light side, GTK, cocoa | The native `TabFolder` |

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
it was prepared with.
`Dialogs.background` reports a new dialog's background, from which About picks
its logo, and `Dialogs.disabledText` the gray of a disabled table row.

## Tab strip colors

`TabStrip.colorTabs` colors the main, results and scratchpad tab strips from
the palette on every platform, at creation and on each palette change.
`CTabFolder` copies the Display's list colors when created, and the Display
reads them once, so an uncolored strip keeps them through a theme change
(black on `#2c2c2c` under Dark on a light GTK desktop).

- The selection background shows only while focus is inside the folder;
  elsewhere the selected tab has the strip's background and keeps the
  selection foreground. That foreground is therefore `text`, not
  `selectionText`.
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
Measured under Xvfb, GTK 3, on `#162638` over every icon (SWT 3.135.0 uses the
same `IMAGE_DISABLE` transform on win32): enabled 9.06:1 or more, SWT's disabled
copy 10.14:1 or more, the faded copy 2.63:1 to 2.87:1. `fadeDisabled(false)`
returns SWT's own disabled image. A set
created with `ToolIcons(Control, boolean)` keeps that ink; `MenuIcons` uses it
on win32. Menu icons: [menus.md](menus.md).

A set created with a background color (`ToolIcons(Control, Color)`) is for a
palette-painted surface, the tab strip buttons: `reink` leaves it alone, and
`follow` takes the ink of the palette's next `gutter`. Measured under Xvfb, GTK
3: the Close icon against the strip is 7.79:1 (Garden Light) to 12.72:1
(Phosphor) across Follow Theme, Light, Dark and the nine bundled schemes, in
light and dark chrome.

The About dialog picks the logo variant from the background of the label that
holds it, not from the palette or the main shell.

### GTK 4 tool item size

SWT 3.135.0 on GTK 4 wraps a `ToolItem`'s GtkPicture in a box that measures 0
high for any width under GTK 4.14, so the tool bar lays an image-only item out
at its padding (12 px, or 4 px for a check item) and shows no icon (measured
under Xvfb, every QLogg tool bar). `ToolIcons.apply` calls
`ToolItemSizing.platform().keepIconSize` before `setImage`.
`PlatformTraits.toolItemSizing` returns `Gtk4ToolItemSizing` on GTK 4, which
turns off the picture's shrinking through SWT internals
(`ToolItem.imageHandle`, `GTK4.gtk_picture_set_can_shrink`), and the items
measure at least 20 px each way; `NativeToolItemSizing` does nothing on the
other backends. This is a listed Public SWT API exception, and ArchUnit
`reflectionOnlyInExceptionClasses` keeps reflection to the exception classes.
If an SWT upgrade renames either internal, the items fall back to SWT's layout and
`TabStripButtonsTest.everyToolItemIsLargerThanItsIcon` fails in the GTK 4 lane.
Every tool item image goes through `ToolIcons.apply`.

### Icon build

`src/icons/*.svg` is the hand-authored icon set (16 px grid, strokes in
`currentColor`). At `generate-resources` the build copies JSVG (MIT, build
only) and runs `src/build/IconRenderer.java` with the Java source launcher,
which renders every SVG in both inks at 16 and 32 px into
`target/generated-resources/icons`; the jar includes them.
