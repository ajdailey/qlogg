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
| `CocoaChrome` shell, list and header colors | The system appearance; skips `PALETTE_COLORED` folders |
| Dialogs and tool windows on win32 under a dark start | `Win32DialogColors`, `Win32DialogTabs`; see [Dialog colors](#dialog-colors) |

## Native widget theme

`SwtThemeService` reaches native widgets through the platform's `ThemeFollow`:
`GtkThemeFollow`, `Win32ThemeFollow` or `CocoaThemeFollow`.

| Platform | `Display.setDarkThemePreferred` | System appearance change |
|---|---|---|
| GTK | `SwtThemeService.open` calls it for Light and Dark and `preferDark` at each switch; the GTK theme's dark variant applies at once | SWT reads the system setting once at Display creation, so a change made while QLogg runs applies at the next start |
| win32 | Widgets read it only when created, so `SwtThemeService.open` sets it before the first Shell, for system from the system setting at that moment, and `preferDark` does nothing; `showsNow` reports whether the startup setting matches a request | The service reads `Display.isSystemDarkTheme()` every 500 ms, because SWT sends `SWT.Settings` only 2 s after `WM_SETTINGCHANGE` (`Display.SETTINGS_DELAY`); native controls take a system change at the next start |
| cocoa | Never called: native widgets follow the system appearance | No `SWT.Settings` is sent and SWT keeps its startup colors (measured, macOS 15), so the service reads `Display.isSystemDarkTheme()` every 500 ms and `CocoaChrome` sets each shell's, list's and field's colors to the current appearance while it differs from the startup one. Table and Tree headers get explicit colors of the current appearance on every change, never null (null colors draw blank labels in dark), and a Table's header is hidden and shown again so it repaints, the area right of the last column included |

`SwtThemeService` reads `Display.isSystemDarkTheme()` and turns `SWT.Settings`
into a change event.

- win32: under a dark startup setting, check box labels draw near-white, which
  a light page cannot show. Every check box therefore comes from
  `DialogColors.check` (through `Dialogs.check`): `Win32DialogColors` creates it
  with the theme off, except in a dialog it colors, whose page is dark. On the quick find bar
  `Win32ChromeColors` gives an enabled box's label the palette text under dark.
- cocoa: do not set an application appearance. cocoa SWT caches its widget
  colors under the system appearance, and a different application appearance
  draws text in that appearance over the cached backgrounds (white on light
  gray).

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
take `Palette.DARK.background` (`#1b2e44`, text 10.9:1) and `Palette.DARK.text`. Under light
the colors are null and composites take `SWT.INHERIT_NONE`. Both directions
apply live. The colors follow `ui.theme`, not the `view.colors` scheme, as
native controls do.

After a dark start (`DialogColors.nativeDark`), a field holding text keeps the
dark colors under light until the next start: the dark native field draws
near-black under any light colors. An empty field takes the system colors. A
field is colored again on the `SWT.Modify` that empties it or gives it text.

`MainWindow` registers the top row, the search row, the quick find bar, the
status bar and each sash of the split. The fields are the path field (read-only),
the pattern and range combos, the two context fields and the quick find field.
The walk does not enter push and arrow buttons, a read-only `Combo`, `List`,
`Table`, `Tree`, `Spinner`, `ProgressBar`, `StyledText`, `Browser`,
`CTabFolder` or a control with `ChromeColors.PALETTE_COLORED`; the visibility
combo stays native. `TabStripButtons` paints itself from the palette and is not
registered.

`apply` colors the surfaces first, then calls `fadeDisabled` and `reink` on the
toolbar's and the search row's `ToolIcons`. The ink is read from a tool bar the
walk colors, so a re-ink before the walk keeps the light-row ink;
`ChromeColorsTest` fails in that order.

A checked tool item draws the checked background of the startup native theme,
not the painted row (measured on Windows 11 and `windows-2025`): `#455160`
after a dark start under dark, `#c0c0c0` after a dark start under light,
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
| `Win32DialogColors` | win32; colors only while `DialogColors.nativeDark` holds, read at each use | Body `#202020`, fields and lists `#2b2b2b`, text `#ffffff`, links `#99ebff` after a dark start; none set after a light one |
| `NativeDialogColors` | GTK, cocoa | None set |

A dark win32 start draws the title bar (`#202020`), push buttons (`#333333`
face, white text) and read-only combos dark and every other surface light
(measured, SWT 3.135.0, `windows-2025`). `Win32DialogColors` gives the shell the
title bar color with `INHERIT_DEFAULT`, then sets each control's colors where
they are still the defaults, so a color the dialog set stays. Push buttons and
read-only combos keep the native dark drawing. A field's text draws white on `#2b2b2b`
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
| `Win32DialogTabs` | win32 after a dark start (`DialogColors.nativeDark`, read when the dialog is built) | A flat `CTabFolder` without a border: tab row, unselected tabs and pages `#202020`, selected tab `#2b2b2b`, tab text `#ffffff` (16.3:1 and 14.2:1) |
| `NativeDialogTabs` | win32 after a light start, GTK, cocoa | The native `TabFolder` |

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

The colors follow the startup setting, as win32 native controls do: after a
theme change, open and newly opened dialogs keep them until the next start.
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
  a folder alone: an appearance change does not change the palette, so nothing
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
