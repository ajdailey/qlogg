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
`detach`. `show(index)` picks the set the filtered view shows.

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

- A find compiles the pattern and runs `LineFinder` on a `qlogg-search-` worker
  over the view's `rows()`, holding the `ActivityIndicator` active.
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
| `FilterFile` | Filters as `filter.N.name`, `filter.N.pattern`, `filter.N.regex`; an export adds `version=1`. `read` validates the path through `SafePaths.logFile` and refuses a file over 1 MiB, without `version=1`, with an incomplete entry or with over 1,000 filters (`FormatException`); `write` replaces the file through `AtomicFiles` |
