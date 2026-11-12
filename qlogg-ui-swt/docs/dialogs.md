# Dialogs and tool windows

The dialogs (`Dialogs`, `LineNumberDialog`, `ErrorDialog`, `AboutDialog`,
`TextWindow`, `ShortcutsWindow`, `HistoryDialog`, `FiltersDialog`,
`NameDialog`, `SwitchFileDialog`, `HighlightersDialog`, `OptionsDialog`,
`SelectorsDialog`, `CommandPaletteWindow`, the timestamp dialogs and
`GuideWindow`) and the tool windows (`ScratchpadWindow`, `MultiSearchWindow`,
`FolderSearchWindow`, `MarksWindow`). Module overview:
[../README.md](../README.md).

## Dialog model

Dialogs open without a nested event loop: `open` returns at once and buttons
act through listeners. The platform `FileDialog` and `ColorDialog` are the
exceptions. Modal dialogs use `APPLICATION_MODAL`; Enter presses the default
button and Escape closes. Dialogs that remember their size are listed in
[main-window.md](main-window.md#windows-and-placement).

Every dialog and tool window shell comes from `Dialogs.shell`, which gives it
the owning window's icons (`Dialogs.inheritIcons`) and the platform's dialog
colors, and opens through `Dialogs.open`, which colors its controls. A shell
built another way calls `inheritIcons`, or the window manager shows its generic
icon, and `DialogColors.prepare` and `apply`, or it stays light under a dark
Windows start ([theme.md](theme.md#dialog-colors));
`ArchitectureTest.ownedShellsTakeDialogColors` fails an owned `Shell` created
without `prepare`.

Keyboard Shortcuts, without a remembered size, and Command Palette open wide
enough for every packed column, within the monitor's client area
(`TableFit.dialogWidth`). A cell still cut off, on a small screen or in a
narrowed dialog, shows its full text as the table's tool tip while the pointer
is over it (`TableFit.cellTips`).

## Dialogs

| Window | Contents |
|---|---|
| Go to Line | 1-based line; OK is enabled only for a positive number |
| Edit Search History | One entry per line and the count kept; OK saves both, dropping blank lines |
| Predefined Filters | Add and Remove icons at the left, Import and Export centered, Move Up and Move Down icons at the right; a table of Name, Pattern and Regex, where a click on a selected row's name or pattern edits it in place and a click on Regex toggles it. Import appends the filters of a chosen file not in the list yet, Export writes the list, both on a worker with the result reported in the dialog. OK saves and closes, Apply saves. The table has focus at open, so no tool item starts highlighted |
| Save as Filter, Rename Tab, Edit Mark Comment | `NameDialog`: the filter's name, prefilled with the pattern cut at 64 chars; the tab's name, empty to reset it; or the comment |
| Switch to File | `SwitchFileDialog`: the open files by path and a filter field matching any part of it, ignoring case; Up and Down in the field move the selection, Enter or a double click shows the tab |
| Command Palette | `CommandPaletteWindow`, resizable and modal: a filter field over a table of command, menu path and keys from `CommandPalette.filter`, refilled on each change with the first row selected. Up, Down, Page Up and Page Down in the field move the selection; Enter or a double click closes the dialog and runs the command on the next UI turn. A disabled command's row is gray and does not run |
| Keyboard Shortcuts | Modeless table (group, command, keys) from `Keymap`, filter field, Copy as plain text |
| Command-Line Options, Licenses | Read-only monospace text with Copy; modeless, or modal and owned by the dialog when opened over a modal one (Licenses from About), because GTK gives input only to the newest modal shell |
| About QLogg | Logo at the top left (the icon variant for the window background's luminance, at 48 logical px), a bold name and version beside it, then tagline, project link, copyright, license and the runtime details in a smaller font; Licenses and Copy Details at the bottom left, OK at the bottom right. The link calls `Program.launch` with the fixed project URL |
| Automatic Highlighters | `SelectorsDialog`: a table of match kind, pattern and set over an editor row, with Add, Update, Remove, Move Up and Move Down; OK saves the ordered selectors |

## Configure Highlighters

`HighlightersDialog` holds the widgets; the list edits and the pattern check
run in `HighlightSetsEditor` (`qlogg-app`). OK saves. Import adds the sets of a
chosen file whose ids are new.

| Tab | Contents |
|---|---|
| Highlighter Sets | The sets (Add, Remove, Move Up, Move Down, Import, Export, name) and the selected set's highlighters (Add, Remove, Move Up, Move Down) with the editor: pattern, Regex, Ignore case, whole line, Enabled, text and background colors through `ColorField`, opacity, variance, and the trigger actions Stop follow, Set mark with a comment template field enabled only with it, and Do not light the tab dot. A pattern or template regex that does not compile is reported below |
| Color Labels | Name, colors and cycle membership per label |
| Match Highlight | On or off, theme color or a chosen one, variance |

## Options

`OptionsDialog` (Tools > Options) is modeless with General, View, File,
Shortcuts, Advanced and Folding tabs. OK and Apply call `setOptions`,
`setSearchHistory`, `setQuickFindOptions`, `setFollowSettings` and
`setPreferences`.

| Tab | Behavior |
|---|---|
| View | The family list is `Display.getFontList`. Changing the size or family clears the zoom |
| Shortcuts | Every command with its keys, an override marked `*`. The key field records one or two strokes; Add refuses keys `Keymap.conflicts` reports, naming the command that holds them |
| Folding | The entry start pattern and the continuation patterns, one per line, with Restore Defaults. OK and Apply check them first (`FoldRulesText`); a refused pattern applies nothing, selects the tab and shows the reason under the patterns |

## Timestamp dialogs

Each is modal with OK disabled until its input parses.

| Dialog | Input |
|---|---|
| `GoToTimeDialog` | A target (`TimeText.parseTarget`) |
| `TimeOffsetDialog` | An offset field and a caret-time field; editing one rewrites the other. An offset that moves the caret line's time out of the `long` range is refused |
| `TimeZoneDialog` | An editable list of Default and the runtime's zone ids |
| `TimestampFormatsDialog` | The user formats as a list with name, locating regex and pattern; Add, Remove. OK refuses a format that does not compile or a repeated name. The preview parses the caret line with the selected format on the UI thread, bounded as every user format is |

## User guide window

`GuideWindow` is an SWT `Browser` with JavaScript off on the guide's copy, and
Close. A `LocationListener` refuses every location but that file (with or
without an anchor) and `about:blank`.

The window closes and calls `ApplicationController.showGuideElsewhere` (the OS
browser, else a notice naming the copy) when:

- a browser call throws `SWTException` or `SWTError`;
- the page has not loaded 15 s after the load request. The bound is a
  `UiDispatcher.timerExec`, so the UI thread does not wait.

A failure during `create` returns null instead, and the controller takes the
same fallback. A bound that expires during one of `GuideWindow`'s own browser
or shell calls closes the window after that call returns: on Cocoa an SWT
timer runs inside any nested run loop a native call spins. A second
Documentation reloads the page, restarts the bound and activates the window.

Under GTK 4 no `Browser` is created; see
[../README.md](../README.md#gtk-4).

## Tool windows

All four are modeless.

### Scratchpad

`ScratchpadWindow` (Tools > Scratchpad) holds editable text tabs; New Tab or
`Ctrl+N` adds one. The Transform menu applies a `Transforms` kind to the
selection, or to the whole tab without one. The line under the tabs shows a
failure's reason, otherwise the selection's derived values. Text sent from a
log view appends to or replaces the shown tab.

Transforms, values and sent text take at most 1 Mi chars.
`ApplicationController.transform` runs an input over 64 Ki chars on a CPU
worker. Until the result arrives the tab is read-only and the line shows
Transforming; text sent meanwhile goes to a new tab, and a result for a closed
tab is dropped.

### Search All Open Files

`MultiSearchWindow` (Search > Search All Open Files) lists one row per file group
with its match count and state under a summary line. Choosing a row shows that
tab with its search's results. Stop cancels every search of the group.

### Search in Folder

`FolderSearchWindow` (Search > Search in Folder) holds the folder (Browse opens
a `DirectoryDialog`), the file name globs, Include subfolders, the search text
and the toggles, then a summary, a progress bar and two virtual tables: the
listed files with their match count and status, and the selected file's
matching lines. Enter or a double click on a line opens it in a tab. Stop
cancels the run. `FolderSearchController` (`qlogg-app`) runs the search on
workers and calls the window's listener on the UI thread. Closing the window
calls `FolderSearchController.clear`, which cancels the run and closes its
results, returning their `RESULTS` reservation.

### Marks List

`MarksWindow` (Go > Marks List) shows a `MarksListController`'s rows in a
virtual table of line, comment and text under a summary line, following the
shown tab.

| Key | Action |
|---|---|
| `Enter`, double click | Go to the row's line |
| `Delete` | Remove the selected marks |
| `F2` | A field over the row's comment (`TableEditor`); `Enter` or leaving the field saves it, `Esc` drops the edit |
