# Settings

The settings directory, `qlogg.conf`, the color scheme files and the position
and session stores in `app.config` of [qlogg-app](../README.md). The directory
modes are in the root `README.md`; the settings saves are in [Windows and
tabs](windows-and-tabs.md#settings-saves).

## Settings directory

`SettingsLocation.resolve` picks the settings directory:

| Mode | When |
|---|---|
| `EXPLICIT` | `--settings` is given |
| `PORTABLE` | `<qlogg.home>/qlogg.conf` exists; else, on Linux, `<qlogg.home>/bin/qlogg.conf` (beside the `bin/qlogg` script), and the directory is `<qlogg.home>/bin` (`OsTraits.launcherDirectory`) |
| `PER_USER` | Otherwise; the directory is `OsTraits.userSettings` |

An environment value that is not an absolute path is ignored.

## Settings file

`ConfigStore` loads and saves `Preferences`, an immutable record, in
`qlogg.conf`. Both operations block and take a lock, so callers run them off
the UI thread; the launcher loads before the Display exists.

| Case | Result |
|---|---|
| File missing, or empty | Defaults, no warning |
| No `version` key | Read as version 1 |
| Unreadable, over 1 MiB, unparsable, or another `version` | Malformed: defaults, one WARN; the first save copies the file to `qlogg.conf.invalid`, then replaces it |
| A leading UTF-8 BOM, UTF-8 or ISO-8859-1 text | Read through `PropertiesFiles`: the BOM dropped, UTF-8 unless a sequence is malformed, then ISO-8859-1 |
| A value that does not parse (`ui.theme=purple`) | That setting's default, one WARN; the file is not malformed |
| Directory read-only at load or at a failed save | Saving disabled, one WARN |
| A save fails in a writable directory (Windows refuses a move over a file open elsewhere) | That save returns false with one WARN; the settings stay marked changed, so the next save writes them |
| `qlogg.conf` is a symbolic link | The file it names is replaced; the link stays |
| `PER_USER` directory missing at save | Created owner-only through `SafePaths.settingsDirectory` |

Saves write sorted keys with no date comment through `AtomicFiles`. Logs name
the file and key, never a value, because the settings hold search history.

Lists use indexed keys read from 0 until the first gap. A history entry over
4,096 chars, or a filter without a name or pattern, is dropped. A value out of
range falls back to its default.

### Log text in settings files

Two settings can hold text taken from a log:

| Key | File | Source | Removed by |
|---|---|---|---|
| `search.history.N` | `qlogg.conf` | Every search that runs records its text; after Search > Add Selection to Search, Exclude Selection from Search or Replace Search with Selection, that text holds the selected log text | Search > Clear Search History, Search > Edit Search History |
| `window.W.tab.N.comment.K` | `qlogg_session.conf` | A keyword trigger's comment template with `{regex}{replacement}` expands the appended line's text (`CommentTemplate`, up to 1,024 chars); `MarkComments` keeps the first 200 | Unmarking the line or closing its tab |

### Preferences parts

| Part | Keys | Read and written by |
|---|---|---|
| `Preferences` fields | `ui.theme` (`ThemeMode`), search history and size, `search.maxResults`, `search.runOnAdd`, quick find options, `view.splitPercent`, `view.overview`, `follow.*`, predefined filters | `ConfigStore` |
| `HighlightSettings` | `highlight.*`, `label.*`, `matchHighlight.*` | `HighlightFile` |
| `ViewOptions` | Wrap on open, whitespace markers, hidden ANSI colors, fold rules, highlighter selectors, `view.timeSpread`, `view.colors` | `ViewFile` |
| `TimeSettings` | `time.zone`, `time.displayZone`, at most 32 `time.format.N.name`, `.locate` and `.pattern`. An unknown zone reads as the system zone; a format that does not compile, or repeats a name, is dropped | `TimeFile` |
| `Options` | Font, `recent.count`, `monitor.*`, `encoding.default`, `session.loadLast`, `shortcut.*`, `favorite.N`, `log.file`, `tabs.closeWindowWithLastTab`, `tabs.closeShowsLastUsed` | `OptionsFile` |
| `UiState` | `window.*`, `searchOption.*`, `view.lineNumbers`, `view.filteredLineNumbers`, `open.directory`, `recent.N`, `closed.N`, `dialog.*`, `folderSearch.*` | `OptionsFile` |

`FoldRulesText.parse` checks the Preferences Folding tab's text. Blank lines are
dropped and other lines kept as typed. Every pattern must compile; at most 64
continuation patterns and at least one pattern in all are allowed.

### Keys

| Key | Values | Default | Set from |
|---|---|---|---|
| `ui.theme` | `light`, `dark`, `system` | `light` | View > Theme |
| `search.historySize` | 1 to 1000 | 50 | Search > Edit Search History; Tools > Preferences |
| `search.history.N` | Search text, most recent first | none | Each search; Search > Clear Search History |
| `search.maxResults` | 0 (no limit) or the match count that stops a search; read at startup | 0 | Tools > Preferences, Advanced |
| `search.runOnAdd` | `true`, `false` | `true` | Search > Search Options > Search After Add, Exclude or Replace; Tools > Preferences, General |
| `filter.N.name`, `filter.N.pattern`, `filter.N.regex` | A predefined filter | none | Tools > Predefined Filters, Save as Filter |
| `quickFind.incremental`, `quickFind.regex` | `true`, `false` | `true`, `false` | The quick find bar; Tools > Preferences, General |
| `view.splitPercent` | 10 to 90: the main view's share of the split | 70 | View > Filtered View > Save Splitter Position |
| `view.overview` | `true`, `false` | `true` | View > Overview Bar |
| `view.timeSpread` | `off`, `activity`, `pauses` | `off` | View > Timestamps > Time Spread |
| `view.colors` | `follow`, `light`, `dark`, a bundled scheme id (`sandstone`, `nightwater`, `garden-light`, `garden-dark`, `legal-pad`, `paper`, `midnight`, `phosphor`, `graphite`), or a user scheme id: the colors of the log views, gutter, overview bar and tab strips. `light` and `dark` pin `Palette.LIGHT` and `Palette.DARK`. An id that names no scheme follows the theme and stays saved | `follow` | Tools > Preferences, View |
| `follow.onLoad` | `true`, `false`: every file opens following | `false` | View > Follow New Files; Tools > Preferences, General |
| `follow.onOverscroll` | `true`, `false`: scrolling down past the last line engages follow | `true` | View > Follow on Overscroll; Tools > Preferences, General |
| `follow.keepClosed` | `true`, `false`: files opened or reloaded afterwards stay open only for each read | `false` | Tools > Preferences, Advanced |
| `highlight.set.N.*`, `highlight.active.N` | Highlighter sets and the active set ids | none | Highlighters menu and Configure Highlighters |
| `label.N.name`, `.fore`, `.back`, `.cycle` | Color label N, 0 to 8 | 9 labels, all in the cycle | Configure Highlighters, Color Labels tab |
| `matchHighlight.enabled`, `.color`, `.variance` | Match highlight; an empty color is the theme's | `true`, theme, 0 | Highlighters > Highlight Search Matches; Configure Highlighters |
| `view.wrapOnOpen` | `true`, `false`: files open with text wrap on | `false` | View > Wrap New Files |
| `view.showWhitespace` | `true`, `false` | `false` | View > Show Whitespace |
| `view.hideAnsi` | `true`, `false`: ANSI color sequences are removed for display, copy and search | `false` | View > Hide ANSI Colors |
| `fold.entryStart`, `fold.continuation.N` | Regexes: an entry's first line, or continuation lines when `fold.entryStart` is empty | empty; `^\s`, `^at `, `^Caused by:`, `^\.\.\. \d+ more` | Tools > Preferences, Folding |
| `highlight.selector.N.glob`, `.firstLine`, `.set` | A rule picking a highlighter set for a file as it opens | none | Highlighters > Automatic Highlighters |
| `font.family`, `font.size` | The log views' font; empty and 0 are the platform's monospace font and size | empty, 0 | Tools > Preferences, View |
| `font.zoom` | 4 to 72: the size zooming chose; 0 is not zoomed | 0 | `Ctrl`+wheel, View > Zoom In, Zoom Out, Reset Zoom |
| `font.bold`, `font.antialias` | `true`, `false` | `false` | Tools > Preferences, View |
| `recent.count` | 1 to 50: files File > Recent Files keeps | 20 | Tools > Preferences, General |
| `recent.N` | Recent file paths, most recent first | none | Each opened file; File > Recent Files > Clean Up, Clear Recent Files |
| `closed.N` | Closed file paths, most recent first, at most 20 | none | Each closed tab; File > Recently Closed > Clear Recently Closed |
| `favorite.N` | Favorite file paths, at most 100 | none | File > Favorites |
| `monitor.pollMillis`, `monitor.watchService` | 100 to 60000 ms between checks of a file; also check on directory change events | 1000, `true` | Tools > Preferences, File |
| `encoding.default` | The charset detection falls back to; one that can encode | `ISO-8859-1` | Tools > Preferences, File |
| `session.loadLast` | `true`, `false`: a start without files restores the session | `true` | Tools > Preferences, General |
| `tabs.closeWindowWithLastTab` | `true`, `false`: closing the last tab, or Close All, closes the window; the last window quits | `false` | Tools > Preferences, General |
| `tabs.closeShowsLastUsed` | `true`, `false`: closing the shown tab shows the open tab shown most recently, not the one after it; with none shown, the one after it | `false` | Tools > Preferences, General |
| `shortcut.<COMMAND>.count`, `.N` | Key bindings that replace a command's defaults: `window` or `view`, then the keys | none | Tools > Preferences, Shortcuts |
| `window.x`, `.y`, `.width`, `.height`, `.maximized`, `.split` | The normal position and client size, maximized state and split of the window used last at the last exit; `--window-*` options win for that run | none | Exit |
| `log.file` | `true`, `false`: the log also goes to `logs/qlogg.log` (`logs/qlogg-<pid>.log` in a process that is not the running instance; the running instance deletes those older than 7 days when it starts with the log on) | `false` | Help > Debug Log File |
| `searchOption.*` | The search toggles, range end and context counts a new tab starts with | regex, match case | The search toggles |
| `view.lineNumbers`, `view.filteredLineNumbers` | Line numbers in a new tab's views | `true` | View > Line Numbers, Line Numbers in Filtered View |
| `open.directory` | Where File > Open starts with no file shown | none | File > Open |
| `dialog.<name>.width`, `.height` | A dialog's last size | none | Closing the dialog |
| `folderSearch.folder`, `.names`, `.recursive`, `.exclude` | Search in Folder's folder, file name globs, Include subfolders and exclude globs (text at most 4,096 chars each) | none, `*.log*`, `false`, none | Each folder search |
| `time.zone` | Zone id of local times in files without a zone chosen | system zone | `qlogg.conf` |
| `time.displayZone` | Zone id times show in | system zone | `qlogg.conf` |
| `time.format.N.name`, `.locate`, `.pattern` | A user timestamp format: a name, a regex whose group `ts` holds the time, a `DateTimeFormatter` pattern; at most 32 | none | View > Timestamps > Edit Formats |

## Color scheme files

`SchemeStore.load` reads `<id>.properties` files in the `colors` folder of the
settings directory; the launcher calls it before the Display exists. The
schemes follow Light, Dark and the bundled ones in `ColorSchemes.all()`, in
file name order.

| Key | Value |
|---|---|
| `dark` | `true` or `false`: the palette's side, which picks the default color label variants |
| `name` | The name in Tools > Preferences; blank, or holding a control character, shows the id; cut at 64 chars |
| Each `Palette.COLOR_NAMES` entry | `#rrggbb` |

Other keys are ignored. A file is skipped with one WARN naming it when its id is
not 1 to 64 letters, digits, `-` and `_`, or is `follow`, `light`, `dark` or a
bundled id; when it is not a regular file (links are not followed), is over 64
KiB or does not parse; or when `dark` or a color is missing or invalid. At most
64 files are read. Values are never logged.

## Position and session stores

`ConfigStore` also holds two stores in the same directory. Both read and write
through `SettingsFile`, which applies the settings-file cases above to their
own file: `<name>.invalid`, atomic owner-only writes, read-only directory. A
save whose content is over the file's limit is refused with one WARN and the
last saved file stays, so a load never finds a file over its cap.

| Store | File | Contents | Limits |
|---|---|---|---|
| `PositionStore` | `positions.conf` | Per canonical path: top line, caret line and column, forced charset, the timestamp format and zone the user chose (`pos.N.*`) | 500 entries, least recently used dropped; 1 MiB |
| `SessionStore` | `qlogg_session.conf` | Per window W from 0: `window.W.active`, `window.W.onTop` (View > Always on Top, when on); per tab `window.W.tab.N.path`, `.name`, `.follow`, `.marks` (`3-7,9`, 0-based), `.comment.K` (0-based line, a space, the text; K from 0 without gaps), `.timeOffset` (nanoseconds, when not 0), `.syncGroup` (from 1, when in a group); `droppedComments`, the comments the save left out at the cap, when not 0 | 32 windows, 256 tabs over all windows, 100,000 mark ranges and 1,000 comments over all tabs; 4 MiB |

`PositionStore.get`, `put` and the setters touch memory under a lock held for
no I/O, so the UI thread calls them; `load` and `save` block.

A session's marks go into `MutableLineSet`s charged to the `RESULTS` share. A
refusal keeps the earlier marks and shows a notice.
