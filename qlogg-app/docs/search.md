# Search

The search controllers in `app.controller` (`SearchController`,
`QuickFindController`, `MultiSearchController`) and the search text helpers in
`app.search` of [qlogg-app](../README.md). The matching engine is in
`qlogg-core`.

## Document search

`SearchController` (from `DocumentController.search()`) runs one document's
search and holds its field text, toggles and result sets.

### Running a search

`run(text, options)` cancels a running search and starts a new generation with
a new `MutableLineSet` charged to `RESULTS`. Empty text only clears.
`clear()` (Search > Clear Results, enabled while the search state is not
`IDLE`) cancels a running search and empties the current set; kept sets, the
field text and the toggles stay.

- The search covers the lines indexed when it starts, narrowed by the search
  start and end lines and, with `inSelection`, the selected lines.
- A combined expression that does not parse fails at once with its column. A
  regex compiles on the search thread and fails there.
- A search that starts sets the filtered view to marks and matches.
- Each search searches one index snapshot and records its line count and
  indexed bytes.
- Each search holds the `ActivityIndicator` active until its `finished` call or
  `detach()`, whichever comes first, and a document entry until its `finished`
  call.

### Progress reports

The search thread keeps the newest report in an `AtomicReference`, never
replacing a newer generation's, and posts through a `UiUpdateQueue`. A flush
drops a report from an older generation. A replaced search's own `finished`
call empties its result set, so a canceled search that merged one more chunk
still returns its reservation.

### Kept results

With `keepResults` on, a run moves a finished current set into `kept()` instead
of emptying it. A kept set stays charged to `RESULTS` until `removeKept` or
`detach`. `show(index)` picks the set the filtered view shows. `show(set)` and
`removeKept(set)` match a set by identity and ignore one not in `kept()`;
`ResultsPane` calls them with the set its tab holds.

- A search still running is replaced as usual, because its set is still
  growing.
- An auto-refresh running into a finished set is canceled, and the set is kept
  with the appended lines the refresh reached.

### Auto-refresh

With `autoRefresh` (`8`) on, `appended()` searches the lines a finished search
did not cover into the same set and generation, starting at its last line,
which may have grown; that line's result is removed first.

- The refresh shows no progress and holds no activity. The state stays `DONE`
  with the new match count.
- A refresh that ends with more growth starts another.
- A search stopped at a limit, or whose range ends before the end of the file
  (search end line, in selection), is not refreshed.

### Range search

With `range` on (`TOGGLE_RANGE`, no key), the field text starts a range and
`rangeEnd` ends it (`SearchQuery.rangeEnd`). An empty or malformed end fails at
once. The search start and end lines and the selection scope limit the scan as
for any search, and a range open at the scope's end runs to it. An auto-refresh
starts in the state the engine reported before the last searched line, so an
open range extends over appended lines.

`selectRange()` (`Ctrl+R`) selects the range around the main view's caret with
the field text, the range end and the current toggles. It runs `RangeFinder` on
a `qlogg-search-` worker inside a document entry, holding the
`ActivityIndicator`. A newer request or `cancel()` stops an older one. With no
range there, a notice shows and the selection stays.

### Toggles, edits and navigation

- `toggle(command)` flips one toggle for keys `4` to `9` and fires
  `optionsChanged`.
- `edit(ADD | EXCLUDE | REPLACE, selection)` rewrites the field text with
  `SearchEdits` and runs it when `search.runOnAdd` is on.
- `findNext()` and `findPrevious()` move the caret to the nearest match of the
  shown set after or before the caret's line and center it. With none, a notice
  shows and the caret stays. They read the set on the UI thread, whose read
  lock a merge holds for one chunk.

### History and saved toggles

`ApplicationController` implements `DocumentHost`. Each search puts its text
first in `search.history.N`, bounded by `search.historySize`; a range search's
end expression goes in before it. Each search also saves its toggles, less In
selection, as `searchOption.*`, the range toggle, end expression and context
counts included. A malformed value or an end over 4,096 chars loads the
default. A new document in any window starts with the saved toggles.

## Quick find

`QuickFindController` (from `ApplicationController.quickFind()`) walks
occurrences in the focused view without a result set. It matches fixed text by
default (`quickFind.regex`), with ignore case from the bar's box.

- A find compiles the pattern with the search's flags
  (`StandardMatchers.regexFlags`) and runs `LineFinder` on a `qlogg-search-`
  worker over the view's `rows()`, holding the `ActivityIndicator` active.
- A newer find cancels the older one, and a result from an older generation is
  dropped.
- The match becomes the view's selection. With no occurrence left in the
  direction, the notice "Reached end of file" or "Reached start of file" shows
  and the selection stays; there is no wrap.
- With `quickFind.incremental` on, each change to the text searches forward
  again from where the caret was when typing began.
- `*` and `.` (forward), `/` and `,` (backward) find the selected text.

## Search across open files

`MultiSearchController` runs one text and set of toggles as the current search
of every open document, through each document's `SearchController`. Each search
uses its document's encoding and result set and is charged to `RESULTS`.

- `results()` is one `FileResults` group per file in tab order. A group is a
  snapshot of its file at run time.
- Stop cancels every search of the group. A closed or reloaded tab drops its
  group.
- A group stopped by a limit, or with skipped lines, shows the search bar's
  status in its row.
- A run ignores `inSelection` and leaves each tab's toggle as it was. Each
  tab's search start and end limits apply.

## Search in a folder

`FolderSearchController` (Search > Search in Folder) runs a `FolderSearch`
over a folder's files without opening tabs; core detail is in
[qlogg-core Search](../../qlogg-core/docs/search.md#search-in-a-folder).

- `request()` is the last run's `FolderRequest`. Otherwise it takes the
  folder, globs, Include subfolders and exclude globs from `folderSearch.*`
  in `qlogg.conf`, the folder falling back to the shown tab's, and the shown
  tab's search text and toggles.
- `run` saves the folder, globs, Include subfolders and exclude globs to
  `folderSearch.*`.
- `run` stops the running search, which keeps what it listed, and starts a
  `FolderResultSet` as the shown set. An empty folder or text, a glob or
  combined expression that does not parse, or closed workers start no set and
  make `state()` `FAILED` before the search thread; the folder is validated
  on it.
- Mode, case, whole word, inverse and combining apply. Range, In selection,
  context and auto-refresh do not. `view.hideAnsi` applies.
- Progress and the end reach the UI thread through the `UiDispatcher`; events
  of a dropped set are ignored.
- `open(file, hit)` selects the tab whose path or real path is the file and
  goes to the line, else opens the file at that line. Another line of a file
  still opening, within 5 s, moves the new tab there instead of opening the
  file again, so fast steps do not race two opens. A failed open of the file
  (`ApplicationListener.documentOpenFailed`) ends that wait.

### Kept result sets

`sets()` lists the runs newest first; `show(set)` picks the set the window
lists and the step keys walk, and ignores a set not in `sets()` or whose
results a refused run closed.

| Operation | Effect |
|---|---|
| New run | Drops the oldest unpinned sets beyond `KEPT_SETS` (10) |
| `togglePin(set)` | A pinned set does not count toward the 10 and is never dropped for memory |
| `drop(set)` (Clear) | Stops its search, closes its `FolderResults`, shows the newest other set |
| `clear()` (Clear All) | Drops every set; closing the main window calls it |
| `RESULTS` refusal during a run | `FolderListener.resultsRefused` closes the oldest unpinned other set on the run's thread and the reservation is retried; the UI thread then drops that set, with one notice per run |

Every kept set holds its `RESULTS` reservation. Closing the Search in Folder
window stops the running search and keeps the sets. Sets are not saved.

### Step keys

`step(forward)` (Search > Next Folder Match `F4`, Previous Folder Match
`Shift+F4`) opens the next or previous listed line of the shown set across
its files, from the last line opened or stepped to, through `open`. The first
step takes the first or last line; `stepFrom(file)` (a file row chosen in the
window) starts at that file. Passing an end wraps with a notice; a set with no
listed line gives a notice. The keys run from the main window's menu and from
a key filter of the Search in Folder window.

## Predefined filters

`importFilters` and `exportFilters` read or write a user-chosen file on a
`qlogg-lifecycle-` worker (`FilterFile`) and report to a `SettingsFileListener`
on the UI thread. Save as Filter asks for a name (`MainView.askFilterName`),
prefilled with the pattern cut at 64 chars. `saveAsFilter` adds the filter
once, named after the pattern when the name is blank.

## Search text helpers

`app.search` holds pure functions, tested without a document.

| Class | Contract |
|---|---|
| `SearchHistory` | `add` puts a search first, drops an equal earlier entry and cuts the list to the size; `completions(prefix, caseSensitive)` lists the entries that extend a prefix, most recent first |
| `FilterPatterns` | `compose` joins filters as regex alternatives (`a\|b`, a `LITERAL` filter escaped), or as quoted terms joined with `or` with combining; `present` reports which filters stand in a text between separators |
| `SearchEdits` | `add` (`text\|sel`, or `text or "sel"`), `exclude` (`"text" and not "sel"`) and `replace`; the selection is escaped for the mode, and add to a literal search or exclude turn combining on |
| `FilterFile` | Filters as `filter.N.name`, `filter.N.pattern`, `filter.N.regex`; an export adds `version=1`. `read` validates the path through `SafePaths.logFile` and refuses a file over 1 MiB, without `version=1`, with an incomplete entry or with over 1,000 filters (`FormatException`); `read` decodes through `PropertiesFiles` (UTF-8 BOM, UTF-8, ISO-8859-1); `write` replaces the file through `AtomicFiles.writeUserFile` |
