# Timestamps

The timestamp controllers in `app.controller` of [qlogg-app](../README.md):
`TimeController`, `TimeFeatures`, `CaretTimes`, `TimeSyncGroups` and the time
spread in `OverviewController`. Format detection and lookups are in
`qlogg-core`.

## Time controller

`TimeController` (`DocumentController.time()`) holds a document's format, the
zone of local times, the display zone (`time.displayZone`) and the tab's offset
in nanoseconds. A null format disables Go to Time, Time Offset and Time Zone.

| Change | Effect |
|---|---|
| Format or zone | Both views read their times again; a running Go to Time is canceled |
| Offset | Both views reformat their times; a session save is scheduled |

The offset is the session's `window.W.tab.N.timeOffset`. A reload, a reopen and
a tab move keep it, as they keep the follow state.

### Format and zone choice

`TimeFeatures` gives a new document:

- the format chosen for its file (`PositionStore` `.timeFormat`; `none` or an
  unknown id is no format), else the one `LogDocument.open` detected;
- the zone chosen for the file (`.zone`), else `time.zone`.

`DocumentOpener` skips the detection for a file with a remembered
`.timeFormat`. View > Timestamps > Format (`setTimeFormat`) and Time Zone apply
at once and record the choice for the file; pasted text records nothing.

Edit Formats replaces the user formats (`setUserTimeFormats`, saved as
`time.format.N.*`). An open document's user format takes the edited format of
its name, or none when it was removed. The editor's preview line is the caret
line's first 256 chars, read on a `qlogg-read-` worker before the editor opens.

### Caret times

Each view's `CaretTimes` reads the caret line's time and, for a selection over
more than one row, the times of its first and last rows' lines, through
`TimeReader.at` on a `qlogg-read-` worker. One read runs at a time with one
more queued. A read is skipped when the lines and the format are those of the
last read. The filtered view reads its rows' source lines.

## Go to Time

`goToTime(shown)` subtracts the offset and runs on a `qlogg-search-` worker
inside `LogDocument.enter`, holding the `ActivityIndicator` until its result
posts. It searches the index snapshot taken at the start.

- The order check (256 `TimeSamples`, 2,112 bytes charged to `HIGHLIGHT`) is
  kept while the index generation and the format stand, and returned on
  `detach`.
- A descent or an inconsistent `TimeSearch.locate` runs `locateLinear`, with
  progress notices at most every 250 ms and the out-of-order notice.
- A refused charge leaves the order unknown and runs `locateLinear` without
  that notice.
- A newer request, `Esc`, `detach` and a format or zone change cancel the
  running search. A result whose generation is not the newest is dropped.
- The found line turns follow off and shows through `showLine`. The filtered
  view's caret moves to that line's row or the next listed one.

Notices: no format, no stamped line, target before the first stamp, target
after the last stamp while indexing runs, out of order, canceled, and a read
failure.

## Time sync groups

`TimeSyncGroups` (`WindowSet.timeSync()`, UI thread) holds the time sync
groups, which span the windows, and runs Scroll All Tabs to This Time. A group
is a set of `DocumentController`s, and a tab is in at most one.

### Sync moves

`TimeController.syncTo(shown)` is `goToTime` for a move another tab caused. The
target for tab `k` is the source's time plus its offset, minus `offset(k)`.

- It releases follow and shows no position or progress notice. A cancel by
  `Esc` (`STOP` on that tab, with `goToTime`'s rule) and a read failure show
  theirs.
- A tab without a format stays where it is. A hidden tab moves as a shown one
  does.

### Move propagation

`DocumentController.caretMoved` feeds `TimeController.caretMoved`, which
reports a move to another source line to the group's `SyncListener`.

- A sync move, a remembered position (`restorePosition`) and a reload's kept
  line (`keepLine`) are quiet, so members cannot bounce and a reload moves
  nobody. Follow scrolls without moving the caret and reports nothing.
- A reported move cancels every member's running sync move. The group's last
  move starts `readCaretTime` on the source (a `qlogg-read-` worker, the newest
  read wins) after a 100 ms `timerExec`, then `syncTo` on the others.
- A caret move in any tab also ends that tab's own sync move, Scroll All's
  included.

### Membership

- Membership follows the controller, so a tab move keeps it. `replace` hands it
  to a reload's or reopen's new controller before the old one closes, and
  `TimeController.detach` removes it on any close.
- A group left with one member dissolves.
- `ApplicationListener.timeSyncChanged` reports every change, for the tab
  tooltips.
- The session's `window.W.tab.N.syncGroup` is the group's place among the
  groups with a saved tab, from 1. A restored tab waits in `restored` until a
  second tab of its number arrives, so a group restored with one open member
  never forms.

## Time spread

View > Timestamps > Time Spread (`TIME_SPREAD_*`, `view.timeSpread`) sets one
mode for every open document. With a mode and a format, `OverviewController`
samples a stamp at each row boundary after the bins, in the same
`qlogg-search-` computation, and keeps the `TimeSpread` levels (`spread()`).

- The levels cost 1 B per row plus 64, charged to `HIGHLIGHT`. The samples are
  charged only while they are read. A refusal or a read failure shows no
  spread.
- A new height, mode, format or zone samples at the next computation. An offset
  changes nothing.
- Growth, or any change of the row count, the collapsed entries or the index
  generation, samples no sooner than `GROWTH_MILLIS` (2 s) after the last
  spread. A `UiDispatcher.timerExec` wait starts when a sampled computation
  posts, and its end samples what grew meanwhile.
