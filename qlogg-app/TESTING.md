# qlogg-app tests

Tests run without a display. Controller tests run over `FakeView` and
`ManualUiDispatcher` with real workers, files and file monitor.

## Test harness

- `ManualUiDispatcher` queues UI submissions until the test drains them on its
  own thread; `runUntil` drives the queue to a condition with a bound, so no
  test sleeps. It checks the condition at least every 10 ms, so a condition a
  worker sets ends the wait without a UI task. `holdTimers` holds `timerExec`
  waits until the test releases them.
- `FakeView` records any `MainView` call other than `setActive` and `setIdle`
  made off the test thread, and the teardown throws it, so a worker path that
  touches the view fails the test.
- `GatedCharset` holds an open inside `LogDocument.open`, so a test can close a
  window or tab while the open runs.
- Logging is off (`quiet-logging.properties`). A test that checks a log
  statement attaches a JUL `Handler` to that logger and sets its level.

## Lifecycle tests

| Class | Covers |
|---|---|
| `ShutdownLifecycleTest` | Shuts down 12 times at random points during a search, a quick find, indexing and the watches. No thread survives `Workers.close()`, every file is closed, every reservation and activity is returned, and each shutdown ends within the document close bound. The helper polls `Workers.liveThreads()` instead of draining the UI queue, because threads end without posting a UI task |
| `CloseLifecycleTest` | Several windows through 40 rounds of random open, paste, reload, search, copy, mark toggle, quick find, new window, tab close, tab move (to a new window or another window), window close, time sync joins and leaves, member caret moves and Scroll All, draining the UI queue at random points, then quit. Every file a tab showed is closed, no thread survives, and the `RESULTS`, `LINE_INDEX`, `HIGHLIGHT`, `COPY` and `PASTED` reservations and the activity counters are 0. The seed is random per run and named in the failure; `-Dqlogg.closeLifecycle.seed=N` replays one |
| `DocumentOpenerTest` | Open order and the `Opening` values, an open failure, a paste over the limit, a reload copying marks and a change on disk not copying them, a restore reporting names and the active tab after their opens; closing during an open, a reload and a restore (held by `GatedCharset`) or after an open ended and before its delivery, each leaving nothing delivered, nothing unclaimed and no `LINE_INDEX` reservation |
| `CommandDispatchTest` | Every `CommandId` with no document, on a document and with its filtered view focused: no exception on the UI thread or a worker, no activity left running |
| `CopyReleaseTest` | A save over the `COPY` share, a save whose write fails, a copy whose tab closes and a selection read whose tab closes all return the `COPY` charge and the activity |

## Windows and tabs

| Class | Covers |
|---|---|
| `WindowSetTest` | Two windows over one set: a save over a file open in the other window is refused; handed files open in the window activated last, which is raised; a handed file another window shows activates that tab at its line and then follows it, with no second tab; quit runs the quit listeners before the finish listeners and saves each window's tabs as its own `window.N`; closing one window keeps the other and drops its tabs from the session; search toggles used in one window seed the next document in either window; closing a window while it opens files, reloads or restores a session closes the document whose open finishes afterwards and skips the rest. Help items: Show Log and the warning marker, Debug Log File following the file (a failed attach stays unchecked), the folder items creating and opening their folders, Report an Issue copying the details before it opens the issue page, Documentation writing the guide's copy and then showing it, opening it or naming it in a notice, without the embedded guide opening it in the system application though the view has a browser, and a guide window failing later doing the same |
| `TabMoveTest` | A moved tab keeps the same `LogDocument` and open file, line count, forced encoding, tab name, marks, follow, a collapsed fold, selection, top line, results, a kept set and the filtered view's visibility; copy reaches the new window's clipboard; the session is written with both windows before any UI task runs; insertion at the drop index with the source showing its next tab; refusal into a window showing the file and during a reload; the last tab moving with the new window at the drop point; a moved document surviving its source window's close, then closing once at quit; a tab's time offset moving with it; time sync across windows after a move |
| `TabHostTest` | An activity reported before a move ends in its first window |
| `TabCommandsTest` | The tab menu's close and rename commands, the path commands and the executable check, dropped files, a dropped folder skipped with a notice, open from clipboard (no file commands, the `PASTED` charge returned on close) |
| `TabDataModelTest` | Initial indexing and growth of the shown tab set no state; background growth sets `NEW_LINES` and showing clears it; matching growth wins over new lines and survives a reload; a search run in a background tab sets nothing; a background reload or truncation sets `NEW_LINES`; a closed tab reports no further change |
| `TabTipsTest` | The tooltip over an empty path, each state, a sync group and a `&` |
| `HelpControllerTest` | A Help result arriving after the window began closing is dropped; a folder that cannot be created shows a notice; a failed launch shows one |
| `CommandStatesTest` | Every check command, file command, Move to New Window and favorite command's checked and enabled state with no document, with a document and with its filtered view focused (the same table as the menu bar's GUI test); each toggle flipping its check, the favorites swapping, clipboard text, a forced encoding, and the set, open file and tab item lists; encoding lists delivered through `loadEncodingChoices` on the UI thread |
| `EncodingChoicesTest` | The common-first split and the runtime's lists |
| `NavigationHistoryTest` | Go Back recording the current line so Go Forward returns, a jump after Go Back dropping the entries ahead, no repeat of the newest line, the oldest dropped past the limit, `clamp` merging entries past the last line and keeping the position, a departure recorded only on commit, `takeOver` |
| `NavigationTest` | Go to Line jumps walked back and forward with the filtered view focused, caret moves not recorded, Go to Bottom dropping the forward entries; a filtered row chosen by `Enter` or a click recording the line from before the filtered view moved the main view; next mark and a quick find match; a reload keeping the history and a truncation clamping it |
| `CommandPaletteTest` | A blank query listing every menu command in menu order with its menu path; the title match ranking Go to Line first for `gtl` and the menu path matching; a disabled command listed disabled and not run; an enabled one run |

## Follow, reload and remembered state

| Class | Covers |
|---|---|
| `FollowControllerTest` | `follow.keepClosed` (a rename over the name, a delete's notice, a change applying at the next reload); follow and its release, overscroll, follow on load and `-f`; auto-refresh over a grown last line; truncation, rename-rotation, a retargeted symbolic link (POSIX only) and delete-then-recreate reopening the tab; reload and stop loading |
| `ReopenRetryTest` | Holds the `LINE_INDEX` share so an open fails on every platform, or deletes the file between the check and the open: the reopen runs again and following resumes; retries keep the content, one notice and the Waiting to reopen note; a change dropped during a failing reload is reported again; `F5`, `Esc` and closing the tab during the wait or a running retry. Regression: one failed reopen ended following until `F5` while the status read Following |
| `ReloadTest` | `F5` keeps marks, their comments and labels and drops marks and comments past the end; a change on disk clears them |
| `RememberedStateTest` | Runs the application twice over one settings directory: comments past the session cap show one notice at the next start, and the next save clears their count; tabs, names, follow, marks, a mark comment, the active tab, the caret, the zoom, favorites, recent files, line numbers, the window state and a tab's time offset come back; `+line:N` wins over the remembered position; an encoding override reindexes and returns on the next open, and Auto clears it; a remembered encoding the runtime does not support logs one WARN naming it and opens with detection, a supported one logs none; an instance that did not load the session writes none; a closed tab listed in Recently Closed across runs, Open All reopening it at its remembered position and Clear emptying the list; Open All showing an open file, opening a closed one and naming a missing one in one notice |
| `StateStoresTest` | Round-trips a tab's time offset and `syncGroup` and a file's timestamp format and zone; reads a malformed offset as 0 |
| `ConfigStoreTest` | Round-trips the timestamp settings and drops a format that does not compile; a save refused by a directory at the file's name leaves saving on, and the next save writes |
| `StoreFaultsTest` | A malformed, oversized and unknown-version session file loads as no windows and is kept as `.invalid` on the next save; a remembered encoding the runtime lacks reads as detection; a refused session save leaves saving on |

## Marks, highlighting and triggers

| Class | Covers |
|---|---|
| `MarkCommentsTest` | Edit Mark Comment on the caret line, an empty comment, unmarking and removing, a refused comment's notice; the marks list following edits, removals and the shown tab, and releasing its charge on close |
| `HighlightControllerTest` | A highlighter file's round trip, its read and write failures, a shutdown dropping an import's result |
| `HighlightFileTest` | Triggers round-trip through export and `qlogg.conf`; a file without them loads; only the malformed action turns off |
| `HighlightCompositionTest` | The rules a document composes: sets last-first, then the match rule; whole word wrapping a quoted or regex pattern; the match color and variance; no match rule past `MAX_PATTERN_CHARS` (including a whole-word wrap that crosses it) or for an inverse or combined search; an automatic set replacing the active sets while its id exists |
| `HighlightComposerTest` | The same cases on `HighlightComposer` with no document, plus labels last in the palette's colors |
| `ColorSchemesTest` | Contrast floors over every listed scheme, Light and Dark included ([Highlighting](docs/highlighting.md#color-schemes)), with each palette's lowest ratio printed under `-Dqlogg.qc.contrast=true`; distinct, reserved bundled ids; the list order, with Light and Dark first holding `Palette.LIGHT` and `Palette.DARK`; `follow`, `light` and `dark` reserved; user schemes after the bundled ones; `Palette.COLOR_NAMES` in component order |
| `ThemeControllerTest` | Every `ui.theme`, system setting and `view.colors` value (Follow theme, Light, Dark, a light and a dark scheme, an unknown id) resolving to its palette while `preferDark` follows the theme alone; a system change with a fixed scheme moving only the native controls; Light and Dark keeping their palette in light and dark chrome; `ui.theme` round trip in portable mode |
| `SchemeStoreTest` | Scheme files folding into the list in name order, ignored other keys and files; each bad file (missing or invalid color or `dark`, reserved (`follow`, `light`, `dark`, a bundled id) or malformed id, over 64 KiB, bad escape, a folder) skipped with one WARN; a name with a control character or over 64 chars; the `view.colors` round trip, including `light`, `dark` and an unknown id, and an invalid value falling back |
| `ColorLabelTest` | Default labels taking the dark variant and the scheme's `labelGray`; user colors kept |
| `TriggerControllerTest` | Stop follow on the shown tab (notice) and a background tab (`FOLLOW_STOPPED` above `NEW_MATCHES`); a templated mark on appended lines only; no firing on the first index, a reload or a truncation reopen; rules of two sets firing together; quiet growth and quiet matches leaving the dot; an append of three batches and more scanned once per line in contiguous batches; one notice for refused comments; growth before a rule acts staying unscanned; a scan that cannot read ending instead of restarting; an empty quiet set after a refusal |

## View features

| Class | Covers |
|---|---|
| `ViewFeaturesTest` | Stop ending every group of a search across files on a catastrophic regex; a reload dropping its group; In selection ignored by that search; grouped search across files and a closed tab's group; the count of cut lines in the saved notice; the fold mapping; expansion by go to line, mark jumps, find next and `showLine`; collapse all and expand all with the `FOLDS` charge returned; copy skipping hidden lines; block copy and block extension; copy with line numbers; wrap with its fit and the block refusal; folds under wrap; save of a selection and of the filtered view, with the refusal of an open file and of an open link's target; selectors by name and first line; a catastrophic first-line regex giving up within its bound; hidden ANSI colors in search; sending text to the scratchpad |
| `OverviewFoldsTest` | Collapsed entries against rows: a match past the row count and one hidden in an entry, a click past the entry and on it, the bins refreshed by a fold change, the spread's rows, a swap of equal entries keeping the row count |
| `TransformsTest` | Each scratchpad transform and the derived values |

## Timestamps

| Class | Covers |
|---|---|
| `TimeControllerTest` | Detection at open with the caret time; a trace line taking the time above it; a selection's difference and the offset applied. Go to time moving the caret, releasing follow and pairing the activity, between two entries and with an offset; the notice before the first stamp; an out-of-order file searched line by line, after the order check or after a probe outside its bracket; a refused order check searching line by line without the out-of-order notice; a format or zone change dropping a running search; a newer request dropping the older result; `Esc` canceling with its notice and a held save, and over held indexing leaving it running until a second `Esc` stops loading; the notice for a target past the indexed part; progress notices of a linear scan held at a gated line. The filtered view's rows' times; a close during a time read or after its post-back; a file without timestamps disabling the commands; a remembered format, none or a user format, skipping detection on the next open; the Go to Time, Time Offset and Time Zone dialogs' results; the offset in the session draft; a reload keeping the offset; the order check's charge returned on close |
| `TimeSpreadTest` | The mode from the menu command to every open document, a new document and the settings; the shaded rows of a generated pause in both modes; no spread without a format or with the bar hidden or removed; samples at once for a height, mode, format and zone, none for an offset; growth held until the 2 s timer; a timer without growth sampling nothing; a refused share and its recovery; the charges returned by Off and close |
| `TimeSyncGroupsTest` | A move reaching a member through both offsets; a go to time and a filtered row of a member propagating; no report for a sync move, a kept or restored position; a kept line or restored position waiting for indexing; a newer request replacing a waiting position; follow, and no sync from a followed member's appended lines; a go to line waiting for indexing reporting its move; moves within the debounce running one sync; a newer move canceling the target's search without a notice; a target's own move ending its sync move; `Esc` on a target; follow released without a position notice; a hidden tab moving; the cascade's join rules and labels; close dissolving a group of one; a reload keeping the membership; session renumbering and restore; Scroll All with its notices |
| `TimeTextTest` | The caret time, differences (sub-second, days, negative, fraction digits), offsets round-tripping and refused, targets with and without a date, the format names and tooltip |

## Single instance

| Class | Covers |
|---|---|
| `HandoffTest` | Client and server in one JVM over loopback: files, lines and `-f` arrive; a launch without files still hands off; a wrong token, a peer without the token and a listener without the token each move no file; a stale endpoint without the lock is replaced, and one behind a live lock, or a missing one past the 2 s wait, fails so the launch opens its own window; 16 concurrent launches all arrive at a running primary, and at one whose first window has not opened, with at most `MAX_CONNECTIONS` held; held files arrive in arrival order; a close or the expired hold refuses every held launch; a peer without the token is not held; a listener that never answers ends in the client bound; close removes the endpoint and releases the lock; the endpoint is `rw-------`; `OPEN` lines need an absolute path; a shared or foreign settings directory turns the handoff off. `aKilledPrimaryReleasesTheLock` starts a child JVM (`ClaimMain`) that claims the lock, kills it with `destroyForcibly`, and checks that the next launch becomes the primary over the stale endpoint |
