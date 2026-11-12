# qlogg-ui-swt tests

Every test class is tagged `swt` and drives real SWT widgets over real
documents. Module overview: [README.md](README.md).

## Running

- Linux runs the `swt` tests when `DISPLAY` is set and skips them otherwise;
  run them under `xvfb-run -a`. Surefire sets `GDK_BACKEND=x11`, so the windows
  go to Xvfb even in a Wayland session. Windows and macOS always run them, and
  macOS runs them with `-XstartOnFirstThread`. A build with
  `-Dqlogg.target=<target>` compiles against that target's fragment and skips
  them.
- The `test-gtk4` CI job runs them with `SWT_GTK4=1`. A test that hits a known
  GTK 4 failure skips itself there through the test class `Gtk4`.
- The root POM sets `XDG_CONFIG_DIRS` to the test classes' `gtk-config`,
  whose `settings.ini` names Adwaita for GTK 3 and GTK 4. Ubuntu's
  `/etc/gtk-3.0/settings.ini` names Yaru, which the CI runner lacks, and a
  missing theme keeps native controls light under the dark setting (measured
  with `GTK_THEME` naming a missing theme: every dark test passed while native
  backgrounds read 246,245,244). A `settings.ini` in `~/.config/gtk-3.0` or
  `gtk-4.0` overrides the pin. `XDG_CONFIG_HOME` is not used: it hides
  `~/.config/ibus`, and without the ibus daemon a posted Alt accelerator does
  not fire under Xvfb, with or without the simple input method (measured on
  GTK 3). `GTK_THEME=Adwaita` is not used: it keeps native controls light under
  the dark setting (measured 246,245,244).
- `EventLoop.runUntil` dispatches events until a condition holds, with a bound
  and no fixed sleep.
- Tests post pointer events with `notifyListeners`. Xvfb's pointer sits over
  the test window, which receives real crossing and motion events while a test
  dispatches; a real motion between a posted press and release moves a
  `LogCanvas` drag to it. A test that dispatches during a posted drag posts
  through `PostedPointer`, which drops pointer events it did not post
  (`ViewFeaturesGuiTest`). A press and release posted with no dispatch between
  them needs no filter.
- SWT GTK caches window trim in `~/.swt/trims.prefs` (the test home under
  surefire): each new `Display` reads it and each dispose writes it, so a trim
  one test records sizes every later shell. Opening `WindowStackingTest`'s
  shell under Xvfb records a 94 px trim width, which narrows later client
  areas by 94 px; that test restores the file. A test that needs a canvas
  width grows the shell until the canvas reaches it (`LogViewTest`), since a
  shell size does not fix it.
- `HangWatchdog`, registered for every test in the module through
  `META-INF/services` and `junit-platform.properties`, prints every thread's
  stack to stderr when a test, its `@BeforeEach` and `@AfterEach` included,
  runs past `-Dqlogg.test.hangSeconds` (120 by default), and halts the test JVM
  5 s later. A test blocked in a native call on the main thread then ends the
  build with its stack in the log, and surefire reports the fork crash.
- Pixel assertions read the screen through `GC.copyArea` or a control through
  `Control.print`. On GTK, `Control.print` draws a tab strip the screen does not
  show.

## Screenshots

Some tests write PNGs when a property names a directory. `GuideScreenshotsTest`
runs only with one; the others run either way.

| Property | Class | Files |
|---|---|---|
| `-Dqlogg.guide.shots=<dir>` | `GuideScreenshotsTest` (`tools/screenshots.sh`) | The user guide's images |
| `-Dqlogg.shots=<dir>` | `TabMoveGuiTest` | `tabmove-source.png`, `tabmove-target.png` |
| `-Dqlogg.shots=<dir>` | `OptionsAndMenusTest` | `options.png`, `main.png` |
| `-Dqlogg.shots=<dir>` | `NavigationGuiTest` | `palette.png`, `palette-filtered.png`, the palette over a 1100x700 window |
| `-Dqlogg.shots=<dir>` | `FolderSearchGuiTest` | `folder-search.png`, `folder-search-opened.png` at 1100x700 |
| `-Dqlogg.shots=<dir>` | `MarkCommentsGuiTest` | `gutter.png`, `mark-comment.png`, `marks-list.png` |
| `-Dqlogg.shots=<dir>` | `RowPixelsTest` | `rows-plain.png`, `rows-cjk.png`, `rows-wrapped.png`, `rows-wrapped-selection.png` at 1100x700 |
| `-Dqlogg.qc.shots=<dir>` | `TabColorsGuiTest` | Each step of the theme sequence |
| `-Dqlogg.tabstrip.shots=<dir>` | `TabStripButtonsTest` | `tab-list-gtk3-light.png`, `-dark.png` (or `gtk4`), the screen with the tab list open; GTK 4 reads the X root window through `import` |

The probes `ScrollFrameProbe`, `OpenStallProbe` and `RowPaintProbe` are
benchmarks; `qlogg-bench/README.md` describes them.

## Coverage

| Class | Covers |
|---|---|
| `MainWindowTest` | The requested client size and location (needs X11, so Xvfb, for the position), a huge request clamped to the work area, a close request reaching the handler, the dispatcher dropping submissions after disposal |
| `LogViewTest` | Composes the window over a real document and drives it with SWT events: painted text and line-number pixels (`Control.print`), a selection of columns 150-160 at 6 pt lying on its glyphs, keys including `Ctrl+E Ctrl+T`, mouse selection and `Ctrl+C` to the clipboard, scroll bar drag and wheel, the menu bar's menus, cascades, order, labels and accelerators, the log view context menu's order, no mnemonic repeated in any built menu, cascade or context menu (each cascade filled as when shown), Go to Line, the Help windows and the About layout, every menu bar item showing one key after its tab (Find Next `F3`), the search bar with `N` and `Shift+N` walking its matches, and the busy cursor across overlapping operations |
| `FilteredPaneTest` | At a 50% split, so the setup's 4 filtered rows fit a 640x480 screen. The filtered pane over a real search: the search row in the bottom pane with its controls in order and the result tabs full width below it; a click on a row moving the main view to the line; visibility keys and the combo agreeing; the sash grip; `+` and `-` moving the sash and Save Splitter Position storing it; both views carrying the context menu and building it on the first right click; a double click selecting a word and `*` finding it through the quick find bar to an end-of-file notice; kept sets as tabs; 12 kept sets overflowing the results strip, its list button counting the hidden ones and listing every set, a choice showing the current search; the filters menu and history list; the Predefined Filters table editing a row in place, toggling Regex and applying; context rows painting the shade and a group rule; the range end field showing while Range is on; the accessible names of both views and the overview bar, the filtered view's with its match count until Clear Results |
| `FilteredMinimumGuiTest` | One file in a 640x480 window at the default split: the filtered view keeps at least 3 rows, through a resize to 640x420 and back with the wanted split unchanged, and after a sash drag to the bottom, which raises the wanted split; a 640x250 window keeps a row in each view |
| `TabsTest` | Two files: the top row's toolbar, path field and its menu, Stop and file fields; the tab row showing with two files and with one, hiding with none and showing again; the last file shown; selecting a tab showing its document and file fields; dragging a tab moving it, the dragged tab staying selected with its document in the views and the path field; closing and Close All; `F` following; the tab menu's items and a middle click closing tabs, the last one included, leaving the window open; a second tab menu reusing the first with new items; Reload and the document check items enabled after an open with no menu shown since; File > Close emptying and disabling the path field; a rename in the tab; the toolbar's Follow tracking the document; Window > Opened Files and Switch to File; dropped files opening; a drag of folders only getting no-drop and a drag holding a file getting Copy (`checkDragged` driven directly) |
| `TabDotTest` | Appending to a background tab's file: the `NEW_LINES` dot and tooltip line, a theme change recoloring it and disposing the old image, showing the tab clearing both; the rendered dot opaque at its center and transparent at the corners; a stop-follow trigger on a following background tab giving the `FOLLOW_STOPPED` dot and tooltip line |
| `TabColorsGuiTest` | Tab strips' screen pixels in light, dark, light and dark, light, dark order, with focus outside and inside the folder: contrast of at least 4.5 and a strip of the palette's lightness. Mixed combinations: Nightwater, Paper, Graphite, Dark, then Follow theme in light chrome, and Sandstone, Midnight, Legal Pad, Light, Legal Pad in dark chrome then a switch to light chrome, each checking both strips and the main and filtered views' background pixel against the scheme. Its theme service reports every change as shown, so win32 opens no modal restart notice. About opened after a switch each way at least its computed size. Skips on GTK 4, where `GC.copyArea` from the Display reads white. A probe child of a known color checks that a screen read shows the main window, because the `windows-11-arm` runner's desktop lies under a full-screen first-run page; when it does not, win32 reads through `Control.print`, which equals the screen there, and other platforms fail |
| `TabStripButtonsTest` | 30 tabs in a 700 px window, GTK 3 and GTK 4: Open, list and Close showing at the strip's end after the last shown tab, SWT's chevron at zero width, the tool tips; the hidden count following a resize; the list menu holding every tab in strip order with its text and, except on GTK 4, its dot, the selected one checked and a separator around the shown run, a choice selecting the tab through `Selection`, showing it and its document, and clearing its dot; SWT's chevron request opening the same menu; the wheel over the strip and over the list button shifting the shown run without changing the selection, and stopping at the selected tab; a new tab shown at every width from 600 to 720 px; Close closing the current tab; one tab showing the strip and its Close leaving an empty window; every tool item in the window wider and taller than its icon (GTK 4 lays an image-only item out at 12 px without `Gtk4ToolItemSizing`); the icon ink matching the strip under Follow Theme, Light, Dark and every bundled scheme in light and dark chrome, with a printed contrast of at least 3:1 where `Control.print` reads pixels; the About logo's variant matching its label's background in dark and light chrome |
| `TabMoveGuiTest` | Two windows over one `WindowSet`: Window > Move to New Window and the tab menu item moving the shown tab to a new window, the emptied window staying open without views and its menu item disabled; a release inside the window's own bounds doing nothing and one outside opening a new window with the tab; a drag released over another window off its tab strip appending the tab there and showing it; `dropTab` over that strip inserting at the tab under the point; a drag inside the strip reordering without looking for a window. The strip drop calls `dropTab` directly because GTK 4 opens every window at 0,0 under Xvfb, where the release point would also hit the source strip |
| `TimeSyncGuiTest` | Joining through the tab cascade and the log-view cascade; check marks, the group label, Leave's state and the tooltip line; a `&` in a file name and a tab name on the tab, tooltip and cascade; a caret move and Scroll All reaching hidden tabs |
| `TimestampsGuiTest` | The time field showing the caret time and the format in its tooltip; the position field showing a selection's difference, and the filtered view's while it has focus; an offset changing both; the size's tooltip following the file's modification time; a click on the time field opening Go to Time; Go to Time accepting a date and time or a time on the caret line's date and refusing anything else; the Time Offset fields rewriting each other and refusing text that does not parse; the format editor previewing the caret line and refusing a pattern that does not compile |
| `TimeSpreadGuiTest` | The bar's pixels: a level's blended color in both modes, the disorder color, a match line over the shading, the dark palette, Off clearing the layer; the Time Spread cascade choosing a mode as a radio group |
| `HelpItemsTest` | Debug Log File checked only while the log file is attached; a WARN line showing the status-bar marker, Show Log clearing it and listing the lines, a later ERROR showing it again, a second Show Log refreshing the same window; Help > Documentation loading the copy in the guide window or closing and taking the fallback (without a browser widget or under GTK 4, the notice naming it); the locations `GuideWindow` allows; without the application menu, the menu bar keeping About and Options. Without WebView2: a factory throwing Edge's timeout `SWTException` leaving no window, and the load bound expiring once unless met. It reads the location from the `changed` event, never `Browser.getUrl`, which blocks on a WebView2 still starting |
| `OptionsAndMenusTest` | The Advanced tab's Keep file closed box; a bullet-column click marking and unmarking; `Ctrl`+wheel zoom over the focused filtered view applying in place (same views, menu bar and focus, fewer rows, the height restored by Reset Zoom); the Encoding, Favorites and Open Recent menus; the shortcut editor refusing `Ctrl+O` for Reload, adding `Ctrl+Alt+R`, removing `F5` and the File menu showing `Ctrl+Alt+R` after Apply; a new key for Match Case reaching the search row's tooltip; the Folding tab refusing an unclosed class and applying an entry start pattern; the View tab listing Follow theme, Light, Dark and the schemes in order and applying Dark and a scheme live and back; Predefined Filters opening with its table focused |
| `NavigationGuiTest` | Go > Go Back's accelerator for the platform and Go Back and Go Forward through the menu items; Go Back disabled before a jump, enabled right after it and disabled again after Go Back, with Go Forward enabled, all with no menu shown; File > Reopen Closed Tab's `Ctrl+Shift+T` accelerator, its enablement and the reopen; File > Recently Closed listing a closed file before Open All and Clear, Open All reopening it and the empty list's placeholder and disabled Clear; the command palette filtering to Previous Tab with its menu and keys and Enter in the field closing it and running the command |
| `TableFitTest` | Keyboard Shortcuts and the command palette opened for a 1100x700 screen: within it, with no cell narrower than its text. Keyboard Shortcuts narrowed to 420 px, and the palette narrowed until its Keys column starts in view and is cut (win32's font fits the palette at 640 px, and win32 finds no row past the client area): the longest Keys cell cut off, a pointer move over it setting the table's tool tip to its full keys, and leaving the table clearing it |
| `MainMenuBarTest` | Showing one top menu leaving every other menu's items and sets unchanged; the Encoding menu's charset items being the same objects after each show while the check follows Auto, a forced charset and a choice from Other, one charset checked at a time; with the list held back through `MainWindow.setEncodingSource`, Other empty and disabled while a forced charset is checked, filling in once when the list arrives |
| `CommandStatesTest` | Every check item's checked and enabled state, and the file commands' enabled state, after `syncMenu` with no document, with a document and with its filtered view focused |
| `AlwaysOnTopWindowTest` | View > Always on Top in a real window: a check item without a key, enabled where `WindowStacking.PLATFORM` is supported, its tool tip where the window manager's menu offers the setting, and a toggle each way. On GTK 3 under X11, the item set before the window maps: `_NET_WM_STATE_ABOVE` on the window, a modal `Dialogs.shell` dialog `WM_TRANSIENT_FOR` the window, and the search history list an override-redirect popup (`xprop` and `xwininfo` through `XWindows`; skipped when absent) |
| `WindowStackingTest` | Each platform class resolving its reflected SWT members, on its own platform, so an SWT upgrade that removes one fails there; GTK 3 on X11 choosing `GtkX11Stacking` and setting and clearing `_NET_WM_STATE_ABOVE` as the window maps; GTK 4 and Wayland choosing the unsupported `WINDOW_MANAGER`. win32: `WS_EX_TOPMOST` each way. cocoa: the window's level each way, and a dialog and a nested dialog opened while on top floating and returning to normal with it. The win32 and cocoa cases run in CI |
| `ThemeAndHighlightTest` | On GTK, the dark setting darkening a shell's native background, which fails under a theme without a dark variant; switching the palette and reading painted pixels of both canvases; clicking the overview bar; toggling a set from the Highlighters menu and reading the span's painted color; `HighlightersDialog` through add, an invalid pattern, move and remove; a rule's trigger actions set, an invalid template regex reported and the actions read back after OK |
| `FolderSearchGuiTest` | Search in Folder from its fields: the summary, the file rows' counts and statuses (a NUL byte listed as not text), the selected file's lines, and a chosen line opening in a tab at its line; closing the window keeping the sets. The exclude field, two kept sets in the set list, choosing and pinning one, `F4` through the window's key filter selecting the line row and opening the file at the second line, Clear and Clear All |
| `PlatformBehaviorTest` | Each view-layer platform branch on the running platform, the expected value worked out from `SWT.getPlatform()` and `SWT_GTK4`: monospace font, Mac key names, the application menu, `launchesByExtension`, the toolkit description, `SwtThemeService.open` and `showsNow`, the dialog colors, tool item sizing and stacking classes. Each CI platform pins its own row |
| `PlatformTraitsTest` | No display: every `PlatformTraits` answer for win32, cocoa, GTK 3 and GTK 4, so a Linux run checks every class; the class chosen on each OS (win32 and cocoa in CI) |
| `DialogColorsTest` | `Win32DialogColors` on any platform: surfaces, labels, links, check boxes, fields and table headers take the dark dialog colors; push buttons, read-only combos and a color the dialog set keep theirs; a field added after the dialog opens is colored at its `SWT.Skin` event; an unprepared shell and `NativeDialogColors` change nothing; a `Dialogs.shell` dialog without a dark win32 start stays uncolored |
| `Win32DarkDialogsTest` | win32 only: every dialog and tool window, each Options tab and the error dialog, under a dark and a light start, and a system start when Windows is dark (the diagnostic runs set `AppsUseLightTheme` to 0). Screen pixels: the body reads `#202020` with light label text of contrast 4.5 or more under dark, and the light window color with dark text under light. Where a screen read does not show the dialog (the `windows-11-arm` runner) it checks the first label's text against its background through `Control.print`, because a printed `Shell` places its children wrong there. Saves each dialog to `target/diagnostics/win32-dark-dialogs/` |
| `ViewFeaturesGuiTest` | `Alt` and `Shift` with the wheel; an `Alt`-drag block selection; a drag held still below the view scrolling until release, focus loss or tab close; a click on the second wrapped row of a long line and the reported fit; a double click past a wrapped row's end selecting the word there; whitespace and fold painting; scratchpad transforms inline and on a worker (the tab read-only until the result) with the epoch value and `Ctrl+N`; the search across two files with its row selecting a tab; the selectors editor row |
| `MarkCommentsGuiTest` | Painted bullets (a commented mark's corner pixel filled, a plain mark's not); the gutter tooltip; the Edit Mark Comment dialog; the marks list through go to, `F2` edit, `Delete` and a tab switch |
| `TextGridCasesTest` | `hit`, `wrappedRows`, `wrapFit` and `gutterWidth` in grid units on a real font: nearest column boundary, gutter and negative coordinates, clamping below the last line, a tab row, a scrolled view, line numbers on and off, wrapped segments at two widths with the click past a segment's end clamped to it |
| `TextGridTest` | The same cases against `TextGrid` with no display, at a fractional and an integer advance, plus `wrappedWordHit` |
| `RowPixelsTest` | Painted extents against glyph ink: a span and the selection on the second row of a wrapped ASCII line and of a wrapped CJK line; a span over CJK glyphs at the start of an unwrapped row and after leading spaces; ASCII glyphs at the same height on a laid-out row and an ASCII row. A wrapped case waits for the wrapped read, because the block read before it holds one screen width of each line. Extents are read on a row's bottom pixel line, which glyph ink reaches when a laid-out row sits below the grid baseline |
| `ResourceLeakTest` | Starts its Display with `DeviceData.tracking`, runs a warm-up round of tab open and close, light and dark theme, zoom in and out and a menu sync, then four more rounds, and requires each tracked type's count (`Font`, `Image`, `Cursor`, and `TextLayout` on GTK 3) to return to the warm-up's; a failure lists each new object's allocation stack. SWT 3.135.0 does not track `Color` |
| `SwtUiDispatcherTest` | A canceled timer not running; cancel after dispose doing nothing |
| `HangWatchdogTest` | The dump naming the test and showing the calling thread's stack |
| `GuideScreenshotsTest` | Writes the user guide's images; runs only with `-Dqlogg.guide.shots=<dir>` |
