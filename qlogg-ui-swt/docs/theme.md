# Theme and icons

`SwtThemeService` applies `ui.theme` to native widgets and reports system
appearance changes; `CocoaChrome` recolors native widgets on macOS;
`MainWindow.colorTabs` colors the tab strips; `Icons` and `ToolIcons` load the
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
| `LogCanvas` (main and filtered), gutter, `OverviewBar`, `TabDots`, the main, results and scratchpad tab strips | The palette |
| Toolbars, search row, status bar, sash, dialogs, `MarksWindow` table, the scratchpad's text | Native, `ui.theme`; `ToolIcons` inks from the native background |
| `CocoaChrome` shell and list colors | The system appearance; skips `PALETTE_COLORED` folders |

## Native widget theme

| Platform | `Display.setDarkThemePreferred` | System appearance change |
|---|---|---|
| GTK | `SwtThemeService.preferDark` calls it; the GTK theme's dark variant applies at once | SWT reads the system setting once at Display creation, so a change made while QLogg runs applies at the next start |
| win32 | Widgets read it only when created, so `SwtThemeService.open` sets it before the first Shell and `preferDark` does nothing; `showsNow` reports whether the startup setting matches a request | `SWT.Settings` |
| cocoa | Never called: native widgets follow the system appearance | No `SWT.Settings` is sent and SWT keeps its startup colors (measured, macOS 15), so the service reads `Display.isSystemDarkTheme()` every second and `CocoaChrome` sets each shell's, list's and field's colors to the current appearance while it differs from the startup one |

`SwtThemeService` reads `Display.isSystemDarkTheme()` and turns `SWT.Settings`
into a change event.

- win32: under a dark startup setting, check box labels draw near-white while
  dialog pages stay light. Every check box therefore comes from
  `SwtThemeService.check`, which creates it with the theme off.
- cocoa: do not set an application appearance. cocoa SWT caches its widget
  colors under the system appearance, and a different application appearance
  draws text in that appearance over the cached backgrounds (white on light
  gray).

## Tab strip colors

`MainWindow.colorTabs` colors the main, results and scratchpad tab strips from
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

### Icon build

`src/icons/*.svg` is the hand-authored icon set (16 px grid, strokes in
`currentColor`). At `generate-resources` the build copies JSVG (MIT, build
only) and runs `src/build/IconRenderer.java` with the Java source launcher,
which renders every SVG in both inks at 16 and 32 px into
`target/generated-resources/icons`; the jar includes them.
