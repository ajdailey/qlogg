# Settings

The settings directory, `qlogg.conf` and the position and session stores in
`app.config` of [qlogg-app](../README.md). The directory modes are in the root
`README.md`; the settings saves are in [Windows and
tabs](windows-and-tabs.md#settings-saves).

## Settings directory

`SettingsLocation.resolve` picks the settings directory:

| Mode | When |
|---|---|
| `EXPLICIT` | `--settings` is given |
| `PORTABLE` | `<qlogg.home>/qlogg.conf` exists |
| `PER_USER` | Otherwise |

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
| A value that does not parse (`ui.theme=purple`) | That setting's default, one WARN; the file is not malformed |
| Directory read-only at load, or a save denied | Saving disabled, one WARN |
| `PER_USER` directory missing at save | Created owner-only through `SafePaths.settingsDirectory` |

Saves write sorted keys with no date comment through `AtomicFiles`. Logs name
the file and key, never a value, because the settings hold search history.

Lists use indexed keys read from 0 until the first gap. A history entry over
4,096 chars, or a filter without a name or pattern, is dropped. A value out of
range falls back to its default.

### Preferences parts

| Part | Keys | Read and written by |
|---|---|---|
| `Preferences` fields | `ui.theme` (`ThemeMode`), search history and size, `search.maxResults`, `search.runOnAdd`, quick find options, `view.splitPercent`, `view.overview`, predefined filters | `ConfigStore` |
| `HighlightSettings` | `highlight.*`, `label.*`, `matchHighlight.*` | `HighlightFile` |
| `ViewOptions` | Wrap on open, whitespace markers, hidden ANSI colors, fold rules, highlighter selectors, `view.timeSpread` | `ViewFile` |
| `TimeSettings` | `time.zone`, `time.displayZone`, at most 32 `time.format.N.name`, `.locate` and `.pattern`. An unknown zone reads as the system zone; a format that does not compile, or repeats a name, is dropped | `TimeFile` |
| `Options` | Font, `recent.count`, `monitor.*`, `encoding.default`, `session.loadLast`, `shortcut.*`, `favorite.N`, `log.file` | `OptionsFile` |
| `UiState` | `window.*`, `searchOption.*`, `view.lineNumbers`, `view.filteredLineNumbers`, `open.directory`, `recent.N`, `dialog.*` | `OptionsFile` |

`FoldRulesText.parse` checks the Options Folding tab's text. Blank lines are
dropped and other lines kept as typed. Every pattern must compile; at most 64
continuation patterns and at least one pattern in all are allowed.

### Keys

| Key | Values | Default | Set from |
|---|---|---|---|
| `ui.theme` | `light`, `dark`, `system` | `light` | View > Light, Dark or System Theme |
| `search.historySize` | 1 to 1000 | 50 | Edit > Search > Edit Search History; Tools > Options |
| `search.history.N` | Search text, most recent first | none | Each search; Edit > Search > Clear Search History |
| `search.maxResults` | 0 (no limit) or the match count that stops a search; read at startup | 0 | Tools > Options, Advanced |
| `search.runOnAdd` | `true`, `false` | `true` | Edit > Search > Search After Add, Exclude or Replace |
| `filter.N.name`, `filter.N.pattern`, `filter.N.regex` | A predefined filter | none | Tools > Predefined Filters, Save as Filter |
| `quickFind.incremental`, `quickFind.regex` | `true`, `false` | `true`, `false` | The quick find bar |
| `view.splitPercent` | 10 to 90: the main view's share of the split | 70 | View > Save Splitter Position |
| `view.overview` | `true`, `false` | `true` | View > Overview Bar |
| `view.timeSpread` | `off`, `activity`, `pauses` | `off` | View > Timestamps > Time Spread |
| `follow.onLoad` | `true`, `false`: every file opens following | `false` | View > Follow New Files |
| `follow.onOverscroll` | `true`, `false`: scrolling down past the last line engages follow | `true` | View > Follow on Overscroll |
| `follow.keepClosed` | `true`, `false`: files opened or reloaded afterwards stay open only for each read | `false` | Tools > Options, Advanced |
| `highlight.set.N.*`, `highlight.active.N` | Highlighter sets and the active set ids | none | Highlighters menu and Configure Highlighters |
| `label.N.name`, `.fore`, `.back`, `.cycle` | Color label N, 0 to 8 | 9 labels, all in the cycle | Configure Highlighters, Color Labels tab |
| `matchHighlight.enabled`, `.color`, `.variance` | Match highlight; an empty color is the theme's | `true`, theme, 0 | View > Highlight Search Matches; Configure Highlighters |
| `view.wrapOnOpen` | `true`, `false`: files open with text wrap on | `false` | View > Wrap New Files |
| `view.showWhitespace` | `true`, `false` | `false` | View > Show Whitespace |
| `view.hideAnsi` | `true`, `false`: ANSI color sequences are removed for display, copy and search | `false` | View > Hide ANSI Colors |
| `fold.entryStart`, `fold.continuation.N` | Regexes: an entry's first line, or continuation lines when `fold.entryStart` is empty | empty; `^\s`, `^at `, `^Caused by:`, `^\.\.\. \d+ more` | Tools > Options, Folding |
| `highlight.selector.N.glob`, `.firstLine`, `.set` | A rule picking a highlighter set for a file as it opens | none | Highlighters > Automatic Highlighters |
| `font.family`, `font.size` | The log views' font; empty and 0 are the platform's monospace font and size | empty, 0 | Tools > Options, View |
| `font.zoom` | 4 to 72: the size zooming chose; 0 is not zoomed | 0 | `Ctrl`+wheel, View > Zoom In, Zoom Out, Reset Zoom |
| `font.bold`, `font.antialias` | `true`, `false` | `false` | Tools > Options, View |
| `recent.count` | 1 to 50: files File > Open Recent keeps | 20 | Tools > Options, General |
| `recent.N` | Recent file paths, most recent first | none | Each opened file; File > Open Recent > Clean Up |
| `closed.N` | Closed file paths, most recent first, at most 20 | none | Each closed tab; File > Recently Closed > Clear |
| `favorite.N` | Favorite file paths, at most 100 | none | Favorites menu |
| `monitor.pollMillis`, `monitor.watchService` | 100 to 60000 ms between checks of a file; also check on directory change events | 1000, `true` | Tools > Options, File |
| `encoding.default` | The charset detection falls back to | `ISO-8859-1` | Tools > Options, File |
| `session.loadLast` | `true`, `false`: a start without files restores the session | `true` | Tools > Options, General |
| `shortcut.<COMMAND>.count`, `.N` | Key bindings that replace a command's defaults: `window` or `view`, then the keys | none | Tools > Options, Shortcuts |
| `window.x`, `.y`, `.width`, `.height`, `.maximized`, `.split` | The normal position and client size, maximized state and split of the window used last at the last exit; `--window-*` options win for that run | none | Exit |
| `log.file` | `true`, `false`: the log also goes to `logs/qlogg.log` (`logs/qlogg-<pid>.log` in a process that is not the running instance; the running instance deletes those older than 7 days when it starts with the log on) | `false` | Help > Debug Log File |
| `searchOption.*` | The search toggles, range end and context counts a new tab starts with | regex, match case | The search toggles |
| `view.lineNumbers`, `view.filteredLineNumbers` | Line numbers in a new tab's views | `true` | View > Line Numbers |
| `open.directory` | Where File > Open starts with no file shown | none | File > Open |
| `dialog.<name>.width`, `.height` | A dialog's last size | none | Closing the dialog |
| `time.zone` | Zone id of local times in files without a zone chosen | system zone | `qlogg.conf` |
| `time.displayZone` | Zone id times show in | system zone | `qlogg.conf` |
| `time.format.N.name`, `.locate`, `.pattern` | A user timestamp format: a name, a regex whose group `ts` holds the time, a `DateTimeFormatter` pattern; at most 32 | none | View > Timestamps > Edit Formats |

## Position and session stores

`ConfigStore` also holds two stores in the same directory. Both read and write
through `SettingsFile`, which applies the settings-file cases above to their
own file: `<name>.invalid`, atomic owner-only writes, read-only directory.

| Store | File | Contents | Limits |
|---|---|---|---|
| `PositionStore` | `positions.conf` | Per canonical path: top line, caret line and column, forced charset, the timestamp format and zone the user chose (`pos.N.*`) | 500 entries, least recently used dropped; 1 MiB |
| `SessionStore` | `qlogg_session.conf` | Per window W from 0: `window.W.active`; per tab `window.W.tab.N.path`, `.name`, `.follow`, `.marks` (`3-7,9`, 0-based), `.comment.K` (0-based line, a space, the text; K from 0 without gaps), `.timeOffset` (nanoseconds, when not 0), `.syncGroup` (from 1, when in a group); `droppedComments`, the comments the save left out at the cap, when not 0 | 32 windows, 256 tabs over all windows, 100,000 mark ranges and 1,000 comments over all tabs; 4 MiB |

`PositionStore.get`, `put` and the setters touch memory under a lock held for
no I/O, so the UI thread calls them; `load` and `save` block.

A session's marks go into `MutableLineSet`s charged to the `RESULTS` share. A
refusal keeps the earlier marks and shows a notice.
