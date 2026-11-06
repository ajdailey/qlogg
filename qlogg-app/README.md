# qlogg-app

The command line, the key bindings, the controllers and the view contracts.
It depends on
`qlogg-core` and slf4j-api, never on SWT, so every class here is tested
without a display. Tests log through `slf4j-jdk14` with logging off
(`quiet-logging.properties`); a test that checks a log statement attaches a JUL
`Handler` to that logger and sets its level.

## Command line (`app.cli`)

`CommandLineParser` is hand-written and accepts `--opt value`, `--opt=value`,
`-o value`, compacted flags (`-mf`, `-md2`) and `--` ending the options.
Options added beyond the base set are long-only, so no short flag changes
meaning. The option table is in the root `README.md`.

- `+line:N` (N >= 1) applies to the next positional file only. `+line:` with a
  bad number, with no file after it, or followed by another `+line:` is an
  error. After `--`, an argument starting `+line:` is a file name; any other
  argument starting `+` is a file name anywhere.
- Paths are resolved against the working directory without touching the file
  system. The caller validates them with `SafePaths`.
- A parse error is a `CommandLineException` with a one-line message.
- `-s` with `-n` is an error.

The result is a `CommandLine`: an `Action` (`RUN`, `HELP`, `VERSION`), a
`StartupRequest`, the `-d` level and the `-l` flag. In `StartupRequest`, a null
width, height, x or y means not given.

`Version.get()` reads `Implementation-Version` from this module's jar manifest,
which Maven writes from the project version; outside a jar it returns
`unknown`.

## Views and the UI thread (`app.view`)

- `UiDispatcher` is the only route onto the UI thread. An implementation drops
  a submission once the UI is disposed. There is no synchronous variant, so a
  worker never waits on the UI thread.
- `MainView` is the main window as controllers see it; every method is called
  on the UI thread and returns without waiting for the user, except
  `chooseFilesToOpen`, which runs the platform chooser. It extends
  `FileDialogService`, `ClipboardService`, `NotificationService` and
  `ActivityIndicator`. `NotificationService.showNotice` puts a one-line
  notice in the status bar ("Reached end of file, no occurrence found").
- `ActivityIndicator.setActive` and `setIdle` are callable from any thread.
  Every operation that calls `setActive` calls `setIdle` once when it ends,
  including on cancel and failure. `BusyCounter` implements them: it posts
  each call through `UiDispatcher` and counts on the UI thread, so the
  indicator turns on with the first active operation and off with the last; an
  idle call with nothing active is ignored.
- `DocumentViewListener` receives `stateChanged(DocumentViewState)` (name,
  path, encoding and whether it was forced, line count, index percent, sparse
  index, following, file size and modification time, missing file, stopped
  loading, error) and `contentChanged()` (repaint from the controller's
  snapshot). `hasFile()` is false for pasted text, which has no path.
  `StatusText` formats the top row's size and encoding, the size's tooltip
  (`Last modified` and the time) and the status bar's notes.
- `SearchViewListener` receives `stateChanged(SearchViewState)` (text,
  `SearchOptions`, phase `IDLE` `RUNNING` `DONE` `CANCELED` `FAILED`,
  percent, match count, limit, skipped lines, error; `statusText()` is the
  bar's line and names the lines too long to search),
  `resultsChanged()`, and `optionsChanged(text, options)` when a key or an
  edit with selection changes the toggles or the field (no-op by default).
  `SearchOptions` adds `autoRefresh` and `keepResults` to the matching
  toggles; `with...` methods copy it with one toggle changed.
- `QuickFindListener` receives `QuickFindState`: open, text, ignore case,
  regex, incremental, running, and the last notice.
- `PositionText.of(selection, lineCount)` is the top row's position field:
  `Ln 12/200  Col 5`, plus `Sel 6 chars` within one line or `Sel 4 lines`.
  The four-argument form takes the size from another selection and appends
  its time difference to `Sel N lines`.
- `TimeText` formats the caret time (`yyyy-MM-dd HH:mm:ss` with at least 3
  fraction digits, more when the line has them), a selection's difference
  (`+00:01:23.456`, `+2d 03:00:00.000`, negative when the last line is
  earlier, with the larger fraction-digit count of the two lines, at least
  3), and the offset (`[+-][d.]HH:mm:ss[.fff]`), and parses an offset and a
  Go to Time target (`yyyy-MM-dd HH:mm:ss[.f]`, or `HH:mm:ss[.f]` on a given
  date); a refused text parses as `INVALID`. `TimeStatus` is a view's caret,
  first and last line times as read, before the offset;
  `DocumentViewListener.timeChanged` reports a change of it, the offset or
  the format.
- `UiUpdateQueue` coalesces notices from worker threads into flushes on the UI
  thread: `post()` from any thread schedules at most one flush, at most one
  per interval (50 ms in both controllers), and the flush reads the latest
  published state. `close()` stops later flushes.
- `WindowGeometry.place` sizes and positions the window. Per dimension the
  command line wins over persisted geometry, which wins over 1024 x 768. The
  size is the client area plus the trim. The monitor is the one containing the
  requested top-left corner, else the primary. Size is clamped to the
  monitor's work area, then the location is clamped so the window lies inside
  it. Without a position, or where position is unsupported (Wayland), the
  window system places the window.

`ManualUiDispatcher` (test sources, published in the test jar) queues
submissions until a test runs them on its own thread. `runUntil` drives the
queue to a condition with a bound, so controller tests need no sleeps.
Draining from another thread throws. `confined(type, listener)` wraps a
listener so a call off the UI thread is recorded, and the next drain throws
it.

## Key bindings (`app.command`)

`Keymap` is the one table of bindings; the menu bar, the log view's key
handler and Help > Keyboard Shortcuts all read it. `CommandId` lists every
command with its menu (`CommandGroup`), label, section break and cascade
(`submenu()`, Edit > Search), in menu order. The menus are File, Edit, View,
Tools, Highlighters, Encoding, Favorites and Help. `MenuTable.commands(group,
appMenu)` lists a menu's commands; with the macOS application menu present it
leaves out About, Options and Exit, which that menu holds as About,
Preferences and Quit. In File, View and Tools, and in each of their cascades,
no two items share a mnemonic (`MenuTableTest`); View has more items than
letters, so Text Wrap, Wrap New Files, Toggle Fold and Save Splitter Position
have none. `KeyStroke` is toolkit-free: a printable key is the char it types (a
letter in lower case plus `SHIFT`; `$` and `^` without `SHIFT`), and other
keys are constants. `PRIMARY` is Ctrl, or Command on macOS.

| Scope | Applies | Dispatched by |
|---|---|---|
| `WINDOW` | Anywhere in the main window; single strokes only | The menu item's accelerator; a command's other window strokes (`Ctrl`+keypad `+`) through `windowCommand`, which the window runs from a key filter |
| `LOG_VIEW` | While the log view has focus, so a text field never sees `J` or `Ctrl+C` | `KeySequencer`, called by the view |

`KeySequencer` holds the pending prefix of a two-stroke sequence
(`Ctrl+E Ctrl+T`) on the UI thread; the next stroke completes it, and `Esc` or
any unbound stroke cancels it. Single keys (`M`, `1` to `9`, `V`, `'`, `*`,
`+`, `Space`) are `LOG_VIEW` bindings, so typing in a text field never runs
them. `Space` is the key constant `SPACE`, not a printable char. A
`CommandId` with `check()` shows as a check item in its menu. `KeymapTest` rejects a stroke bound twice, a
single stroke that is also a sequence prefix, an accelerator on a command with
no menu, and a log view command with no key.

`ShortcutOverrides` holds the bindings the user set in place of a command's
defaults (`shortcut.<COMMAND>.count`, `shortcut.<COMMAND>.<n>` =
`window` or `view`, then the keys as `Binding.text` writes them; count 0
unbinds). `Keymap.withOverrides` puts a command's new bindings where its
first default stood. `Keymap.conflicts` lists the bindings with the same keys
in any scope, since a window stroke also reaches the log view, and a single
stroke that starts a sequence; the editor refuses a binding with any.
`scopeFor` gives a sequence, a log view command or a plain key `LOG_VIEW`, a
stroke the command has by default that binding's scope, and other `Ctrl`,
`Alt` or function keys `WINDOW`. `KeyStroke.parse` reads `KeyStroke.text`
back.

## Controllers (`app.controller`)

Every controller is confined to the UI thread and never blocks it
(`ArchitectureTest` rejects `Future.get`, `Thread.join`, `Thread.sleep`,
`OperationHandle.await` and `CountDownLatch.await` here). Controllers read no
system property or environment variable; the platform comes from the view.
Public controller methods return `LineSet`, never `MutableLineSet`
(`marks()`, `KeptResults.lines()`).

A blocking call in `app.controller` (file I/O, `LogDocument` open and close,
settings load and save, line reads; the list is `BLOCKING` in
`ArchitectureTest`) is made only from a method annotated `@WorkerThread` or
from `run` of an anonymous `Runnable` that its creating method passes to
`Workers`, directly or through a helper of the same class that takes a
`Runnable`. A creating method that also creates another anonymous `Runnable`
or takes a method reference does not qualify, because the bytecode does not
show which task it submits; annotate that `run`. A `@WorkerThread` method is
called only from such code, so annotating a UI-thread method fails at its
caller. Method references to blocking methods are not checked.

`ApplicationController` is one main window: it runs window commands and owns
the tabs, one `DocumentController` per open file in tab order
(`documents()`), and the shown one (`activeDocument()`). `WindowSet` holds
the windows and what they share: the settings and their saves, the theme, the
key bindings, the file monitor, the document services, the recent log lines
and the debug log file. The six-argument constructors create a set with one
window; `new ApplicationController(set, view)` adds a window to a set.

Each window holds a `DocumentOpener` and a `HelpController`.
`DocumentOpener` runs the open, paste, reload and session restore workers and
delivers each opened document to the window on the UI thread. After the window
closes (`detachAll`), a document whose open ends later is closed by its worker
or by `closeUnclaimed`, never delivered. `HelpController` runs the Help
commands below; a result that arrives after the window began closing is
dropped.

- `open(files, follow)` runs `LogDocument.open` for each file on one
  `qlogg-index-` worker, in the order given; each opens a tab after the
  others and is shown, so the last one ends shown, at its `+line:N`. A file
  already open shows its tab. `LogDocument.path()`, the path as given with
  symbolic links kept, keys the tab title, the open-file check, the handoff,
  the recent list, favorites, the session and the `PositionStore`, so a
  retargeted link reopens under its own name. Indexing and the `FileMonitor`
  watch start with the tab. `follow` (`-f`) or `follow.onLoad` start it following; otherwise,
  without `+line:N`, the `PositionStore` entry restores the caret and the top
  line once indexing reaches them. The worker opens the file with the
  entry's forced charset, if any. Each file goes first in the recent list. A failure is
  logged at WARN and shown as a one-line error. The end of the first indexing
  pass and of every search is logged at INFO with its duration and counts.
  File > Open and `openDropped`
  (files dropped on the window) open each file the same way.
- `openClipboard` (File > Open from Clipboard, `Ctrl+V` in a log view) reads
  the clipboard's text on the UI thread, then `LogDocument.ofText` encodes it
  as UTF-8 on a `qlogg-index-` worker, charged to the `PASTED` share and capped
  at `MAX_PASTED_BYTES` (16 MiB); a refusal shows a notice. The tab is named
  `Clipboard N`. A pasted document has no path: it is never watched, followed
  or reloaded, and the path commands show a notice instead.
- `activate`, `moveDocument`, `closeDocument(document)` and `closeAll` change
  the tabs; listeners get `documentOpened`, `documentClosed`,
  `activeDocumentChanged`, `documentReplaced` and `tabRenamed`. Closing the
  shown tab shows the one after it, else the one before. `Ctrl+PageDown` and
  `Ctrl+PageUp` show the next and previous tab. The tab context menu calls
  `closeOthers`, `closeSide`, `copyPath`, `copyFileName`,
  `openContainingFolder`,
  `renameTab` and `resetTabName`; a tab name is kept per path for the
  session and saved with it. View > Switch to File (`Ctrl+Shift+O`) calls
  `MainView.showOpenedFiles`.
- `copyPath`, `copyFileName`, `openContainingFolder` and `openInEditor`
  (Edit menu and tab menu) act on the file's path. The folder and the file open through
  `MainView.launch`, which runs no shell. `openInEditor` first checks on a
  `qlogg-lifecycle-` worker whether the system would run the file instead
  of opening it (an executable file, or an extension in `WINDOWS_EXECUTABLE`
  where `MainView.launchesByExtension()`), and refuses it with a notice.
- `reload` (`F5`) and a `TRUNCATED`, `REPLACED` or `MODIFIED` file
  (`reopenChanged`) reopen the path on a `qlogg-index-` worker. The new
  controller takes the tab, the follow state and the search text, and results
  start empty. A change on disk also empties marks and color labels and shows
  a notice, and with `autoRefresh` on a search that was not canceled runs again
  with the text and toggles it ran with; the field and toggles stay as they
  were, and the notice says the search runs again instead of results cleared.
  `F5` does not rerun it. A reload keeps the caret's line, the color labels, the marks and
  their comments: the worker copies them into the new document, and its first
  completed indexing drops marks and comments past the last line.
- A reopen that fails keeps the tab and its content and calls
  `DocumentController.reopenFailed`, which resumes the document's checks. The
  next check that reports a change reopens again, so retries run at the check
  interval and following resumes when the file opens. While no file is at the
  path the check reports `MISSING` once and no reopen runs. A run of failures
  shows one notice and logs one WARN; each failed `F5` shows its own notice. The
  status note reads Waiting to reopen in place of Following until the run ends.
  `Esc` leaves the retries running, as it leaves a first reopen. Closing the
  tab ends them: the watch stops and `reopened` or `reopenFailed` drops the
  result of a worker still running. A change reported during a reload is
  dropped; if that reload fails, the next check reports the change again.
- `execute(command)` runs File, Help, Go to Line, the search field, quick find,
  the split and the history and filter dialogs itself. Search toggles,
  visibility modes, edits with selection and search limits go to the shown
  document's `SearchController` and `FilteredController`; every other command
  goes to the focused view. A canvas reports focus through `setFocusedView`
  on focus, click and key; without one, the main view is focused.
- `FIND_NEXT` and `FIND_PREVIOUS` (`F3`, `N`, `Ctrl+G` and the shifted keys)
  walk quick find while its bar is open, otherwise the search's matches.
- It implements `DocumentHost`: each search puts its text first in
  `search.history.N` (bounded by `search.historySize`), after a range
  search's end expression, and saves its toggles, less In selection, as
  `searchOption.*`: the range toggle, end expression and context counts
  included (a malformed value or an end over 4,096 chars loads the default). A new document in any window starts
  with the saved toggles. `followOnOverscroll` reads
  `follow.onOverscroll`.
- `importFilters` and `exportFilters` read or write a user-chosen file on a
  `qlogg-lifecycle-` worker (`FilterFile`) and report to a
  `SettingsFileListener` on the UI thread. Save as Filter asks for a name
  (`MainView.askFilterName`), prefilled with the pattern cut at 64 chars;
  `saveAsFilter` adds the filter once, named after the pattern when the name
  is blank.
- `Space` (`RELEASE_FOCUS`) moves focus from the main view to the filtered
  view and back (`MainView.focusView`).
- Highlight settings (`highlightSettings`, `setHighlightSettings`,
  `toggleHighlightSet`, None) save at once and recolor every tab.
  `importHighlights` and `exportHighlights` run `HighlightFile` on a
  `qlogg-lifecycle-` worker and report to a `SettingsFileListener`. Color
  label commands read the focused view's selected text on a worker, then
  change the document's labels.
- `theme()` is the `ThemeController`; the View menu's theme items call
  `setTheme`, which saves `ui.theme` and, when `ThemeController.setMode`
  reports that native controls show the theme only from the next start
  (Windows), shows `THEME_RESTART_MESSAGE` through `showInfo`. A palette change recolors every tab's
  match highlight, whose default color is the palette's, and the color
  labels: on a dark palette a label still at its default colors paints with
  the dark default (`ColorLabel.forPalette`). Colors the user set are never
  changed.
- About facts and the license notices are read on a `qlogg-lifecycle-` worker,
  then shown.
- `setPreferences` replaces the settings. Every change of the settings, the
  tabs, a tab's follow state or marks (`DocumentHost.documentStateChanged`)
  restarts a `SAVE_DELAY_MILLIS` (1 s) timer; when it fires, `flushSettings`
  records every window's tab positions in the `PositionStore` and captures the
  settings and each window's tab list on the UI thread, and a
  `qlogg-lifecycle-` worker writes `qlogg.conf` (when changed),
  `positions.conf` (when changed) and `qlogg_session.conf` (`window.N` per
  window), reading each tab's marks and mark comments inside
  `LogDocument.enter`. Each capture takes a sequence number, and a writer
  skips a part a later capture has written.
- `restoreSession` opens a `Session`'s tabs on one `qlogg-index-` worker with
  their names, follow state, marks and mark comments (added before indexing
  starts, as a reload adds them; a comment on an unmarked line is dropped),
  then shows its active tab, and makes this instance the
  session's owner. Only the owner writes the tab list; the launcher makes an
  instance the owner when it restored the session and has no `-m`.
- `setOptions` (Tools > Options) applies the key bindings (listeners get
  `keymapChanged`), the font (`fontChanged`), the monitor settings
  (`FileMonitor.setSettings`), the default encoding for files opened
  afterwards and the recent-files count (`optionsChanged` for any option but
  the key bindings and the font). `keymap()` is the defaults with the
  overrides. `zoomFont` and View > Zoom In, Zoom Out (`Ctrl`+wheel in a view)
  step the drawn size by one point within 4 to 72 and save it as
  `font.zoom`, then fire `fontChanged`; Reset Zoom clears it, back to the
  Options size.
- The Encoding menu calls `setEncoding(document, charset)`: the choice goes
  into the file's `PositionStore` entry (null for Auto) and the tab reopens
  as a reload does, so it is indexed again with the forced charset. An open
  whose remembered charset name this runtime does not support logs a WARN
  naming it and detects the encoding.
  Favorites add and remove the shown file's path; `openListed` opens a recent
  or favorite path; File > Open Recent > Clean Up checks the paths on a
  `qlogg-lifecycle-` worker and drops those that are not regular files.
- Search toggles, line number toggles, the Open dialog's directory, dialog
  sizes (`rememberDialogSize`) and, when a window closes and at quit for the
  window activated last, the normal bounds, maximized state and split
  (`rememberWindow`) go into `UiState`.
- Closing a tab detaches its controllers on the UI thread, which cancels the
  search, quick find, mark toggle, overview, copy and save, then `LogDocument.close` runs
  on a `qlogg-lifecycle-` worker: it waits for every operation holding a
  document entry, reads included, before it closes the file.
- New Window (`WindowSet.newWindow`) opens an empty window through the
  set's `Factory`. `windowActivated` records the window the user activated
  last; `WindowSet.openHandoff` opens handed files there and raises it
  (`MainView.toFront`). A handed file that a tab in any window shows already,
  compared by the opened document's path, is closed again and that tab is
  shown instead, at its `+line:N` and following with `-f`; its window is
  raised (`WindowSet.holderOf`). File > Open and the command line keep their
  own window's check only.
- `WindowSet.moveDocument` moves a tab to another window at a tab position,
  or to a new window through the `Factory` at a display point (View > Move to
  New Window, the tab menu, a tab drag). The `DocumentController` and its
  `LogDocument` move as they are; nothing is reopened. Each controller reports
  to its window through a `TabHost` (`DocumentHost`, clipboard, notices,
  activity) that the move points at the target. An activity reported before
  the move ends in the window it started in. The source detaches the tab
  without closing it (`ApplicationListener.documentMovedOut`), cancels its
  quick find and drops it from the search across files; the target inserts it
  (`documentMovedIn`) with its Rename Tab name and shows it. A window left
  with no tab stays open. The settings and session save once, without the
  debounce. Nothing moves while quitting, during the tab's reload or reopen, or into a window
  that shows the same file (a notice says so).
- `closeWindow()` (the window's close button) closes this window: it
  remembers its placement, detaches its tabs, hides it, saves the session
  without it, closes its documents on a `qlogg-lifecycle-` worker and posts
  its disposal. The last open window quits instead. An open, paste, reload or
  session restore still running for the window stops before its next file;
  the worker closes a document it opened after the close began.
- `shutdown()` (File > Exit) runs `WindowSet.quit`: it records the placement
  of the window activated last, captures a save, detaches every window's tabs
  and hides the windows, then on a `qlogg-lifecycle-` worker runs the quit
  listeners (the handoff listener closes), writes the save, closes every
  document at once (`LogDocument.closeAll`, one worker each, 5 s overall),
  then `Workers` (5 s, naming survivors in a WARN), then any document an open
  finished after quit began, runs the finish listeners (the shutdown hook
  waits on one) and posts the windows' disposal, which ends the event loop. A
  second call does nothing.
- Help: Show Log shows `RecentLog`'s lines through `MainView.showLog` and
  clears the warning marker in every window; a WARN or ERROR line after that
  shows it again (`MainView.showWarningMarker`). Debug Log File attaches or
  detaches `LogFiles` on a `qlogg-lifecycle-` worker and saves `log.file`;
  its check mark reads `debugLogFile()`, so it stays clear when the file did
  not open. Open Log Folder and Open Settings Folder create the folder
  owner-only on a worker, then launch it. Report an Issue collects the About
  details on a worker, puts them on the clipboard and opens
  `AboutInfo.ISSUES_URL` through `MainView.launchUrl`.

`LineViewController` is the abstract parent of the two views of a document.
It holds the `Viewport`, the `Selection` in rows and the visible `LineBlock`,
and runs the navigation, copy, mark and selection-range commands both views
share. Subclasses map rows to lines (`sourceLine`, `rowOf`, `rows()`).

- Visible rows (rows plus one, columns plus a 16-column margin) are mapped to
  lines on the UI thread and decoded on a `qlogg-read-` worker. At most one
  read is in flight; a viewport change during a read starts one more when it
  returns. The view paints whatever block is present.
- Every worker a controller starts on the document (reads, copy, selected
  text, search, quick find, mark toggle, filtered-view snapshot, overview)
  runs inside `LogDocument.enter` and `exit`; after close begins, `enter`
  fails and the worker posts its empty result.
- Copy, save and `selectedText` run `TextExporter` on a worker. It is
  stateless and builds the text from the rows' source lines, with the
  platform line separator between them (LF for a block). An empty selection,
  and Copy This Line, copy the caret's row. It reserves 4 bytes of the `COPY`
  share per selected file byte (builder and string, up to 2 bytes per char
  each), 7 for a save; the controller releases them on the UI thread once the
  text is on the clipboard, written, refused or canceled. A selection the
  share cannot hold is refused with a notice naming both sizes. Copy holds the
  `ActivityIndicator` active until it ends. The builder is sized once, because
  a doubling builder peaks above the charge. A file shorter than the index
  snapshot after the rows are read fails the copy or save: a read past the end
  returns less text, not an error.
- One copy or save runs per view; another shows a notice. `TextExporter`
  checks a `CancellationToken` before every row and before
  `AtomicFiles.write`. `Esc` (`DocumentController.stop`) cancels the running
  copies of both views, and `detachView` cancels them on tab close, so the
  worker leaves its document entry within one row and `LogDocument.close`
  releases the line index. A canceled save writes no file.
- `M` toggles the marks of the selected rows' lines as one through
  `DocumentController.toggleMarks`, for any number of rows.
- The read that decodes the rows also colors them with the view's
  `Highlighter` (`qlogg-core`), from each row's whole decoded line, so
  highlighting costs nothing on the UI thread and covers the visible rows
  only. The block's spans are charged to `HIGHLIGHT` while it is shown; a
  block whose spans the share refuses shows uncolored. `marked(line)` and
  `matched(line)` answer the gutter's bullets with one set lookup per row.
- Set Selection Start remembers the caret's row; Set Selection End selects
  whole rows from it to the caret's row.
- `selectedText` reads the selection of one row on a worker, cut at 4,096
  chars, for edits with selection and quick find; an empty or multi-row
  selection shows a notice instead.
- `End` scrolls so the longest line in the decoded block ends at the right
  edge; `$` does the same for the caret's row; `Home`, `^` and `0` scroll to
  the first column.

`DocumentController` is the main view, where row n is line n unless entries are
folded (see below), and owns the
document's `SearchController` and `FilteredController`.

- Index progress arrives on the indexing thread, which only posts a flush; a
  flush runs at most every 50 ms and reads the latest snapshot. Indexing holds
  the `ActivityIndicator` active until it ends, from the indexing thread, or
  from `detach` when the document closes before indexing ends.
- `goToLine(n)` waits until a flush shows line n, then centers it; a line
  past the end goes to the last line. `goToLine` and `restorePosition` decide
  on the line count of the last flush, not the live index: the caret clamps to
  the viewport, which lags the index until the next flush. `showLine` moves the caret to a line,
  centering it only when it is off screen.
- `]` and `[` go to the next and previous mark; with none, a notice shows.
- `toggleMarks` runs `MarkToggle` on a `qlogg-search-` worker with the
  `ActivityIndicator` active: one toggle at a time (a second `M` shows a
  notice), progress notices at most every 250 ms, `Esc` (`STOP`) cancels and
  keeps what changed. `STOP` also stops loading: indexing ends and growth
  stays unindexed until a reload. While a go to time runs, `STOP` leaves
  loading for the next press. Marking reserves from `RESULTS` and stops with a notice
  at the share's limit. A toggle over more than 4,096 rows reports the count
  when it ends. Unmarking removes the lines' comments.
- Edit Mark Comment (`Shift+M`, `EDIT_MARK_COMMENT`) asks for the focused
  view's caret line's comment (`MainView.askMarkComment`), then
  `setMarkComment` marks the line when it is unmarked and sets the comment;
  an empty comment keeps the mark without one. A mark or comment the
  `RESULTS` share refuses, or a comment past 1,000 per document, shows a
  notice. `removeMarks(lines)` removes marks and their comments.
- `MarksListController` backs Tools > Marks List: the shown tab's first
  10,000 marks as rows of line, comment and the line's first 160 chars,
  read on a `qlogg-read-` worker under `LogDocument.enter`, one read at a
  time. It follows `activeDocumentChanged`, `documentReplaced` and
  `documentClosed`, and reads again when a content event finds the marks' or
  comments' `version()` changed. Each row reserves 96 bytes plus 2 per char
  from `RESULTS` while listed; rows past a refusal are left out. Listing
  another document, or `close`, cancels a read before its next row. `close`
  releases the rows and removes its listeners.
- `refreshHighlights` builds one `Highlighter` for both views from the rules
  `HighlightComposer` returns. The color labels (`LabelState`) are charged to
  `HIGHLIGHT`; a state the share refuses is not applied. It runs when a search
  starts or clears, the settings change, the palette changes or a label
  changes.
- `overview()` is the `OverviewController`: bins of matches and marks per
  pixel row, computed on a `qlogg-search-` worker from `OverviewBins` while
  the bar shows, one at a time, the newest generation kept and charged to
  `HIGHLIGHT`. The bar spreads the main view's rows, so a collapsed entry
  takes one row's share and its hidden lines count in that row's pixel:
  `refresh` maps each pixel row through `sourceLine` on the UI thread, a
  binary search of the `FoldMap` that reads no file. `jumpTo(y)` shows the
  line of the row under the pointer. A fold change refreshes the bar. It stops
  computing when the bar is removed with its tab (`barRemoved`) and starts
  again at the next `setGeometry`. It also holds the time spread
  ([Timestamps](#timestamps)).
- Follow (`F`, `F10`, `setFollowing`): while following, each flush scrolls to
  the last line. A user move that leaves the bottom (scroll, page, caret, go
  to line, scroll bar) releases it. With `follow.onOverscroll`, scrolling down
  5 rows past the bottom engages it. Growth the document indexes needs no
  action here beyond the flush, which also calls `SearchController.appended`.
- A `MISSING` file shows a notice and the status note; its content stays. The
  notice is left out while a failed reopen waits, because that run showed one.
  `TRUNCATED`, `REPLACED` and `MODIFIED` go to `DocumentHost.reopenChanged`.
- `detach()` drops every later result and detaches the search and the
  filtered view; `close()` also closes the document on a worker.

`FilteredController` is the filtered view over `FilteredView` (`qlogg-core`):
the shown result set's lines, the marks, or both. Its rows change when the
search flushes, the marks change or the mode changes; a mode change keeps the
caret on its line or the next listed one. In marks and matches mode the marks
outside the matches come from a snapshot built on a `qlogg-search-` worker
(`FilteredView.buildExtras`), at most one at a time and not while a mark
toggle runs; until it arrives the rows keep the previous snapshot, so the
marks are never walked on the UI thread. Moving the caret to a row shows its
line in the main view. Its line numbers are the source lines' and toggle
apart from the main view's.

Context lines come from the shown set's toggles (`SearchController.shownOptions`:
the current search's as it ran, or a kept set's), so a kept set keeps its
counts. After the matches change, `ContextRows.extend` runs on a
`qlogg-search-` worker before the marks snapshot, one build at a time; the
rows stay on the installed build until the next arrives. `grew(lineCount)`
re-reads the rows when the line count changes, so the last result's context
extends with the file without a search. `contextRow` and `startsGroup` feed
the row shading and the group rule; navigation, counts, the overview bar and
the gutter bullets read the matches only.

`SearchController` (from `DocumentController.search()`) runs one document's
search and holds its field text, toggles and result sets.

- `run(text, options)` cancels a running search and starts a new generation
  with a new `MutableLineSet` charged to `RESULTS`; empty text only clears.
  The search covers the lines indexed when it starts, narrowed by the search
  start and end lines and, with `inSelection`, the selected lines. A combined
  expression that does not parse fails at once with its column; a regex
  compiles on the search thread and fails there. A search that starts sets
  the filtered view to marks and matches.
- With `keepResults` on, a run moves a finished current set into `kept()`
  instead of emptying it. A kept set stays charged to `RESULTS` until
  `removeKept` or `detach`; `show(index)` picks the set the filtered view
  shows. A search still running is replaced as usual, because its set is
  still growing. An auto-refresh running into a finished set is canceled and
  the set is kept with the appended lines the refresh reached.
- The search thread keeps the newest report in an `AtomicReference`, never
  replacing a newer generation's, and posts through a `UiUpdateQueue`; a flush
  drops a report from an older generation. A replaced search's own `finished`
  call empties its result set, so a canceled search that merged one more chunk
  still returns its reservation.
- Each search holds the `ActivityIndicator` active until its `finished` call
  or `detach()`, whichever comes first, and a document entry until its
  `finished` call. It searches one index snapshot, whose line count and
  indexed bytes it records.
- `toggle(command)` flips one toggle for keys `4` to `9` and fires
  `optionsChanged`.
- With `autoRefresh` (`8`) on, `appended()` searches the lines a finished
  search did not cover into the same set and generation, starting at its last
  line, which may have grown: that line's result is removed first. The
  refresh shows no progress and holds no activity; the state stays `DONE` with
  the new match count, and a refresh that ends with more growth starts
  another. A search stopped at a limit, or whose range ends before the end of
  the file (search end line, in selection), is not refreshed.
- With `range` on (`TOGGLE_RANGE`, no key), the field text starts a range and
  `rangeEnd` ends it (`SearchQuery.rangeEnd`); an empty or malformed end fails
  at once. The search start and end lines and the selection scope limit the
  scan as for any search, and a range open at the scope's end runs to it. An
  auto-refresh starts in the state the engine reported before the last
  searched line, so an open range extends over appended lines.
- `selectRange()` (`Ctrl+R`) selects the range around the main view's caret
  with the field text, the range end and the current toggles, through
  `RangeFinder` on a `qlogg-search-` worker inside a document entry, holding
  the `ActivityIndicator`. A newer request or `cancel()` stops an older one;
  with no range there, a notice shows and the selection stays.
- `edit(ADD | EXCLUDE | REPLACE, selection)` rewrites the field text with
  `SearchEdits` and runs it when `search.runOnAdd` is on.
- `findNext()` and `findPrevious()` move the caret to the nearest match of
  the shown set after or before the caret's line and center it; with none, a
  notice shows and the caret stays. They read the set on the UI thread, whose
  read lock a merge holds for one chunk.

`QuickFindController` (from `ApplicationController.quickFind()`) walks
occurrences in the focused view without a result set. Fixed text by default
(`quickFind.regex`), ignore case from the bar's box. A find compiles the
pattern and runs `LineFinder` on a `qlogg-search-` worker over the view's
`rows()`, holding the `ActivityIndicator` active; a newer find cancels the
older one, and a result from an older generation is dropped. The match
becomes the view's selection. With no occurrence left in the direction, the
notice "Reached end of file" or "Reached start of file" shows and the
selection stays; there is no wrap. With `quickFind.incremental` on, each
change to the text searches forward again from where the caret was when
typing began. `*` and `.` (forward), `/` and `,` (backward) find the selected
text.

### Folding, block selection, wrap and save

`DocumentController` maps rows to lines through a `FoldMap`, so a collapsed
entry is one row; `FoldController` finds entry bounds with `FoldClassifier` on
a `qlogg-read-` worker, one classification at a time and only around the
visible rows. Toggle Fold classifies around the caret's line and collapses its
entry, or expands a collapsed one. Collapse All Folds collapses each entry as
the region around the visible rows is classified; an entry the user expands
stays expanded until the next Collapse All. A fold change keeps the top line
and the selection on their lines. Go to line, find next and previous, mark
jumps, filtered-view clicks and quick find expand the entry that hides their
line (`revealRow`); an overview click goes to a row and expands nothing. Copy, mark toggles and save walk `rows()`, so
lines hidden in a fold are skipped. The filtered view lists lines regardless
of folds. A Toggle Fold pressed while a classification runs waits for it and
then runs; a later press replaces a waiting one, and Collapse All, Expand All
or new rules drop it. New rules (Tools > Options, Folding, through
`setViewOptions`) reach the open files of every window and expand their
folds.

`LineViewController` holds a block selection (`selectBlock`): the display
columns between anchor and caret on every row between them. Copy takes each
row's part through `LineReader.appendColumns` and joins rows with LF; a stream
copy joins them with the platform line separator. Any stream selection, caret
move or `select` ends block mode. With text wrap on (`setWrap`, both views of
a document at once) the first column stays 0, a read takes up to 65,536
columns per line, and the viewport's rows follow the lines the view reports
as fitting whole (`wrapFitted`), so the last lines can scroll into view; a
block selection is refused with a notice. Folding is unchanged by wrap: a
collapsed entry is still one line, wrapped.

`selectWordAt`, the double click, selects the letters, digits and underscores
around a position, or around the char before it, within the decoded column
window. A row with a char outside printable ASCII selects nothing, because
the view's hit testing follows the grid.

Copy with line numbers puts each source line's number and a tab before it.
Save Selection writes the selected lines, or the block's columns, and Save
Filtered Results every filtered row, as UTF-8 through `AtomicFiles` on the
copy worker, charged to `COPY` at 7 bytes per file byte (the text plus its
UTF-8 bytes). A line over `LineReader.MAX_LINE_BYTES` is saved cut, and the
saved notice counts those lines; a block save does not count them. The chosen
path passes `SafePaths.logFile` on a
`qlogg-lifecycle-` worker; a path whose real path is the `realPath()` of a
document open in any window is refused, so QLogg never writes a file it shows.

### Search across open files and automatic highlighter sets

`MultiSearchController` runs one text and set of toggles as the current
search of every open document, through each document's `SearchController`,
so each uses its document's encoding and result set and is charged to
`RESULTS`. `results()` is one `FileResults` group per file in tab order; Stop
cancels every search of the group, and a closed or reloaded tab drops its
group. A group is a snapshot of its file at run time. A group stopped by a
limit, or with skipped lines, shows the search bar's status in its row. A run
ignores `inSelection` and leaves each tab's toggle as it was; each tab's search
start and end limits apply.

`ViewFeatures` applies the view settings to each new document and tries the
`HighlightSelector`s in order: a file-name glob on the UI thread, a first-line
regex on a `qlogg-read-` worker over the first 4 KiB, decoded. A first-line
regex reads at most `8 * length + 65,536` chars through the core
`BoundedChars`, the highlighter's bound, and counts as no match past it. The first match colors that document with the chosen set instead of the active sets
(`DocumentController.setAutoHighlightSet`). Choosing sets in the Highlighters
menu while that document shows drops its automatic set.

### Timestamps

`TimeController` (`DocumentController.time()`) holds a document's format
(null disables Go to Time, Time Offset and Time Zone), the zone of local
times, the display zone (`time.displayZone`) and the tab's offset in
nanoseconds. A format or zone change makes both views read their times
again; an offset change only reformats them and schedules a session save.

- `TimeFeatures` gives a new document the format chosen for its file
  (`PositionStore` `.timeFormat`; `none` or an unknown id is no format), else
  the one `LogDocument.open` detected, and the zone chosen for the file
  (`.zone`), else `time.zone`. `DocumentOpener` skips the detection for a file
  with a remembered `.timeFormat`. View > Timestamps > Format
  (`setTimeFormat`) and Time Zone apply at once and record the choice for
  the file; pasted text records nothing. Edit Formats replaces the user
  formats (`setUserTimeFormats`, saved as `time.format.N.*`); an open
  document's user format takes the edited format of its name, or none when
  it was removed. The editor's preview line is the caret line's first 256
  chars, read on a `qlogg-read-` worker before the editor opens.
- Each view's `CaretTimes` reads the caret line's time and, for a selection
  over more than one row, its first and last rows' lines' times through
  `TimeReader.at` on a `qlogg-read-` worker, one read at a time with one more
  queued, skipped when the lines and the format are those of the last read.
  The filtered view reads its rows' source lines.
- `goToTime(shown)` subtracts the offset and runs on a `qlogg-search-` worker
  inside `LogDocument.enter`, holding the `ActivityIndicator` until its result
  posts. It searches the index snapshot taken at the start. The order check
  (256 `TimeSamples`, 2,112 bytes charged to `HIGHLIGHT`) is kept while the
  index generation and the format stand and returned on `detach`. A descent
  or an inconsistent `TimeSearch.locate` runs `locateLinear` with progress
  notices at most every 250 ms and the out-of-order notice. A refused charge
  leaves the order unknown and runs `locateLinear` without that notice. A
  newer request, `Esc`, `detach` and a format or zone change cancel the
  running one, and a result whose generation is not the newest is dropped.
  The found line turns follow off and shows through `showLine`, and
  the filtered view's caret moves to that line's row or the next listed one.
  Notices: no format, no stamped line, target before the first stamp,
  target after the last stamp while indexing runs, out of order, canceled,
  and a read failure.
- The offset is the session's `window.W.tab.N.timeOffset`; a reload, a reopen
  and a tab move keep it, as they keep the follow state.
- View > Timestamps > Time Spread (`TIME_SPREAD_*`, `view.timeSpread`) sets
  one mode for every open document. With a mode and a format,
  `OverviewController` samples a stamp at each row boundary after the bins,
  in the same `qlogg-search-` computation, and keeps the `TimeSpread` levels
  (`spread()`, 1 B per row plus 64, charged to `HIGHLIGHT`). The samples are
  charged only while they are read. A refusal or a read failure shows no
  spread. A new height, mode, format or zone samples at the next computation;
  an offset changes nothing. Growth, or any change of the row count, the
  collapsed entries or the index generation, samples no sooner than
  `GROWTH_MILLIS` (2 s) after the last spread: a `UiDispatcher.timerExec` wait starts when a sampled
  computation posts, and its end samples what grew meanwhile.

`TimeSyncGroups` (`WindowSet.timeSync()`, UI thread) holds the time sync
groups, which span the windows, and runs Scroll All Tabs to This Time. A group
is a set of `DocumentController`s, and a tab is in at most one.

- `TimeController.syncTo(shown)` is `goToTime` for a move another tab caused:
  the target for tab `k` is the source's time plus its offset, minus
  `offset(k)`. It releases follow and shows no position or progress notice; a
  cancel by `Esc` (`STOP` on that tab, with `goToTime`'s rule) and a read
  failure show theirs. A tab without a format stays where it is, and a hidden
  tab moves as a shown one does.
- `DocumentController.caretMoved` feeds `TimeController.caretMoved`, which
  reports a move to another source line to the group's `SyncListener`. A sync
  move, a remembered position (`restorePosition`) and a reload's kept line
  (`keepLine`) are quiet, so members cannot bounce and a reload moves nobody.
  Follow scrolls without moving the caret and reports nothing.
- A reported move cancels every member's running sync move; the group's last
  move starts `readCaretTime` on the source (a `qlogg-read-` worker, the
  newest read wins) after a 100 ms `timerExec`, then `syncTo` on the others.
  A caret move in any tab also ends that tab's own sync move, Scroll All's
  included.
- Membership follows the controller, so a tab move keeps it; `replace` hands
  it to a reload's or reopen's new controller before the old one closes, and
  `TimeController.detach` removes it on any close. A group left with one member
  dissolves. `ApplicationListener.timeSyncChanged` reports every change, for
  the tab tooltips.
- The session's `window.W.tab.N.syncGroup` is the group's place among the
  groups with a saved tab, from 1. A restored tab waits in `restored` until a
  second tab of its number arrives, so a group restored with one open member
  never forms.

### Tab data dot

`TabDataModel` (`ApplicationController.tabData()`) keeps a `TabDataState` per
tab of one window: `NONE`, `NEW_LINES`, `NEW_MATCHES` or `FOLLOW_STOPPED`
(a keyword trigger turned follow off). It listens to each
document's `DocumentViewListener` and `SearchViewListener` from
`ApplicationController.start`, before indexing begins, and to the
`ApplicationListener` events. A tab that is not shown takes `NEW_LINES` when
its line count grows after the first indexing pass ends, and `NEW_MATCHES`
when its search's match count grows from `DONE` to `DONE` within one
generation, which only the auto-refresh of appended lines does; a new search
run never sets a state. A state replaces only a lower-ranked one
(`outranks`). Showing the tab sets `NONE`. A reload or reopen keeps the
tab's state, and a replacement indexed in the background with at least one
line takes `NEW_LINES`. A close or move out detaches the listeners.
`TabTips.text` builds a document tab's tooltip from its path, state and sync
group.

While a quiet rule acts on a document (`TriggerController.defersDot`), its
growth waits for the trigger scan: `NEW_LINES` needs a scanned line no quiet
rule matched, and `NEW_MATCHES` needs a new match on such a line, checked once
the scan covers the last new match. At most 10,000 new matches are checked
per growth; past that the growth counts as matching.

### Keyword triggers

`TriggerController` (`DocumentController.triggers()`) applies the
`Triggers` of the rules `HighlightComposer.triggerRules` returns: the active
sets, or the automatic set, highest priority first. `refreshHighlights`
rebuilds them when the rules change; a running scan keeps the rules it
started with.

- Scope: complete lines appended after the first indexing pass ends. A
  reload or reopen creates a new controller, so a first pass, `F5` and an
  automatic reopen never fire. A partial last line is scanned once it ends.
  Growth that arrives while no rule acts is skipped, so a rule added later
  does not act on it.
- Each flush with unscanned growth and no scan running starts one on a
  `qlogg-search-` worker under `LogDocument.enter`. It loops over
  `Triggers.scan` in batches of 4,096 lines to the indexed end, re-reading
  the index each batch, and posts each batch. The token is canceled by
  `detach`. A scan that fails to read keeps its position, and the next starts
  when the complete line count changes.
- On the UI thread a batch: logs a template failure once (WARN), repaints
  after marks, calls `followStoppedByTrigger` for a stop-follow match, and
  notifies `TriggerListener`s. `followStoppedByTrigger` does nothing while not
  following; otherwise follow turns off and `DocumentHost.followStoppedByTrigger`
  shows a notice naming the rule (pattern cut to 60 chars) for the shown tab,
  or sets `FOLLOW_STOPPED` for another.
- A mark or comment refused by `RESULTS` or the 1,000-comment cap shows one
  notice per scan, naming the tab.
- Quiet lines stay in a `MutableLineSet` charged to `RESULTS` until the
  document closes. When the share refuses them the set is cleared, a notice
  shows, quiet lines count toward the dot again for that document, and later
  scans keep none.

### Menu state

`CommandStates` maps each `CommandId` to its menu item's checked and enabled
state, read from an `ApplicationController` on every call, on the UI thread.
A check command in `APPLICATION_CHECKS` reads the settings, theme, block mode
or debug log file and is always enabled; any other check command reads the
shown document and is enabled while one shows. `FILE_COMMANDS` (Reload, the
path commands, Open in Editor, Encoding > Auto) need a file, so clipboard text
disables them. Move to New Window needs a document; Add to Favorites needs a
file that is not a favorite, Remove from Favorites a favorite. It also lists
the highlighter sets (`highlightSets`), the open files (`openedFiles`) and a
tab menu's enabled items (`tabItems`). An ArchUnit rule keeps the views from
reading the getters only this mapping needs.

`EncodingChoices.available()` is the Encoding menu's lists, computed once:
the supported entries of `COMMON_NAMES` in order, then every other charset of
`Charset.availableCharsets()`, which takes 130 to 230 ms cold (measured), so
it runs on a worker. `ApplicationController.loadEncodingChoices` delivers
the lists on the UI thread, at once when computed, else from a
`qlogg-lifecycle-` worker. `commonCharsets()` looks up the common names only.
`forced(document)` is the charset the menu checks: the forced one of a
document with a file, else empty for Auto.

## Settings (`app.config`)

`SettingsLocation.resolve` picks the settings directory: `--settings`
(`EXPLICIT`), `<qlogg.home>` when `<qlogg.home>/qlogg.conf` exists
(`PORTABLE`), else the per-user directory (`PER_USER`; table in the root
`README.md`). An environment value that is not an absolute path is ignored.

`FoldRulesText.parse` checks the Options Folding tab's text: blank lines are
dropped and other lines kept as typed, every pattern must compile, at most 64
continuation patterns, and at least one pattern in all.

`ConfigStore` loads and saves `Preferences`, an immutable record, in
`qlogg.conf`. Both block and take a lock, so callers run them off the UI
thread; the launcher loads before the Display exists.

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
`Preferences` holds `ui.theme` (`ThemeMode`), the search history and its
size, `search.maxResults`, `search.runOnAdd`, the quick find options,
`view.splitPercent`, `view.overview`, the predefined filters, the
`HighlightSettings`, the `ViewOptions` (wrap on open, whitespace markers,
hidden ANSI colors, fold rules, highlighter selectors and `view.timeSpread`,
read and written by
`ViewFile`), the `TimeSettings` (`time.zone`, `time.displayZone`, at most 32
`time.format.N.name`, `.locate` and `.pattern`, read and written by
`TimeFile`; an unknown zone reads as the system zone and a format that does
not compile, or repeats a name, is dropped), the `Options` (font, `recent.count`, `monitor.*`,
`encoding.default`, `session.loadLast`, `shortcut.*`, `favorite.N`,
`log.file`) and the `UiState` (`window.*`, `searchOption.*`, `view.lineNumbers`,
`view.filteredLineNumbers`, `open.directory`, `recent.N`, `dialog.*`), read
and written by `OptionsFile`; the key table is in the root `README.md`. Lists
use indexed keys read from 0 until the first gap; a
history entry over 4,096 chars, or a filter without a name or pattern, is
dropped.

`ConfigStore` also holds two stores in the same directory. Both read and
write through `SettingsFile`, which applies the table above to their own file
(`<name>.invalid`, atomic owner-only writes, read-only directory).

| Store | File | Contents | Limits |
|---|---|---|---|
| `PositionStore` | `positions.conf` | Per canonical path: top line, caret line and column, forced charset, the timestamp format and zone the user chose (`pos.N.*`) | 500 entries, least recently used dropped; 1 MiB |
| `SessionStore` | `qlogg_session.conf` | Per window W from 0: `window.W.active`; per tab `window.W.tab.N.path`, `.name`, `.follow`, `.marks` (`3-7,9`, 0-based), `.comment.K` (0-based line, a space, the text; K from 0 without gaps), `.timeOffset` (nanoseconds, when not 0), `.syncGroup` (from 1, when in a group); `droppedComments`, the comments the save left out at the cap, when not 0 | 32 windows, 256 tabs over all windows, 100,000 mark ranges and 1,000 comments over all tabs; 4 MiB |

`PositionStore.get`, `put` and the setters touch memory under a lock held
for no I/O, so the UI thread calls them; `load` and `save` block. A session's
marks go into `MutableLineSet`s charged to the `RESULTS` share; a refusal
keeps the earlier marks and shows a notice.

## Search text (`app.search`)

Pure functions, tested without a document.

| Class | Contract |
|---|---|
| `SearchHistory` | `add` puts a search first, drops an equal earlier entry and cuts the list to the size; `completions(prefix, caseSensitive)` lists the entries that extend a prefix, most recent first |
| `FilterPatterns` | `compose` joins filters as regex alternatives (`a\|b`, a `LITERAL` filter escaped), or as quoted terms joined with `or` with combining; `present` reports which filters stand in a text between separators |
| `SearchEdits` | `add` (`text\|sel`, or `text or "sel"`), `exclude` (`"text" and not "sel"`) and `replace`; the selection is escaped for the mode, and add to a literal search or exclude turn combining on |
| `FilterFile` | Filters as `filter.N.name`, `filter.N.pattern`, `filter.N.regex`; an export adds `version=1`. `read` validates the path through `SafePaths.logFile` and refuses a file over 1 MiB, without `version=1`, with an incomplete entry or with over 1,000 filters (`FormatException`); `write` replaces the file through `AtomicFiles` |

## Highlighters and color labels (`app.highlight`)

| Class | Contract |
|---|---|
| `HighlightSettings` | The sets (at most 256, distinct ids), the active ids (unknown ids dropped), 9 `ColorLabel`s and the `MatchHighlight`. `rulesInApplyOrder` lists the active sets' rules so the top rule of the first active set applies last and wins; `withImported` appends only sets with new ids |
| `HighlightFile` | The keys in its Javadoc. `load` skips a malformed set or rule and keeps the default for a malformed label. A malformed trigger value (flag not `true` or `false`, template over 1,000 chars) turns only that action off in `load` and `read`, with one WARN per call; a file without trigger keys loads with `RuleTrigger.NONE`. `read` takes the user's path through `SafePaths.logFile`, refuses a file over 1 MiB, without `version=1`, with over 256 sets or a malformed set or rule (`FilterFile.FormatException`); `write` replaces the file through `AtomicFiles` |
| `HighlightComposer` | `triggerRules` returns the active or automatic set's rules highest priority first, for `TriggerController`. `compose` returns one document's rules, lowest priority first: the active sets (`rulesInApplyOrder`), or only the automatic set while a set has its id; the match rule of the last search; the color labels in the palette's colors. `LastSearch` carries the search toggles the match rule reads, so `app.highlight` does not depend on `app.view` (that dependency forms a package cycle). `matchRule` returns null when the match highlight is off, for empty text, for a combined or inverse search, and for a pattern over `MAX_PATTERN_CHARS`, counting the whole-word wrap `(?<!\w)(?:...)(?!\w)` that `StandardMatchers.wholeWord` puts around the quoted or regex text |
| `HighlightSetsEditor` | The Configure Highlighters list model over a copy of the sets: add, remove, move and rename sets, add, remove and move rules, at most `MAX_SETS` sets and `MAX_RULES` rules per set. Each edit returns the index to select next; an index out of range changes nothing; removing a set drops its active id. `setRule` keeps a rule that does not compile and returns `validate`'s message, which also reports a comment template whose regex does not compile; `importSets` appends the sets with new ids |
| `LabelState` | One document's labeled texts. `apply(n, text)` moves a text to label n, or removes it when it has n; `next` steps the cycle (labels with `cycle`) and removes a labeled text's label, still advancing; `EMPTY` clears and restarts the cycle. At most 256 texts; cost 56 bytes per text plus 2 per char |

## Scratchpad (`app.scratch`)

`Transforms.apply(kind, text)` runs one built-in transform: Base64 and hex to
and from UTF-8, URL decode, JSON and XML indentation. JSON and XML are
reformatted by a scan of quotes, brackets and tags, not parsed, so no entity,
DTD or external reference is read. Input that does not fit throws
`IllegalArgumentException` with the reason; an input or output over
`MAX_CHARS` (1 Mi) throws its subclass `TooLongException`. JSON and XML
indent at most `MAX_DEPTH` (32) levels, so the work is linear in `MAX_CHARS`
whatever the nesting. `values(text, zone)` lists the
selection's CRC32, its value in the other number base and, for 10 or 13
digits, the epoch time in UTC and in the zone. Nothing runs code from the
text.

## UI text (`app.i18n`)

`UiText` names every string the UI shows; its constant name is the key in
`messages.properties` beside it, the only bundle shipped. `text()` returns the
value and `format(args)` applies it as a `String.format` pattern in
`Locale.ROOT`. Command and menu labels come from it through `CommandId` and
`CommandGroup`. `UiTextTest` checks that constants and bundle keys match, and
`ArchitectureTest` that every constant is read outside `UiText`. The usage text
and key names (`Ctrl+`, `PgUp`) stay in code.

## About and palette (`app.about`, `app.theme`)

`AboutInfo` is the About dialog's content: version, Java, OS, toolkit line,
settings directory and mode, and `details()` for Copy Details.
`licenseText()` reads `LICENSE`, `NOTICE` and `THIRD-PARTY.txt`, which the
build copies into this module's jar.

`UserGuide` is Help > Documentation's page. At `generate-resources` the build
copies commonmark-java 0.28.0 and its GFM tables extension (BSD-2-Clause,
build only) and runs `src/build/GuideRenderer.java` with the Java source
launcher: `docs/user-guide.md` becomes one HTML page under
`target/generated-resources/guide`, each image a `data:` URI and each heading
an `<a id>` with its GitHub anchor. A link that leaves the page or names no
heading, or an image that is not a PNG beside the guide, fails the build;
`UserGuidePageTest` checks the shipped page's links against its anchors. `writeCopy` streams the page to
`<settings>/cache/guide.html` (owner-only folder, `AtomicFiles`) on a
`qlogg-lifecycle-` worker; the controller then calls `MainView.showGuide`,
else `showGuideElsewhere`: `launch` on the copy, else a notice naming it. The
view also calls `showGuideElsewhere` when its guide window fails after
`showGuide` returned true.

`Palette` holds the log view, gutter bullet and overview bar colors, the
time-spread colors among them, as `Rgb` values (`LIGHT`, `DARK`). `ThemeController` resolves `ThemeMode` against
`ThemeService.systemDark()` and pushes a new palette to `PaletteListener`s;
`SYSTEM` follows the service's change events. Each mode change, and each
system change in `SYSTEM`, also calls `ThemeService.preferDark` so native
controls match the palette. Highlighter colors are not
remapped.

## Recent log lines (`app.log`)

`RecentLog` holds the last 500 log lines, each cut at 2,000 chars, for Show
Log. Append and snapshot each hold the deque's lock only to copy references.
The launcher's `RecentHandler` feeds it and marks WARN and ERROR lines;
`warnings()` counts them, and the warning listener runs on the logging thread
after each one. `LogFiles` is the debug log file as the controllers see it;
the launcher's `FileLog` implements it.

## Single-instance handoff (`app.ipc`)

`InstanceServer.claim(settings)` takes the OS lock on `instance.lock` with
`FileChannel.tryLock`, deletes any `instance.endpoint`, binds an ephemeral
loopback port and writes the endpoint (`version=1`, `port`, `token` as 64
hex) owner-only through `AtomicFiles.writeOwnerOnly`; it returns null while
another live process holds the lock. The OS releases the lock when that
process dies, SIGKILL included, so a stale endpoint file never decides
liveness. Until `close`, `SafePaths.logPath` rejects the lock file with
`INSTANCE_LOCK_REFUSED`, which every open, import and export shows: the POSIX
lock ends at the close of any channel on the file, the file watch's included,
and a second primary's endpoint is deleted when the first quits. A restored
session holding the file shows the error once and saves without that tab.
`start` accepts on a `qlogg-ipc-` thread and serves each connection
on its own, at most 4 at once; a launch past that waits in the socket backlog
of 128. Authentication and the request are read within 5 s. `close` stops
accepting, deletes the endpoint, then releases the lock; call it before
`Workers.close`, which waits for the accept thread.

Until `deliverTo` names the `Receiver`, an authenticated request stays on its
open connection, unanswered. `deliverTo` does not block; the held requests go to
the receiver in arrival order and each client then reads `OK`, so a client that
exits 0 knows the receiver has its files. Held files are bounded by the
connection cap: 4 requests of at most 256 files and 1 MiB. `close`, or 30 s
after `claim` without `deliverTo`, answers the held requests `ERR`. A primary
that dies closes the connections. Either way the client opens its own window,
so a held file is not lost. Past the backlog a connect fails and the launch
opens its own window.

`InstanceClient.handOff` reads the endpoint, waiting up to 2 s while it is
missing, connects within 1 s and runs the exchange within 35 s, which outlasts
the 30 s hold so the client reads the `ERR`:

```text
C: QLOGG 1 HELLO nonceC
S: QLOGG 1 PROOF nonceS HMAC(token, "server" nonceC nonceS)
C: AUTH HMAC(token, "client" nonceS nonceC)
C: FOLLOW 0|1
C: OPEN base64url(UTF-8 path) line      (repeated, at most 256; line 0 = none)
C: END
S: OK | ERR
```

Nonces are 32 bytes from `SecureRandom`; proofs are compared with
`MessageDigest.isEqual`. The client sends nothing after a wrong `PROOF`, so
no file reaches a listener without the token; the server sends nothing after
a wrong `AUTH`. Lines are printable ASCII up to 16 KiB, at most 1 MiB per
connection; each path must be absolute. Every step throws `IOException`, and
the launcher then opens its own window. `LineChannel` runs the socket
non-blocking under a `Selector` against one deadline, because
`ArchitectureTest` bans `java.net.Socket`, whose timeouts a blocking channel
would need. `refusal` turns the handoff off
for a POSIX settings directory another user owns or others can write.

`EventLoopWatchdog` posts a probe through the `UiDispatcher` every
`intervalMillis` from a `qlogg-watchdog-` thread. A probe still waiting after
`boundMillis` reports a stall with the UI thread's stack
(`StallListener.stalled`; `LOGGING` logs it at WARN), then the whole delay once
it runs (`recovered`, INFO). It never interrupts the UI thread, and ends at
`stop` or when the workers close. `enabled()` is true when its logger takes
DEBUG (`-d 2` and above).

## Tests

Controller tests run over `FakeView` and `ManualUiDispatcher`, with real
workers, files and file monitor. `FakeView` records any `MainView` call
other than `setActive` and `setIdle` made off the test thread, and the
teardown throws it, so a worker path that touches the view fails the test.
`FollowControllerTest` covers follow and its
release, overscroll, follow on load and `-f`, auto-refresh over a grown last
line, truncation, rename-rotation, a retargeted symbolic link (POSIX only) and
delete-then-recreate reopening the tab,
reload and stop loading. `ReopenRetryTest` holds the `LINE_INDEX` share so an open
fails on every platform, or deletes the file between the check and the open: the
reopen runs again and following resumes, retries keep the content, one notice
and the Waiting to reopen note, a change dropped during a failing reload is
reported again, and `F5`, `Esc` and closing the tab during the wait or a running
retry. Regression: one failed reopen ended following until `F5` while the status
read Following. `RememberedStateTest` runs the application twice over
one settings directory: comments past the session cap show one notice at the
next start and the next save clears their count; tabs, names, follow, marks, a mark comment, the
active tab, the caret, the zoom, favorites, recent files, line numbers and the window state
come back; `+line:N` wins over the remembered position; an encoding override
reindexes and returns on the next open, and Auto clears it; a remembered name
the runtime does not support logs one WARN naming it and opens with detection,
and a supported one logs none; an instance that
did not load the session writes none. `ReloadTest` checks that `F5` keeps
marks, their comments and labels and drops marks and comments past the end,
and that a change on disk clears them. `MarkCommentsTest` covers Edit Mark
Comment on the caret line, an empty comment, unmarking and removing, a
refused comment's notice, and the marks list following edits, removals and
the shown tab and releasing its charge on close.
`TabCommandsTest` covers the tab menu's close and rename commands, the path
commands and the executable check, dropped files and open from clipboard
(no file commands, the `PASTED` charge returned on close).
`TabDataModelTest` checks that initial indexing and growth of the shown tab
set no state, background growth sets `NEW_LINES` and showing clears it,
matching growth wins over new lines and survives a reload, a search run in a
background tab sets nothing, a background reload or truncation sets
`NEW_LINES`, and a closed tab reports no further change.
`TabTipsTest` covers the tooltip over an empty path, each state, a sync group
and a `&`.
`HighlightControllerTest` covers a highlighter file's round trip, its read and
write failures and a shutdown dropping an import's result.
`TriggerControllerTest` covers stop follow on the shown tab (notice) and a
background tab (`FOLLOW_STOPPED` above `NEW_MATCHES`), a templated mark on
appended lines only, no firing on the first index, a reload or a truncation
reopen, rules of two sets firing together, quiet growth and quiet matches
leaving the dot, an append of three batches and more scanned once per line in
contiguous batches, one notice for refused comments, growth before a rule
acts staying unscanned, a scan that cannot read ending instead of restarting,
and an empty quiet set after a refusal. `HighlightFileTest` round-trips
triggers through export and `qlogg.conf`, loads a file without them, and turns
off only the malformed action.
`ViewFeaturesTest` covers Stop ending every group of a search across files on a
catastrophic regex, a reload dropping its group, In selection ignored by that
search, the count of cut lines in the saved notice, the fold mapping, expansion by go to line, mark
jumps, find next and `showLine`, collapse all and expand all with the `FOLDS`
charge returned, copy skipping hidden lines, block copy and block extension,
copy with line numbers, wrap with its fit and the block refusal, folds under
wrap, save of a selection and of the filtered view with the refusal of an
open file and of an open link's target, grouped search across files and a
closed tab's group, selectors by
name and first line, a catastrophic first-line regex giving up within its
bound, hidden ANSI colors in search, and sending text to the scratchpad.
`TransformsTest` covers each transform and the derived values.
`TimeControllerTest` covers detection at open with the caret time, a trace
line taking the time above it, a selection's difference and the offset
applied; go to time moving the caret, releasing follow and pairing the
activity, between two entries and with an offset; the notice before the
first stamp; an out-of-order file searched line by line, after the order
check or after a probe outside its bracket; a refused order check searching
line by line without the out-of-order notice; a format or zone change dropping
a running search; a newer request dropping the older result; `Esc` canceling with its notice and a held save,
and over held indexing leaving it running until a second `Esc` stops loading; the notice for
a target past the indexed part; progress notices of a linear scan held at a
gated line; the filtered view's rows' times; a close during a time read or after its post-back; a file without
timestamps disabling the commands; a remembered format, none or a user
format, skipping detection on the next open; the Go to Time, Time Offset and
Time Zone dialogs' results, the offset in the session draft; a reload keeping
the offset; and the order check's charge returned on close. `TimeSpreadTest`
covers the mode from the menu command to every open document, a new document
and the settings; the shaded rows of a generated pause in both modes; no
spread without a format or with the bar hidden or removed; samples at once
for a height, mode, format and zone, none for an offset; growth held until
the 2 s timer (`ManualUiDispatcher.holdTimers`); a timer without growth
sampling nothing; a refused share and its recovery; and the charges returned
by Off and close. `OverviewFoldsTest` checks collapsed entries against rows:
a match past the row count and one hidden in an entry, a click past the entry
and on it, the bins refreshed by a fold change, the spread's rows, and a swap
of equal entries, which keeps the row count. `RememberedStateTest` brings a tab's offset back through a session reopen,
and `TabMoveTest` moves it with the tab. `TimeSyncGroupsTest` covers a move
reaching a member through both offsets, a go to time and a filtered row of a
member propagating, no report for a sync move, a kept or restored position,
a kept line or restored position waiting for indexing, a newer request replacing a waiting position, and follow, no sync from a followed member's
appended lines, a go to line waiting for indexing reporting its move, moves within the debounce running one sync, a newer move canceling the
target's search without a notice, a target's own move ending its sync move, `Esc` on
a target, follow released without a position notice, a hidden tab moving, the
cascade's join rules and labels, close dissolving a group of one, a reload
keeping the membership, session renumbering and restore, and Scroll All with
its notices. `TabMoveTest` syncs across windows after a move and
`StateStoresTest` round-trips `syncGroup`. `TimeTextTest`
covers the caret time, differences (sub-second, days, negative, fraction
digits), offsets round-tripping and refused, targets with and without a
date, and the format names and tooltip.
`ShutdownLifecycleTest` shuts down 12 times at random
points during a search, a quick find, indexing and the watches, and checks
that no thread survives `Workers.close()`, every file is closed, every
reservation and activity is returned, and each shutdown ends within the
document close bound. The shutdown helper polls `Workers.liveThreads()`
instead of draining the UI queue for it, because threads end without posting
a UI task.
`CloseLifecycleTest` drives several windows through 40 rounds of random
open, paste, reload, search, copy, mark toggle, quick find, new window, tab
close, tab move (to a new window or another window), window close, time sync
joins and leaves, member caret moves and Scroll All, draining the UI queue at
random points, then quits.
Every file a tab showed is closed, no thread survives, and the `RESULTS`,
`LINE_INDEX`, `HIGHLIGHT`, `COPY` and `PASTED` reservations and the activity
counters are 0. The seed is random per run and named in the failure;
`-Dqlogg.closeLifecycle.seed=N` replays one.
`WindowSetTest` runs two windows over one set: a save over a file open in the
other window is refused, handed files open in the
window activated last, which is raised; a handed file another window shows
activates that tab at its line and then follows it, with no second tab; quit runs the quit listeners before
the finish listeners and saves each window's tabs as its own `window.N`;
closing one window keeps the other and drops its tabs from the session;
search toggles used in one window seed the next document in either window;
closing a window while it opens files, reloads or restores a session closes
the document whose open finishes afterwards and skips the rest, with
`GatedCharset` holding the open inside `LogDocument.open`; and
the Help items: Show Log and the warning marker, Debug Log File following the
file (a failed attach stays unchecked), the folder items creating and opening
their folders, Report an Issue copying the details before it opens the issue
page, and Documentation writing the guide's copy and then showing it, opening
it, or naming it in a notice, and a guide window failing later doing the same.
`DocumentOpenerTest` runs the opener with a recording listener: open order and
the `Opening` values, an open failure, a paste over the limit, a reload copying
marks and a change on disk not copying them, a restore reporting names and the
active tab after their opens, and closing during an open, a reload and a
restore (held by `GatedCharset`) or after an open ended and before its
delivery, each leaving nothing delivered, nothing unclaimed and no
`LINE_INDEX` reservation. `HelpControllerTest` checks that a Help result
arriving after the window began closing is dropped, a folder that cannot be
created shows a notice, and a failed launch shows one.
`TabMoveTest` moves a tab and checks the same `LogDocument` and open file,
the line count, forced encoding, tab name, marks, follow, a collapsed fold,
selection, top line, results, a kept set and the filtered view's visibility
in the new window; copy reaching the new window's clipboard; the session
written with both windows before any UI task runs; insertion at the drop
index with the source showing its next tab; refusal into a window showing the
file and during a reload; the last tab moving with the new window requested
at the drop point; and a moved document surviving its source window's close,
then closing once at quit. `TabHostTest` checks that an activity reported
before a move ends in its first window.
`CommandDispatchTest` runs every `CommandId` with no document, on a document
and with its filtered view focused, and requires no exception on the UI thread
or a worker and no activity left running. `HighlightCompositionTest` records
the rules a document composes: sets last-first, then the match rule; whole word wrapping a
quoted or regex pattern; the match color and variance; no match rule past
`MAX_PATTERN_CHARS` (including a whole-word wrap that crosses it) or for an
inverse or combined search; and an automatic set replacing the active sets
while its id exists. `HighlightComposerTest` runs the same cases on
`HighlightComposer` with no document, plus labels last in the palette's
colors. `CopyReleaseTest` checks that a save over the `COPY`
share, a save whose write fails, a copy whose tab closes and a selection read
whose tab closes all return the `COPY` charge and the activity.
`StateStoresTest` (config) also round-trips a tab's time offset and a
file's timestamp format and zone, and reads a malformed offset as 0;
`ConfigStoreTest` round-trips the timestamp settings and drops a format that
does not compile. `StoreFaultsTest` (config) loads a malformed, oversized and unknown-version
session file as no windows and keeps each as `.invalid` on the next save; a
remembered encoding the runtime lacks reads as detection.

`CommandStatesTest` records every check command, file command, Move to New
Window and favorite command's checked and enabled state with no document, with
a document and with its filtered view focused, the same table as the menu
bar's GUI test; then each toggle flipping its check, the favorites swapping,
clipboard text, a forced encoding, and the set, open file and tab item lists.
`EncodingChoicesTest` covers the common-first split and the runtime's lists;
`CommandStatesTest` also receives them through `loadEncodingChoices` on the
UI thread.

`HandoffTest` runs client and server in one JVM over loopback: files, lines
and `-f` arrive; a launch without files still hands off; a wrong token, a
peer without the token and a listener without the token each move no file; a
stale endpoint without the lock is replaced, and one behind a live lock, or a
missing one past the 2 s wait, fails so the launch opens its own window;
16 concurrent launches all arrive at a running primary, and at one whose first
window has not opened, with at most `MAX_CONNECTIONS` held; held files arrive in
arrival order; a close or the expired hold refuses every held launch; a peer
without the token is not held; a listener that never answers ends in the client
bound; close removes the endpoint and releases the lock; the endpoint is `rw-------`; `OPEN` lines need an
absolute path; a shared or foreign settings directory turns the handoff off.
`aKilledPrimaryReleasesTheLock` starts a child JVM (`ClaimMain`) that claims
the lock, kills it with `destroyForcibly`, and checks that the next launch
becomes the primary over the stale endpoint.
