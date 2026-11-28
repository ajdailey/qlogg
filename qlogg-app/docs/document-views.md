# Document views

The controllers of one document's two views in `app.controller` of
[qlogg-app](../README.md): `LineViewController`, `DocumentController` (the main
view), `FilteredController`, `FoldController`, the column classes
(`ColumnController`, `ColumnFeatures`, `ColumnRows`, `RecordScanner`,
`CellCommands`, `RecordText`), the
filter tab classes (`DerivedTabs`, `Derivation`, `DerivedRows`, `RanSearch`,
`RowRestriction`, `SessionFilterTabs`),
`TextExporter`, `MarksListController` and `TabDataModel`.

## Line view controller

`LineViewController` is the abstract parent of the two views. It holds the
`Viewport`, the `Selection` in rows and the visible `LineBlock`, and runs the
navigation, copy, mark and selection-range commands both views share.
Subclasses map rows to lines (`sourceLine`, `rowOf`, `rows()`).

### Row reads and highlighting

The visible rows (rows plus one, columns plus a 16-column margin) are mapped to
lines on the UI thread and decoded on a `qlogg-read-` worker. With text wrap
on, the read also takes the screen rows above the top (`firstRowMeasured`),
because the view's fit at the end counts up past the top and a line outside
the block counts one row. At most one read is in flight; a viewport change
during a read starts one more when it returns. The view paints whatever block
is present.

The read that decodes the rows also colors them with the view's `Highlighter`
(`qlogg-core`), from each row's whole decoded line, and, with syntax colors on
(`setSyntaxColors`), tokenizes them with a `LogTokens.LineMatcher`.
Highlighting therefore costs nothing on the UI thread and covers only the rows
read. The block's spans and tokens are charged to `HIGHLIGHT` while it is
shown; a block whose spans the share refuses shows uncolored. `marked(line)` and `matched(line)` give the
gutter's bullets with one set lookup per row.

### Navigation and selection

- `End` scrolls so the longest line in the decoded block ends at the right
  edge; `$` does the same for the caret's row. `Home`, `^` and `0` scroll to
  the first column.
- Set Selection Start records the caret's row; Set Selection End selects whole
  rows from it to the caret's row.
- `selectWordAt` (double click) selects the letters, digits and underscores
  around a position, or around the char before it, within the decoded column
  window. A row with a char outside printable ASCII selects nothing, because
  the view's hit testing follows the grid.
- `selectedText` reads the selection of one row on a worker, cut at 4,096
  chars, for edits with selection and quick find. An empty or multi-row
  selection shows a notice instead.
- `M` toggles the marks of the selected rows' lines as one through
  `DocumentController.toggleMarks`, for any number of rows.

### Block selection and wrap

`selectBlock` holds a block selection: the display columns between anchor and
caret on every row between them. Copy takes each row's part through
`LineReader.appendColumns` and joins rows with LF. Any stream selection, caret
move or `select` ends block mode.

With text wrap on (`setWrap`, both views of a document at once):

- the first column stays 0, and a read takes up to 65,536 columns per line;
- the viewport's rows follow the lines the view reports as fitting whole
  (`wrapFitted`), so the last lines can scroll into view, and a following view
  re-pins to the end when they change;
- a block selection is refused with a notice;
- a collapsed entry is still one line, wrapped.

With text wrap off, `toggleLineWrap` (Wrap Line, `W`, or the view's line
marker) wraps one line in place per view, keyed by its source line; wrapping
another line unwraps the first. Turning text wrap on unwraps it and disables
the command, each `SearchController.run` unwraps the filtered view's, and a
reload starts without one. While the line is in the visible rows or the screen
rows above the top, the read also decodes it alone from column 0 into
`wrappedLines`, up to the view's columns times its screen rows, and the
viewport's rows follow `wrapFitted` as with text wrap on. The other rows keep their column window.

## Copy and save

Copy, save and `selectedText` run `TextExporter` on a worker. `TextExporter` is
stateless and builds the text from the rows' source lines, with the platform
line separator between them (LF within a block). An empty selection, and Copy
This Line, copy the caret's row. Copy with line numbers puts each source line's
number and a tab before it.

In column view a request carries a `RecordText.Columns` and `RecordText`
builds each whole row from its record:

| Form | Text of a row |
|---|---|
| `COLUMNS` (Copy, save by default) | The shown cells' full values joined by tabs; a tab, LF or backslash inside a value is written `\t`, `\n` or `\\`; a record that did not parse is one value |
| `LINES` (Copy Raw Lines, `saveLines(file, true)`) | Every line of the record as in the file |
| `CELL` (Copy Cell) | One cell's full value, unescaped |

With line numbers, the record's first line number comes first. Save takes the
form through `saveLines(Path, boolean)` and `saveAll(Path, boolean)`; the save
dialog's choice is not built yet.

### Charges

| Operation | `COPY` charge per selected file byte | Released |
|---|---|---|
| Copy, `selectedText` | 4 bytes (builder and string, up to 2 bytes per char each) | On the UI thread once the text is on the clipboard, refused or canceled |
| Save | 7 bytes (the text plus its UTF-8 bytes) | Once the file is written, refused or canceled |

A column-view copy is charged on `RecordText.bytes`: each row's record bytes,
twice over for `COLUMNS` and `CELL` (escapes, the Time cell), plus 32 bytes
per shown cell; text that outgrows that size is refused as too large.

A selection the share cannot hold is refused with a notice naming both sizes.
The builder is sized once, because a doubling builder peaks above the charge.
Copy holds the `ActivityIndicator` active until it ends.

### Cancel and failure

- One copy or save runs per view; another shows a notice.
- `TextExporter` checks a `CancellationToken` before every row and before
  `AtomicFiles.writeUserFile`. `Esc` (`DocumentController.stop`) cancels the running
  copies of both views, and `detachView` cancels them on tab close, so the
  worker leaves its document entry within one row and `LogDocument.close`
  releases the line index.
- A canceled save writes no file.
- A file shorter than the index snapshot after the rows are read fails the copy
  or save: a read past the end returns less text, not an error.

### Saving to a file

Save Selection writes the selected lines, or the block's columns; Save Filtered
Results writes every filtered row. Both write UTF-8 through
`AtomicFiles.writeUserFile` on a `qlogg-read-` worker, so a new file takes the
default mode and a replaced one keeps its group and mode. The rows are decoded
4,096 at a time and the text encoded on the CPU pool (`CpuSteps`), as a copy's
rows are. A line over
`LineReader.MAX_LINE_BYTES` is saved cut, and the saved notice counts those
lines; a block save does not count them.

The chosen path passes `SafePaths.logFile` on a `qlogg-lifecycle-` worker. A
path whose real path is the `realPath()` of a document open in any window is
refused, so QLogg never writes a file it shows.

## Main view

`DocumentController` is the main view, where row n is line n unless entries are
folded, or, in a [filter tab](#filter-tabs), the n-th line of a set. It owns the
document's `SearchController` and `FilteredController`.

### Index progress

Index progress arrives on the indexing thread, which only posts a flush. A
flush runs at most every 50 ms and reads the latest snapshot. Indexing holds
the `ActivityIndicator` active until it ends, released from the indexing
thread, or from `detach` when the document closes before indexing ends.

### Go to line

`goToLine(n)` waits until a flush shows line n, then centers it; a line past
the end goes to the last line. `goToLine` and `restorePosition` decide on the
line count of the last flush, not the live index, because the caret clamps to
the viewport, which lags the index until the next flush. `showLine` moves the
caret to a line and centers it only when it is off screen.

### Navigation history

Go > Go Back and Go Forward walk the document's `NavigationHistory`: the
main view's caret lines that jumps left, at most 100, oldest dropped first. A
jump records the caret's line unless it is the newest entry. A jump after Go
Back drops the entries ahead. Go Back from the newest end records the caret's
line first, so Go Forward returns to it.

| Jump | Records through |
|---|---|
| Go to Line, `+line:N`, Find Next and Previous over search matches | `jumpToLine` |
| Next and previous mark, a Marks List row, Go to Time (not a time sync move) | `recordJump` before `showLine` |
| Go to Top and Bottom, a quick find match | `LineViewController.jump` |
| A click or `Enter` (`GO_TO_RESULT`) on a filtered-view row | `FilteredController.rowChosen` |

Scrolling and caret moves are not jumps. The filtered view moves the main view
on every caret move; the first such move keeps the main view's line as the
departure, the next click or `Enter` records it, and a main-view move of its
own drops it. While typing in quick find with incremental find on, only the
first match is a jump.

Entries are lines, so appends leave them in place. A reload or reopen copies
them to the new controller (`takeOver`); a rotated set's roll-over or toggle
then moves them through its line map (`map`), dropping entries on a dropped
member. Once indexing ends, `clamp` moves entries past the last line onto it
and merges neighbors that became equal.

Go Back and Go Forward are enabled while their direction has an entry
(`canGoBack`, `canGoForward`). A disabled item's accelerator does not fire, so
`DocumentController` fires `navigationChanged` whenever a jump, Go Back, Go
Forward, `takeHistory` or `clamp` changes either answer, and the window syncs
its menu's enablement then.

### Marks

- `]` and `[` go to the next and previous mark among the rows; with none, a
  notice shows.
- `toggleMarks` runs `MarkToggle` from a `qlogg-search-` worker, one batch of
  4,096 rows per CPU step (`CpuSteps`), with the `ActivityIndicator` active.
  One toggle runs at a time (a second `M` shows a notice), with progress
  notices at most every 250 ms. A toggle over more than 4,096 rows reports the
  count when it ends. Unmarking removes the lines' comments.
- Marking reserves from `RESULTS` and stops with a notice at the share's limit.
- Edit Mark Comment (`Shift+M`) asks for the comment of the focused view's
  caret line (`MainView.askMarkComment`). `setMarkComment` then marks the line
  when it is unmarked and sets the comment; an empty comment keeps the mark
  without one. A mark or comment the `RESULTS` share refuses, or a comment past
  1,000 per document, shows a notice.
- `removeMarks(lines)` removes marks and their comments.

### Stop

`Esc` (`STOP`) cancels a mark toggle and keeps what changed. `STOP` also stops
loading: indexing ends and growth stays unindexed until a reload. While a go to
time runs, `STOP` cancels it and leaves loading for the next press.

### Follow

Follow (`F`, `F10`, `setFollowing`) scrolls to the last line at each flush and
each viewport resize.

- A user move that leaves the bottom (scroll, page, caret, go to line, scroll
  bar) releases follow.
- With `follow.onOverscroll` (read through `DocumentHost.followOnOverscroll`),
  scrolling down 5 rows past the bottom engages it.
- Growth the document indexes needs no action beyond the flush, which also
  calls `SearchController.appended`.

### File changes

| Monitor state | Effect |
|---|---|
| `MISSING` | A notice and the status note; the content stays. With `follow.keepClosed` the notice says the file cannot be read until it returns. The notice is left out while a failed reopen waits, because that run showed one |
| `TRUNCATED`, `REPLACED`, `MODIFIED`, `ROLLED` | `DocumentHost.reopenChanged` ([Reload and reopen](windows-and-tabs.md#reload-and-reopen)) |

`DocumentOpener` reads `follow.keepClosed` at each open, so a change applies to
files opened or reloaded afterwards. An indexing pass ended by
`FileChangedException` shows no failure, because the check that follows reloads
the tab.

### Detach and close

`detach()` drops every later result and detaches the search and the filtered
view. `close()` also closes the document on a worker, in a file tab only.

### Highlighter and overview

`refreshHighlights` builds one `Highlighter` for both views from the rules
`HighlightComposer` returns. It runs when a search starts or clears, the
settings change, the palette changes or a label changes. The color labels
(`LabelState`) are charged to `HIGHLIGHT`; a state the share refuses is not
applied.

`overview()` is the `OverviewController`: bins of matches and marks per pixel
row, computed on a `qlogg-search-` worker from `OverviewBins` while the bar
shows. One computation runs at a time, the newest generation is kept, and the
bins are charged to `HIGHLIGHT`.

- The bar spreads the main view's rows, so a collapsed entry takes one row's
  share and its hidden lines count in that row's pixel. `refresh` maps each
  pixel row through `sourceLine` on the UI thread, a binary search of the
  `FoldMap` that reads no file.
- `jumpTo(y)` shows the line of the row under the pointer.
- A fold change refreshes the bar.
- It stops computing when the bar is removed with its tab (`barRemoved`) and
  starts again at the next `setGeometry`.
- It also holds the time spread ([Timestamps](timestamps.md#time-spread)).

## Folding

In a file tab `DocumentController` maps rows to lines through a `FoldMap`, so a
collapsed entry is one row. `FoldController` finds entry bounds with
`FoldClassifier` on a `qlogg-read-` worker, one classification at a time and
only around the visible rows.

| Command | Effect |
|---|---|
| Toggle Fold | Classifies around the caret's line and collapses its entry, or expands a collapsed one |
| Fold All | Collapses each entry as the region around the visible rows is classified; an entry the user expands stays expanded until the next Fold All |
| Unfold All | Expands every entry |
| New rules (Tools > Preferences, Folding, through `setViewOptions`) | Reach the open files of every window and expand their folds |

- A fold change keeps the top line and the selection on their lines.
- Go to line, find next and previous, mark jumps, filtered-view clicks and
  quick find expand the entry that hides their line (`revealRow`). An overview
  click goes to a row and expands nothing.
- Copy, mark toggles and save walk `rows()`, so lines hidden in a fold are
  skipped. The filtered view lists lines regardless of folds.
- A Toggle Fold pressed while a classification runs waits for it and then runs.
  A later press replaces a waiting one; Fold All, Unfold All or new rules
  drop it.

## Columns

`ColumnController` (`DocumentController.columns()`) holds a tab's column
format (`ColumnSchema`, null for none), whether column view shows and the
cells (`ColumnLayout`, `ColumnLayout.of` for a new format; `setLayout`
re-reads the rows). The column view's painting, header and menus are not
built yet; the state drives the rows, copies and searches below.

`ColumnFeatures.configure` sets a new tab's state after its time state:

| Remembered for the file (`positions.conf`) | Format | View |
|---|---|---|
| `.columns` a format id | That format, its schema built from `LogDocument.head()` | Column |
| `.columns` `none` | The detected format (`LogDocument.columns()`) | Text |
| Nothing | The detected format | Column when `view.columns.auto` is on |

- `.header` (`1` or `0`) overrides the CSV and TSV header guess.
- A tab whose format is `clef` or `log4j-xml`, with no timestamp format chosen
  for the file, takes the built-in timestamp format of the same name.
- Toggle Columns (`TOGGLE_COLUMNS`, no key and no menu item; enabled while the
  tab has a format) switches between text and column view and records the
  format id or `none` for the file. `ApplicationController.setColumnHeader`
  rebuilds the schema and records `.header`. Pasted text records nothing.
- A `time` format follows the tab's timestamp format; without one the tab has
  no column format.

### Records

A file tab with a multi-line format (CSV, TSV, `log4j-xml`) runs a
`RecordScanner`, in text view too, so a toggle and the filter tabs below it
find the records read:

- A pass runs after each indexing pass, on a `qlogg-index-` worker inside a
  document entry, through `LogDocument.scanRecords`, and extends the scan over
  growth. A `ROLLED` set or a reload opens a new controller, which scans from
  the start.
- While a pass runs, every 250 ms (`PROGRESS_MILLIS`) the rows flush and the
  notice `Reading records: N%` shows.
- A `RESULTS` refusal stops the scan for good with one notice; lines from the
  refused one on stay one row each.
- In a rotated set whose format has a header, a member's first line equal to
  line 0 is a repeated header: searches in column view skip it, as they skip
  line 0.
- `detach` cancels the pass and empties the record set, on the pass's thread
  when one runs.

In column view on a multi-line format, `ColumnRows` maps a file tab's rows
through `RecordRows`: a row is a record, shown at its first line, and lines
past the scanned end are one row each. Each flush takes the newest snapshot
through `changeRows`, which keeps the top line and the selection on their
lines, as a fold change does. Folding is off while records show; the fold
rules and collapsed entries stay for text view. A filter tab keeps its
`DerivedRows` and reads its parent's records for its searches.

### Rows and commands

In column view `ColumnController.read()` returns the `ColumnRead` both views
pass to a `CellReader` in place of the text read: each row is the record
holding its line, composed into cells (core
[Column-view rows](../../qlogg-core/docs/columns.md#column-view-rows)). A
filter tab row on a continuation line therefore shows its record. Text wrap,
a wrapped line and whitespace markers do not apply. The instance stays the
same until the layout, the time settings, the records snapshot or the
repeated headers change, so the reader's row cache survives vertical
scrolling; a change of the time offset or zone re-reads the rows.

| Command | Column view | Text view |
|---|---|---|
| Copy, Copy with Line Numbers, `selectionText` | The selected rows' shown cells, tab-separated ([Copy and save](#copy-and-save)) | Lines |
| Copy Raw Lines (`COPY_RAW_LINES`) | Every line of each selected row's record | Disabled |
| Copy Cell (`COPY_CELL`) | The full value of the caret's cell | Disabled |
| Show Record (`SHOW_RECORD`) | `Name: value` per column of the caret's record, hidden ones included, in a text window (`DocumentHost.showText`) | Disabled |
| `selectedText` | The full value of the cell at the selection start | The selected chars |

The current cell is the cell holding the caret's composed column
(`ColumnLayout.cellAt`). The three new commands have no key and no menu item
yet. Show Record and the cell read run in `CellCommands` on a `qlogg-read-`
worker inside a document entry, with the busy cursor.

## Filter tabs

A filter tab is a `DocumentController` over its root tab's `LogDocument` whose
rows are the root lines one search matched. `ApplicationController.filterToTab`
(Search > Filter to Tab, a results tab's context menu) makes one from a tab's
shown result set when its search ran to its end (`SearchController.shownRan`,
a `RanSearch`), else a notice shows. A `qlogg-search-` worker
copies the set (`MutableLineSet.copy`, charged to `RESULTS`), so the parent's
next search leaves the tab as it is; a refused copy shows a notice. A filter
tab of a filter tab makes a chain, every level in root lines.

`DocumentController` takes a `RowMap` and a `DocumentRole` at construction;
callers never check the tab kind.

| | File tab | Filter tab |
|---|---|---|
| `RowMap` | `ColumnRows`: `FoldRows`, where row n is line n unless folded, or one row per record ([Columns](#columns)) | `DerivedRows`: row n is `select(n)`; a line's row is `rank(line)`, the next row for a line that is not one |
| `DocumentRole` | `OwnedDocument`: indexes, reopens on a change, closes | `Derivation`: shares the document and grows the rows |
| Folding, keyword triggers | On | Off; the fold commands are disabled |
| Its search tests | Every line | Its rows below `settledLines` (`RowRestriction`); a record search, the records holding them |
| Title | File name, or a rotated set's | Root title and search: `app.log: ERROR` |
| Tooltip after the path | A rotated set's files | The searches from the root down, then `Does not grow` when a limit or a scope ended growth |
| Status bar note, tab data dot | A rotated set's note; growth in lines | `N rows`; growth in rows |
| Position, Recently Closed | Recorded | Left out (`ownsDocument()` is false) |
| Session | Path, marks | Parent and search ([Sessions](#filter-tab-sessions)) |

### Growth

`Derivation` searches the root lines from the rows' last searched line, which
may have grown, or from that line's record in the parent's record view, to the
parent's `settledLines`, restricted to the parent's rows, with the parent's
columns. It runs whenever the parent's `settledBytes` passes the rows': a first
level on the document's flush, a deeper level when its parent's growth ends.
Growth ignores the auto-refresh toggle and shows no progress.

- A growth removes the rows from its first line before it searches, so it
  waits while a reader holds the rows: the tab's own search or a child's
  growth. A reader that starts during a growth reads below its first line.
- A growth stopped by the result or memory limit ends growth with a notice. A
  search scoped to end before the end of the file ends growth at that line.

A record search in a filter tab (column view on a multi-line format) tests the
records holding its rows: `RowRestriction` maps the rows to their records'
first lines through `RecordStarts`, a lazy `LineSet` over the rows and the
records, so a tab of continuation lines from a text-view search finds records.
A hit is the record's first line, which may not be a row; the filtered view
shows it and the main view goes to the record's first row.

### Chains and closing

`DerivedTabs` (one per `WindowSet`) holds each filter tab's `Derivation`, whose
parent is a controller in any window.

- A new filter tab opens right of its parent, after the parent's filter tabs
  in that window (`WindowTabs`, `documentOpened` with the index).
- Closing a file tab closes its filter tabs at every depth in every window.
  Closing a filter tab moves its children under its parent. Closing a window
  does both for its tabs.
- A reload or a change on disk that replaces the root tab derives every level
  again over the new document, parents first, each with empty rows its growth
  fills. The root's notice is the only one. `F5` in a filter tab reloads its
  root.
- Marks are the root document's. `]`, `[` and the marks list skip marks that
  are not rows (`MarkSteps`, alternating `ceiling` or `floor` on the marks and
  the rows).
- Locate Source Line (`L`, `Ctrl+E Ctrl+L`, Go menu, log view context menu)
  shows the focused view's caret line in the immediate parent: it shows the
  parent's tab, raises its window, and records a jump in the parent's
  navigation history. In a file tab it is disabled.
- Go to Time searches the root lines and goes to the last row at or before
  the line found, else the first row. Time Spread samples at row boundaries;
  activity counts rows.

### Filter tab sessions

`SessionFilterTabs` saves each filter tab as its parent's `W.N` and its search
(`window.W.tab.N.derived.*`, [settings](settings.md)), and leaves out a filter
tab whose parent is not saved. On restore it derives each tab once the
window's file tabs are open, matched to their entries by path, with empty
rows its growth fills, a level after its parent, in the tab's own window. A
filter tab whose parent did not open is dropped; one whose search does not
parse is dropped with a notice. `derived.column` is stored and restored only.

## Filtered view

`FilteredController` is the filtered view over `FilteredView` (`qlogg-core`):
the shown result set's lines, the marks, or both. Its rows change when the
search flushes, the marks change or the mode changes. A mode change keeps the
caret on its line or the next listed one. Moving the caret to a row shows its
line in the main view; a click or `Enter` makes that a jump of the
[navigation history](#navigation-history). Its line numbers are the source
lines' and toggle apart from the main view's.

In marks and matches mode, the marks outside the matches come from a snapshot
built by a `qlogg-search-` worker as one CPU step (`MarkSnapshots.build`),
at most one at a time and not while a mark toggle runs. Until it arrives the
rows keep the previous snapshot, so the marks are never walked on the UI
thread. In a filter tab the view lists only the marks among the tab's rows
(the view's domain), in marks mode through the same snapshot, rebuilt when the
rows grow.

### Context lines

Context counts come from the shown set's toggles
(`SearchController.shownOptions`: the current search's as it ran, or a kept
set's), so a kept set keeps its counts.

- After the matches change, `ContextRows.extend` runs from a `qlogg-search-`
  worker as one CPU step before the marks snapshot, one build at a time. The
  rows stay on the installed build until the next arrives.
- `grew(lineCount)` re-reads the rows when the line count changes, so the last
  result's context extends with the file without a search.
- `contextRow` and `startsGroup` feed the row shading and the group rule.
  Navigation, counts, the overview bar and the gutter bullets read the matches
  only.

## Marks list

`MarksListController` backs Go > Marks List: the shown tab's first 10,000
marks among its rows, each as line, comment and the line's first 160 chars. It
reads them on a `qlogg-read-` worker under `LogDocument.enter`, one read at a
time.

- It follows `activeDocumentChanged`, `documentReplaced` and `documentClosed`,
  and reads again when a content event finds the marks' or comments'
  `version()` changed.
- Each row reserves 96 bytes plus 2 per char from `RESULTS` while listed; rows
  past a refusal are left out.
- Listing another document, or `close`, cancels a read before its next row.
  `close` releases the rows and removes its listeners.

## Tab data dot

`TabDataModel` (`ApplicationController.tabData()`) keeps a `TabDataState` per
tab of one window. It listens to each document's `DocumentViewListener` and
`SearchViewListener` from `ApplicationController.start`, before indexing
begins, and to the `ApplicationListener` events. A close or move out detaches
the listeners.

| State | Set on a tab that is not shown when |
|---|---|
| `NEW_LINES` | Its line count grows after the first indexing pass ends; a reload or reopen replacement indexed in the background with at least one line |
| `NEW_MATCHES` | Its search's match count grows from `DONE` to `DONE` within one generation, which only the auto-refresh of appended lines does |
| `FOLLOW_STOPPED` | A keyword trigger turned follow off |

- A new search run never sets a state.
- A state replaces only a lower-ranked one (`outranks`).
- Showing the tab sets `NONE`. A reload or reopen keeps the tab's state.

While a quiet rule acts on a document (`TriggerController.defersDot`), its
growth waits for the trigger scan. `NEW_LINES` needs a scanned line no quiet
rule matched. `NEW_MATCHES` needs a new match on such a line, checked once the
scan covers the last new match. At most 10,000 new matches are checked per
growth; past that the growth counts as matching.

`TabTips.text` builds a document tab's tooltip from its path, state and sync
group. `ResultTabs` builds the filtered view's result-set tab text, tool tips
and accessible name; `FolderSearchText` and `MultiSearchText` build the Search
in Folder and Search All Open Files text.
