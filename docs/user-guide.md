# QLogg User Guide

QLogg shows large and growing log files without loading them into memory. It
never connects to another computer; a second launch hands its files to the
running QLogg over a local-only connection. On macOS, `Ctrl` in this guide is
`Cmd`; Help > Keyboard Shortcuts lists every key for your platform and Tools >
Options, Shortcuts changes them.

- [Opening files](#opening-files)
- [The main window](#the-main-window)
- [Moving around](#moving-around)
- [Search and the filtered view](#search-and-the-filtered-view)
- [Marks and quick find](#marks-and-quick-find)
- [Highlighters and color labels](#highlighters-and-color-labels)
- [Following a growing file](#following-a-growing-file)
- [Folding multi-line entries](#folding-multi-line-entries)
- [Selecting, copying and saving](#selecting-copying-and-saving)
- [View options](#view-options)
- [Scratchpad](#scratchpad)
- [Sessions, recent files and favorites](#sessions-recent-files-and-favorites)
- [Windows and the running instance](#windows-and-the-running-instance)
- [Options](#options)
- [Help menu and logs](#help-menu-and-logs)
- [Command line](#command-line)

## Opening files

Open files with File > Open (`Ctrl+O`), by dropping them on the window, from
File > Open Recent or the Favorites menu, or on the command line. Each file
opens in its own tab. The first lines show at once while indexing continues in
the background; `Esc` or the Stop button stops it. File > Open from Clipboard
(`Ctrl+V` in a log view) opens the clipboard's text, up to 16 MiB, held in
memory only.

Reload (`F5`) reopens the shown file and keeps its marks and color labels.
QLogg detects the encoding from the byte order mark and the content; the
Encoding menu forces another one, and the choice is remembered for the file.

## The main window

![Main window with search results, marks and the overview bar](images/guide/main.png)

- The top row holds Open, Reload and Follow, the file's path (its menu copies
  the path or opens the containing folder), Stop, and the file's size,
  modification time, encoding and position.
- A tab row appears while two or more files are open. A tab's menu closes tabs,
  copies the path, opens the folder, renames the tab or moves it to a new
  window; a middle click closes it. View > Opened Files and Switch to File
  (`Ctrl+Shift+O`) list the tabs.
- The overview bar beside the main view marks matches and marks across the
  whole file; a click goes there.
- Below the splitter: the search row, the result tabs and the filtered view.
  `+` and `-` resize the main view; View > Save Splitter Position keeps the
  split.
- The status bar shows indexing, follow and notices. A warning marker appears
  when QLogg logs a warning; a click opens Help > Show Log.

## Moving around

| Keys | Action |
|---|---|
| Arrows, `j`, `k`, `h`, `l` | Move the current line, scroll sideways |
| `Page Up`, `Page Down` | Move one page |
| `Ctrl+Up`, `Ctrl+Down` | Scroll without moving the current line |
| `Ctrl+Home`, `Ctrl+End`, `G` | First line, last line |
| `Home`, `0`, `^`, `$`, `End` | Line start, line end, right edge of the text |
| `Ctrl+L` | Go to a line |
| `Ctrl+PageDown`, `Ctrl+PageUp` | Next and previous tab |
| `Space` | Switch between the main and the filtered view |
| `Ctrl`+wheel, `Ctrl+=`, `Ctrl+-`, `Ctrl+0` | Zoom in, out, back to the Options size |
| `Alt`+wheel, `Shift`+wheel | Scroll sideways, scroll by pages |

## Search and the filtered view

Type a pattern in the search row and press `Enter`. Matching lines appear in
the filtered view below the main view; a click there goes to the line in the
main view. `n` and `N` (`F3`, `Shift+F3`) go to the next and previous match.

The icon toggles beside the field, also on keys `4` to `9` in a log view:

| Toggle | Effect |
|---|---|
| Match case | Case-sensitive search |
| Regular expression | Java regular expression, else plain text |
| Whole word | The match must be a whole word |
| Inverse | Lines that do not match |
| Combine | Expressions such as `"error" and not "retry"` |
| In selection | Only the selected lines |
| Auto-refresh | Search lines appended to a followed file |
| Keep results | The next search adds a result tab instead of replacing the results |

Edit > Search > Set Search Start and Set Search End limit every search to the
lines between; Clear Search Limits removes the limit. The filtered view lists
matches, marks or both (`1`, `2`, `3`; `v` and `V`
cycle). The history drop-down recalls earlier patterns, and Predefined
filters stores named patterns (Tools > Predefined Filters, import and export).
`Shift+A`, `Shift+E` and `Shift+R` add the selected text to the search,
exclude it, or replace the search with it.

Edit > Search All Open Files (`Ctrl+Shift+S`) runs the search in every tab;
each tab's filtered view gets the results for its file.

## Marks and quick find

`m` marks or unmarks the selected lines; a click in the bullet column marks
one line. `]` and `[` go to the next and previous mark. Marks show in the
gutter, the overview bar and the filtered view.

Quick find (`Ctrl+F`, `'` forward, `"` backward) searches the shown view from
the current line without changing the filtered view. `*` and `/` find the
selected text forward and backward.

## Highlighters and color labels

![Highlighters dialog](images/guide/highlighters.png)

A highlighter set is a list of rules: a pattern, plain text or regular
expression, that colors the whole line or only the matched text, with
optional capture groups, opacity and color variance. Several sets can be
active at once from the Highlighters menu. Highlighters > Automatic
Highlighters picks a set when a file opens, by file name or first line. Sets
import and export from the Configure Highlighters dialog.

Color labels color every occurrence of the selected text: `Ctrl+D` takes the
next color, `Ctrl+Shift+1` to `Ctrl+Shift+9` a given one, `Ctrl+Shift+0`
clears them. View > Highlight Search Matches colors the last search's matches.

## Following a growing file

Follow (`f`, `F10`, the Follow button, or `-f` on the command line) keeps the
end of the file in view as lines arrive. Scrolling up stops following;
scrolling down past the last line starts it again (View > Follow on
Overscroll). View > Follow New Files follows every file as it opens.

A file that is truncated, replaced or rotated reopens by name with its results
cleared. A deleted file keeps its content on screen until a file with that name
appears again.

## Folding multi-line entries

![Collapsed stack traces](images/guide/folds.png)

A fold hides an entry's continuation lines, such as a stack trace, under its
first line. `Alt+Backspace` collapses or expands the entry at the current line;
`Ctrl+E Ctrl+C` collapses every entry as it scrolls into view and
`Ctrl+E Ctrl+X` expands them all. Going to a line, a match or a mark inside a
collapsed entry expands it. Copy, mark and save skip hidden lines.

By default a line continues the entry above it when it starts with white
space, `at `, `Caused by:` or `... N more`. Tools > Options, Folding changes
the rules for every file:

![Options, Folding tab](images/guide/options-folding.png)

- **Entry start pattern**: when set, every line it does not match continues
  the entry above. A pattern such as `^\d{4}-\d{2}-\d{2}` suits logs whose
  entries start with a date.
- **Continuation patterns**: one per line, used while the entry start pattern
  is empty; a line matching any of them continues the entry above.

Patterns are Java regular expressions found anywhere in the line, so anchor
them with `^`. A pattern that does not compile is refused with the reason.

## Selecting, copying and saving

Drag or use `Shift` with the arrows to select lines; `Ctrl+A` selects all.
`Alt`-drag, `Alt+Shift` with the arrows, or Edit > Block Selection selects a
rectangle of columns. Edit > Set Selection Start and End select the lines
between two chosen lines.

`Ctrl+C` copies the selection, or the current line without one. Edit > Copy
with Line Numbers puts each line's number in front. File > Save Selection and
Save Filtered Results write the lines to a file in UTF-8. `Esc` stops a copy or
save that is still running; a stopped save writes no file. One copy or save
runs at a time.

## View options

The View menu holds line numbers in either view, text wrap (`w`, and Wrap New
Files), markers for tabs, trailing spaces and line ends, hiding ANSI color
sequences (for display, copy and search), the overview bar, and the light,
dark and system themes. Light and dark also apply to menus, scroll bars and
other controls: at once on Linux, at the next start on Windows, which says so
when you change it, and where only the title bar, scroll bars, menu bar and
lists turn dark. On Linux the GTK theme must have a dark variant. On macOS
controls follow the system appearance and the theme recolors the log views.

## Scratchpad

Tools > Scratchpad holds text tabs. `Ctrl+Z` in a log view appends the
selection to it and `Ctrl+Shift+Z` replaces its text. It converts Base64, hex,
URL encoding, JSON and XML, and shows the selection's CRC32, number base and
epoch time.

## Sessions, recent files and favorites

QLogg restores the last session at start when no file is given (Tools >
Options, General; `-s` and `-n` decide for one run): the tabs of each window,
the shown tab, tab names, follow and marks. Each file's position and forced
encoding are remembered; `+line:N` wins over the position.

File > Open Recent lists recent files (Clean Up drops missing ones). The
Favorites menu adds, removes and opens favorite files.

## Windows and the running instance

File > New Window opens another main window. A tab moves between windows
with everything it shows: its position, selection, marks, search results,
filtered view, folds, follow state and name stay, and the file is not read
again.

- Drag a tab onto another QLogg window: over its tab row, the tab lands where
  it is dropped; anywhere else in that window, after the last tab.
- Drag a tab outside every QLogg window: a new window opens at the drop point,
  where the window system allows placing it.
- The tab menu and View > Move to New Window move a tab to a new window. A
  window left without tabs stays open.

A tab does not move into a window that already shows the same file, or while
its file is reloading.

A launch without `-m` hands its
files to the QLogg already running for the same settings directory and exits:
the files open in the window used last, which comes to the front. A file a
window shows already is shown in that tab instead of a second one, with
`+line:N` and `-f` applied. On macOS, files opened from the Finder or dropped
on the Dock icon arrive the same way.

## Options

Tools > Options (on macOS, QLogg > Preferences) has these tabs:

| Tab | Settings |
|---|---|
| General | Restore the last session, recent file count, search history size, search after add, quick find options, follow options |
| View | Font family and size, bold, anti-aliasing |
| File | Default encoding, change notifications and the check interval |
| Shortcuts | Rebind any command; keys another command holds are refused; restore defaults |
| Advanced | Stop a search after a number of matches |
| Folding | The fold rules ([Folding multi-line entries](#folding-multi-line-entries)) |

![Keyboard Shortcuts](images/guide/shortcuts.png)

## Help menu and logs

| Item | Action |
|---|---|
| Documentation | This guide, in its own window; when the window cannot show it within 15 seconds, in your system browser |
| Keyboard Shortcuts (`F1`) | Every binding, with a filter and Copy |
| Command-Line Options | The `--help` text |
| Show Log | QLogg's recent log lines |
| Debug Log File | Writes the log at DEBUG to `qlogg.log` in the settings directory's `logs` folder; a QLogg started with `-m`, or one that could not reach the running instance, writes `qlogg-<pid>.log` there, and the running instance deletes those older than 7 days when it starts with this on |
| Open Log Folder, Open Settings Folder | The folder in the file manager |
| Report an Issue | Copies the runtime details, then opens the project's issue page in the browser |
| About QLogg | Version, license and runtime details; on macOS in the QLogg menu |

## Command line

```text
qlogg [options] [[+line:N] file]...
```

| Option | Effect |
|---|---|
| `-h`, `--help` | Print usage and exit |
| `-v`, `--version` | Print the version and exit |
| `-d`, `--debug <level>` | Log level on stderr: `1` INFO, `2` DEBUG, `3` TRACE, or a level name |
| `-l`, `--log` | Also write the debug log file for this run |
| `-m`, `--multi` | Run as an independent instance: no handoff, and the saved session stays as it is |
| `-s`, `--load-session` | Restore the last session |
| `-n`, `--new-session` | Start without the last session |
| `-f`, `--follow` | Follow the files given |
| `--window-width <px>`, `--window-height <px>` | Window size; default 1024 x 768 |
| `--window-x <px>`, `--window-y <px>` | Window position |
| `--settings <dir>` | Settings directory |
| `+line:N` | Open the next file at line N |

QLogg keeps its settings in `qlogg.conf` in the settings directory: Linux
`~/.config/qlogg`, Windows `%APPDATA%\QLogg`, macOS
`~/Library/Application Support/QLogg`, or the installation directory when a
`qlogg.conf` exists there (portable mode).
