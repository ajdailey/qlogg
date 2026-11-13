# Theme and icons

`SwtThemeService` applies `ui.theme` to native widgets and reports system
appearance changes; `CocoaChrome` recolors native widgets on macOS;
`DialogColors` colors dialogs on a dark Windows start;
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
| `LogCanvas` (main and filtered), gutter, `OverviewBar`, `TabDots`, the main, results and scratchpad tab strips, `TabStripButtons` and their icons' ink | The palette |
| Toolbars, search row, status bar, sash, dialogs, `MarksWindow` table, the scratchpad's text | Native, `ui.theme`; `ToolIcons` inks from the native background |
| `CocoaChrome` shell and list colors | The system appearance; skips `PALETTE_COLORED` folders |
| Dialogs and tool windows on win32 under a dark start | `Win32DialogColors`; see [Dialog colors](#dialog-colors) |

## Native widget theme

| Platform | `Display.setDarkThemePreferred` | System appearance change |
|---|---|---|
| GTK | `SwtThemeService.preferDark` calls it; the GTK theme's dark variant applies at once | SWT reads the system setting once at Display creation, so a change made while QLogg runs applies at the next start |
| win32 | Widgets read it only when created, so `SwtThemeService.open` sets it before the first Shell, for system from the system setting at that moment, and `preferDark` does nothing; `showsNow` reports whether the startup setting matches a request | `SWT.Settings`; native controls take a system change at the next start |
| cocoa | Never called: native widgets follow the system appearance | No `SWT.Settings` is sent and SWT keeps its startup colors (measured, macOS 15), so the service reads `Display.isSystemDarkTheme()` every second and `CocoaChrome` sets each shell's, list's and field's colors to the current appearance while it differs from the startup one |

`SwtThemeService` reads `Display.isSystemDarkTheme()` and turns `SWT.Settings`
into a change event.

- win32: under a dark startup setting, check box labels draw near-white while
  the main window's pages stay light. Every check box therefore comes from
  `SwtThemeService.check`, which creates it with the theme off, except in a
  dialog with `Win32DialogColors`, whose page is dark.
- cocoa: do not set an application appearance. cocoa SWT caches its widget
  colors under the system appearance, and a different application appearance
  draws text in that appearance over the cached backgrounds (white on light
  gray).

## Dialog colors

`Dialogs.shell` calls `DialogColors.prepare` on every dialog and tool window
shell, and `Dialogs.open` calls `DialogColors.apply` before the shell shows. A
shell built another way calls both. `DialogColors.of` takes the class from
`PlatformTraits.dialogColors`:

| Class | When | Colors |
|---|---|---|
| `Win32DialogColors` | win32, native controls started dark | Body `#202020`, fields and lists `#2b2b2b`, text `#ffffff`, links `#99ebff` |
| `NativeDialogColors` | GTK, cocoa, win32 started light | None set |

A dark win32 start draws the title bar (`#202020`), push buttons (`#333333`
face, white text) and read-only combos dark and every other surface light
(measured, SWT 3.135.0, `windows-2025`). `Win32DialogColors` gives the shell the
title bar color with `INHERIT_DEFAULT`, then sets each control's colors where
they are still the defaults, so a color the dialog set stays. Push buttons and
read-only combos keep the native dark drawing. A control created after the
dialog opens is colored at its `SWT.Skin` event.

| Stays native | Drawn |
|---|---|
| `TabFolder` tab row and frame | Light; the selected page is dark |
| `Table` grid lines and header dividers | Light lines between dark rows and header cells |
| `Spinner` arrows | Light buttons beside the dark field |
| A hovered or focused `ToolItem` | A light highlight under the light-ink icon (seen on Predefined Filters' focused Add; hover assumed the same) |
| Disabled text | The system gray: a disabled label `#808080` on the body (4.1 contrast). `Dialogs.disabledText` (`#9a9a9a`) colors only the rows the command palette draws disabled |
| `Browser` (the user guide) | The page's own colors |
| `FileDialog`, `ColorDialog` | The system dialogs |

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
- `colorTabs` sets `CocoaChrome.PALETTE_COLORED`, and `CocoaChrome` leaves such
  a folder alone: an appearance change does not change the palette, so nothing
  would restore the colors it wrote.

## Icons

| Class | Images |
|---|---|
| `Icons` | The 16 to 256 px application PNGs rendered by `tools/icons.sh`. `DARK` is the window and taskbar icon; `LIGHT` is for in-app use with the light palette. Owned by the views that create them |
| `ToolIcons` | The toolbar, toggle and dialog icons: `icons/tool/NAME-light-16.png` (dark ink) or `NAME-dark-16.png` (light ink), with the 32 px PNG at 200% zoom. Created on first use and disposed with the owner |

`ToolIcons` picks the ink from the owning control's background, because
toolbars are native: on Windows they stay light under the dark palette.
`reink` reads the background again and swaps the image of every item given to
`apply`. It runs on `SWT.Settings`, from the Display or from `CocoaChrome` on
the owner, and on the UI turn after a palette change, once native controls
have restyled.

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
