# Windows and tabs

The window and tab controllers in `app.controller` of
[qlogg-app](../README.md): `ApplicationController`, `WindowSet`,
`DocumentOpener`, `CommandStates` and `EncodingChoices`.

## Windows

`ApplicationController` is one main window. It runs window commands and owns
the tabs: one `DocumentController` per open file in tab order (`documents()`)
and the shown one (`activeDocument()`). Each window holds a `DocumentOpener`
and a `HelpController` ([Help, logs and scratchpad](help-and-logs.md)).

`WindowSet` holds the windows and what they share: the settings and their
saves, the theme, the key bindings, the file monitor, the document services,
the recent log lines and the debug log file. The six-argument constructors
create a set with one window; `new ApplicationController(set, view)` adds a
window to a set.

New Window (`WindowSet.newWindow`) opens an empty window through the set's
`Factory`. `windowActivated` records the window the user activated last.

`WindowSet.openHandoff` opens handed files in that window and raises it
(`MainView.toFront`). A handed file that a tab in any window already shows,
compared by the opened document's path, is closed again and that tab is shown
instead, at its `+line:N` and following with `-f`; its window is raised
(`WindowSet.holderOf`). File > Open and the command line check their own window
only.

## Opening files

`DocumentOpener` runs the open, paste, reload and session restore workers and
delivers each opened document to its window on the UI thread. After the window
closes (`detachAll`), a document whose open ends later is closed by its worker
or by `closeUnclaimed`, never delivered.

`open(files, follow)` runs `LogDocument.open` for each file on one
`qlogg-index-` worker, in the order given. Each file opens a tab after the
others and is shown, so the last one ends shown, at its `+line:N`. File > Open
and `openDropped` (files dropped on the window) use the same path;
`openDropped` skips folders on the worker and then shows the notice "Folders
are not opened".

- A file already open shows its tab.
- `LogDocument.path()` is the path as given, with symbolic links kept. It keys
  the tab title, the open-file check, the handoff, the recent list, favorites,
  the session and the `PositionStore`, so a retargeted link reopens under its
  own name.
- Indexing and the `FileMonitor` watch start with the tab. `follow` (`-f`) or
  `follow.onLoad` start it following. Otherwise, without `+line:N`, the
  `PositionStore` entry restores the caret and the top line once indexing
  reaches them.
- The worker opens the file with the entry's forced charset, if any. A
  remembered charset name this runtime does not support logs a WARN naming it,
  and the encoding is detected.
- Each file goes first in the recent list.
- A failure is logged at WARN and shown as a one-line error. The end of the
  first indexing pass and of every search is logged at INFO with its duration
  and counts.

### Clipboard text

`openClipboard` (File > Open from Clipboard, `Ctrl+V` in a log view) reads the
clipboard's text on the UI thread. `LogDocument.ofText` then encodes it as
UTF-8 on a `qlogg-index-` worker, charged to the `PASTED` share and capped at
`MAX_PASTED_BYTES` (16 MiB); a refusal shows a notice. The tab is named
`Clipboard N`. A pasted document has no path: it is never watched, followed or
reloaded, and the path commands show a notice instead.

### Session restore

`restoreSession` opens a `Session`'s tabs on one `qlogg-index-` worker with
their names, follow state, marks and mark comments, then shows its active tab
and makes this instance the session's owner. Marks and comments are added
before indexing starts, as a reload adds them; a comment on an unmarked line is
dropped. Only the owner writes the tab list. The launcher makes an instance the
owner when it restored the session and has no `-m`.

`AlwaysOnTop` holds a window's View > Always on Top flag; `restoreSession`
restores it and the session saves it per window. Where
`MainView.onTopSupported` is false the item is disabled and a restored flag is
kept for the next save without reaching the view. A new window, including one a
tab moves to, starts with it off; handed files do not change it.

## Tabs

`activate`, `moveDocument`, `closeDocument(document)` and `closeAll` change the
tabs. Listeners get `documentOpened`, `documentClosed`,
`activeDocumentChanged`, `documentReplaced` and `tabRenamed`.

- Closing the shown tab shows the one after it, else the one before.
- Closing the last tab, or Close All, leaves an empty window. With
  `tabs.closeWindowWithLastTab` on, the window closes through `closeWindow`, so
  the last window quits. Close All in an empty window does nothing.
- `Ctrl+PageDown` and `Ctrl+PageUp` show the next and previous tab.
- The tab context menu calls `closeOthers`, `closeSide`, `copyPath`,
  `copyFileName`, `openContainingFolder`, `renameTab` and `resetTabName`. A tab
  name is kept per path for the session and saved with it. File > Close
  Multiple Tabs calls `closeOthers` and `closeSide` on the active tab.
- Window > Switch to File (`Ctrl+Shift+O`) calls `MainView.showOpenedFiles`.

Closing a tab detaches its controllers on the UI thread, which cancels the
search, quick find, mark toggle, overview, copy and save. `LogDocument.close`
then runs on a `qlogg-lifecycle-` worker; it waits for every operation holding
a document entry, reads included, before it closes the file.

### Path commands

`copyPath`, `copyFileName` (Edit menu), `openContainingFolder` and
`openInEditor` (File menu) act on the file's path; the tab menu has all but
`openInEditor`. The folder and the file open through
`MainView.launch`, which runs no shell. `openInEditor` first checks on a
`qlogg-lifecycle-` worker whether the system would run the file instead of
opening it: an executable file, or an extension in `WINDOWS_EXECUTABLE` where
`MainView.launchesByExtension()`. It refuses such a file with a notice.

### Moving a tab between windows

`WindowSet.moveDocument` moves a tab to another window at a tab position, or to
a new window through the `Factory` at a display point (Window > Move to New
Window, the tab menu, a tab drag). The `DocumentController` and its
`LogDocument` move as they are; nothing is reopened.

- Each controller reports to its window through a `TabHost` (`DocumentHost`,
  clipboard, notices, activity), which the move points at the target. An
  activity reported before the move ends in the window it started in.
- The source detaches the tab without closing it
  (`ApplicationListener.documentMovedOut`), cancels its quick find and drops it
  from the search across files. The target inserts it (`documentMovedIn`) with
  its Rename Tab name and shows it.
- A window left with no tab stays open.
- The settings and session save once, without the debounce.
- Nothing moves while quitting, during the tab's reload or reopen, or into a
  window that shows the same file (a notice says so).

## Reload and reopen

`reload` (`F5`) and a `TRUNCATED`, `REPLACED` or `MODIFIED` file
(`reopenChanged`) reopen the path on a `qlogg-index-` worker. The new
controller takes the tab, the follow state and the search text; results start
empty.

| Trigger | Marks, comments, labels | Search |
|---|---|---|
| `F5` | Kept: the worker copies them into the new document, and its first completed indexing drops marks and comments past the last line. The caret's line is kept | Not rerun |
| Change on disk | Emptied, with a notice | With `autoRefresh` on, a search that was not canceled runs again with the text and toggles it ran with; the notice says so instead of results cleared. The field and toggles stay as they were |

### Failed reopen

A reopen that fails keeps the tab and its content and calls
`DocumentController.reopenFailed`, which resumes the document's checks. The
next check that reports a change reopens again, so retries run at the check
interval and following resumes when the file opens.

- While no file is at the path, the check reports `MISSING` once and no reopen
  runs.
- A run of failures shows one notice and logs one WARN; each failed `F5` shows
  its own notice. The status note reads Waiting to reopen in place of Following
  until the run ends.
- `Esc` leaves the retries running, as it leaves a first reopen.
- Closing the tab ends them: the watch stops, and `reopened` or `reopenFailed`
  drops the result of a worker still running.
- A change reported during a reload is dropped. If that reload fails, the next
  check reports the change again.

### Encoding menu

The Encoding menu calls `setEncoding(document, charset)`. The choice goes into
the file's `PositionStore` entry (null for Auto) and the tab reopens as a
reload does, indexed again with the forced charset.

`EncodingChoices.available()` computes the menu's lists once: the supported
entries of `COMMON_NAMES` in order, then every other charset of
`Charset.availableCharsets()`. That call takes 130 to 230 ms cold, so it runs
on a worker: `ApplicationController.loadEncodingChoices` delivers the lists on
the UI thread, at once when computed, else from a `qlogg-lifecycle-` worker.
`commonCharsets()` looks up the common names only. `forced(document)` is the
charset the menu checks: the forced one of a document with a file, else empty
for Auto.

## Command dispatch

`execute(command)` runs File, Help, Go to Line, the search field, quick find,
the split and the history and filter dialogs itself. Search toggles, filtered
view modes, edits with selection and search limits go to the shown document's
`SearchController` and `FilteredController`. Every other command goes to the
focused view. A canvas reports focus through `setFocusedView` on focus, click
and key; without one, the main view is focused.

- `FIND_NEXT` and `FIND_PREVIOUS` (`F3`, `N`, `Ctrl+G` and the shifted keys)
  walk quick find while its bar is open, otherwise the search's matches.
- `Space` (`RELEASE_FOCUS`) moves focus from the main view to the filtered view
  and back (`MainView.focusView`).
- Go Back and Go Forward move the shown document's main view whichever view has
  focus.

### Command palette

View > Command Palette (`Ctrl+Shift+P`) calls `MainView.showCommandPalette` over
`commandPalette()`. `CommandPalette.filter` lists every menu command but itself
with its menu path (`Search > Search Options`) and `CommandStates.enabled`. A
query matches when its non-blank chars appear in order in the title, ignoring
case, else in the menu path and title; a char scores 1, 3 more right after the
previous match and 5 more at a word start, and rows sort by score, then menu
order. `run` passes an enabled command to `execute` and ignores a disabled one.

### Menu state

`CommandStates` maps each `CommandId` to its menu item's checked and enabled
state, read from an `ApplicationController` on every call, on the UI thread.

| Commands | Checked from | Enabled when |
|---|---|---|
| Checks in `APPLICATION_CHECKS` | Settings, theme, block mode, debug log file or the window's Always on Top flag | Always; Always on Top only while `MainView.onTopSupported` |
| Other checks | The shown document | A document shows |
| `FILE_COMMANDS` (Reload, the path commands, Open in Editor, Encoding > Auto) | - | The shown document has a file (not clipboard text) |
| Move to New Window | - | A document shows |
| Close Others, Close to the Left, Close to the Right | - | The active tab has a tab on that side (`tabItems`) |
| Add to Favorites, Remove from Favorites | - | The file is not a favorite, or is one |
| Go Back, Go Forward | - | The shown document's history has an entry in that direction |
| Go to Time, Scroll All Tabs to This Time, Time Offset, Time Zone | - | The shown document has a timestamp format |
| Recent Files > Open All, Clear Recent Files; Recently Closed > Open All, Clear Recently Closed | - | The list is not empty |
| Reopen Closed Tab | - | Recently Closed is not empty |

`CommandStates` also lists the highlighter sets (`highlightSets`), the open
files (`openedFiles`) and a tab menu's enabled items (`tabItems`). An ArchUnit
rule keeps the views from reading the getters only this mapping needs.

## Options and remembered state

`setOptions` (Tools > Preferences) applies:

| Option | Applied through |
|---|---|
| Key bindings | Listeners get `keymapChanged`; `keymap()` is the defaults with the overrides |
| Font | `fontChanged` |
| Monitor settings | `FileMonitor.setSettings` |
| Default encoding | Files opened afterwards |
| Recent-files count | `optionsChanged`, as for any option but the key bindings and the font |

`zoomFont` and View > Zoom In, Zoom Out (`Ctrl`+wheel in a view) step the drawn
size by one point within 4 to 72, save it as `font.zoom` and fire
`fontChanged`. Reset Zoom clears it, back to the Preferences size.

Favorites add and remove the shown file's path. `openListed` opens a recent or
favorite path. File > Recent Files > Clean Up checks the paths on a
`qlogg-lifecycle-` worker and drops those that are not regular files; Clear
Recent Files (`RecentFiles.clearRecent`) empties the list on the UI thread.

`RecentFiles` keeps File > Recently Closed in `UiState.closedFiles`, at most 20,
newest first. Closing a tab lists its file; closing a window or quitting does
not. Opening the file in any tab drops it. A reopened file opens through the
normal path, at its remembered position unless Follow New Files is on. Open All
on either list checks the paths on a `qlogg-lifecycle-` worker, opens the
regular, readable ones in list order (a file open already shows its tab) and
names the others in one notice. File > Reopen Closed Tab (`Ctrl+Shift+T`) takes
the newest entry off the list, then opens it through `openListed`, so a missing
file leaves the list too.

`UiState` receives the search toggles, line number toggles, the Open dialog's
directory and dialog sizes (`rememberDialogSize`). When a window closes, and at
quit for the window activated last, `rememberWindow` records the normal bounds,
maximized state and split.

### Settings saves

`setPreferences` replaces the settings. Every change of the settings, the tabs,
a tab's follow state or marks (`DocumentHost.documentStateChanged`) restarts a
`SAVE_DELAY_MILLIS` (1 s) timer. When it fires, `flushSettings`:

1. records every window's tab positions in the `PositionStore`;
2. captures the settings and each window's tab list on the UI thread;
3. on a `qlogg-lifecycle-` worker, writes `qlogg.conf` (when changed),
   `positions.conf` (when changed) and `qlogg_session.conf` (`window.N` per
   window), reading each tab's marks and mark comments inside
   `LogDocument.enter`.

Each capture takes a sequence number, and a writer skips a part a later capture
has written.

## Closing a window and quitting

`closeWindow()` (the window's close button) remembers the window's placement,
detaches its tabs, hides it, saves the session without it, closes its documents
on a `qlogg-lifecycle-` worker and posts its disposal. The last open window
quits instead. An open, paste, reload or session restore still running for the
window stops before its next file; the worker closes a document it opened after
the close began.

`shutdown()` (File > Exit) runs `WindowSet.quit`. A second call does nothing.

1. On the UI thread: record the placement of the window activated last, capture
   a save, detach every window's tabs and hide the windows.
2. On a `qlogg-lifecycle-` worker: run the quit listeners (the handoff listener
   closes) and write the save.
3. Close every document at once (`LogDocument.closeAll`, one worker each, 5 s
   overall).
4. Close `Workers` (5 s, naming survivors in a WARN), then any document an open
   finished after quit began.
5. Run the finish listeners (the shutdown hook waits on one) and post the
   windows' disposal, which ends the event loop.
