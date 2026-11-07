# Documents

`core.model` models an open file: `LogDocument`, line reads, the viewport and
quick find. Folding, highlighting, marks and the filtered view are in
[View models](view-models.md). Module overview: [qlogg-core](../README.md).

## LogDocument

`LogDocument` is one open file. `DocumentServices` carries the shared
`Workers`, `Indexer`, `SearchEngine`, `BlockCache`, `MemoryBudget` and
fallback charset.

| Method | Contract |
|---|---|
| `open(path, forcedCharset, services)` | Validates the path (`FileByteSource.open`), reads the first 64 KiB, detects the encoding and the timestamp format from those bytes (`FormatDetector`), and creates the line index. Blocks; run it on a worker |
| `open(..., detectTimeFormat)` | `false` skips the format detection, for a file whose format the owner remembers |
| `open(..., detectTimeFormat, keepClosed)` | `true` reads through a `ReopeningByteSource` ([Keep file closed](#keep-file-closed)) |
| `ofText(name, text, maxBytes, services)` | A document over pasted text, below |
| `startIndexing()` | Starts the `Indexer` and returns |
| `stopIndexing()` | Cancels indexing; later growth stays unindexed while the document is open |
| `index()`, `status()` | Published state, read without I/O. `status()` carries the file size, modification time and the missing flag |
| `timeFormat()` | The detected format, null for none |
| `newTimeReader(parser)` | A `TimeReader` over the document through the shared `BlockCache` |
| `openedModifiedMillis()` | The modification time at open, for `TimeContext` |
| `marks()`, `markComments()` | The marked lines (`MutableLineSet`) and their `MarkComments`, both charged to `RESULTS`; `close` empties them |
| `path()`, `realPath()`, `name()` | The path as given (absolute, normalized), the file opened, and the file name of `path()` |

A symbolic link stays a link: the watch follows it, a retarget reads as
`REPLACED`, and the owner's reopen opens the new target.

### Pasted text

`ofText` holds the UTF-8 bytes in a `MemoryByteSource`, charged to `PASTED`
until `close`; its timestamp format is detected as for a file. Text over
`maxBytes` or the share throws `PastedTextException` with the size and the
limit. The document reports `inMemory()`, has a null `path()`, ignores
`startWatching` and ignores `keepClosed`.

### Operations and close

Every operation on the file holds an `OperationGate` entry while it runs:
indexing, each watch check, and every read, search and scan a controller
starts (`enter()`, `exit()`). Callers cancel their own operations before
closing.

`close(bound)`:

1. Closes the gate, so `enter` fails.
2. Cancels indexing and the watch.
3. Waits up to the bound for every entry to exit and for the watch to end.
4. Releases the index reservation, closes the file, drops its blocks from the
   `BlockCache` and calls `DocumentListener.closed()`.

`close` returns false when an operation outlived the bound. The reservation
then stays held, and that operation reads a closed file and ends on
`ClosedChannelException`.

`closeAll(documents, workers, bound)` closes documents at the same time, each
on its own `qlogg-lifecycle-` worker, so shutdown with several stuck documents
takes one bound rather than one per document. It returns the documents that
did not close cleanly.

## Watching

`startWatching(monitor)` checks the path on each `FileMonitor` call
([File monitoring](file-monitoring.md)):

| Result | Document action |
|---|---|
| `APPENDED` | Indexes from `GrowableLineIndex.resumeOffset()`, so a partial last line is scanned again. One indexing operation runs at a time; growth seen while one runs starts another when it ends |
| `TRUNCATED`, `REPLACED`, `MODIFIED` | Reaches `DocumentListener.fileChanged` once, and the document stops checking; its owner reopens the path, which is how a renamed log is followed by name |
| `MISSING` | Reported once; the open file stays readable unless the document keeps its file closed. A file that reappears is reported as `REPLACED` |
| `UNCHANGED` | Reported when only the size or modification time moved, for the status fields |

After each completed indexing operation the document records the modification
time, then the CRC32C of the indexed tail, and extends the head digest over the
indexed bytes until it covers 4 KiB.

### Failed reopen

An owner whose reopen fails must call `reopenFailed()`; otherwise following
ends with the status unchanged. The call resumes the checks, and the next one
reports the change again or `MISSING`. It returns true for the first failure of
a run, for one notice per run. `status().reopenWaiting()` holds until a reopen
replaces the document or a check classifies the file as `UNCHANGED` or
`APPENDED`.

## Keep file closed

With `keepClosed`, the document reads through a `ReopeningByteSource`, so
nothing holds the file between reads and a writer on Windows can rename over
the path. The document keeps the source it opened with.

- `open` reads the head through a held channel, so a rotation during an open
  fails the first indexing pass, which the next check reports, and not the
  open.
- Each `read` validates the real path captured at open through
  `SafePaths.logFile`, opens it, and compares, with the channel open, the file
  key (without one, the creation time), the head digest, and the tail digest
  `recordTail` passes to `expect`. The read runs on that verified channel, so
  it returns that file's bytes or fails; old offsets never read a new file.
- A mismatch throws `FileChangedException` and sets `changed()`; a missing
  file throws `NoSuchFileException`. A failed read takes the normal error path
  of its caller.
- A symbolic link is not followed again: the source stays on the real path
  until the watch reports the retarget.
- The next check reports `REPLACED` when `changed()` is set and
  `ChangeDetector` saw `UNCHANGED` or `APPENDED`. Until the owner reopens,
  reads fail and blocks already in `BlockCache` stay readable.
- A read or a check holds a handle for its length, so a rename that lands
  inside one is refused and succeeds on a retry.

## LineReader

`LineReader` (one per thread, from `newReader()`) decodes lines through the
block cache. It reads at most the first 1 MiB of a line, so a longer line shows
and copies its first MiB.

| Method | Returns |
|---|---|
| `read(index, first, count, firstColumn, columns)` | A `LineBlock`: each line cut to the display columns `[firstColumn, firstColumn + columns)`, tabs expanded to spaces, a trailing CR dropped, plus each line's full display width. Decoded text is bounded by the window, not by the line |
| `readRows(index, firstRow, lines, count, firstColumn, columns, highlights)` | Listed lines as consecutive rows; `LineBlock.sourceLine(row)` gives each row's line. With a `Highlighter.LineMatcher` it colors each row from the whole decoded line (`LineBlock.spans(row)`). The views read through it |
| `readRows(..., highlights, markers)` | Also one whitespace marker per char (`LineBlock.whitespace`: tab start, trailing space, lone CR, which then shows as a space) and the line end (`LineBlock.terminator`: `LF`, `CRLF`, or `NONE` for a last line without LF, from `LineDecoder.droppedCarriageReturn()`) |
| `appendText(index, line, fromColumn, toColumn, out)` | Appends the chars whose display column lies in the range, tabs kept, for copy |
| `appendColumns(index, line, fromColumn, toColumn, out)` | The same for a block selection, but a tab partly inside the range becomes one space per covered column. Nothing is padded |
| `decodedLine(index, line)` | The decoded line, tabs kept, valid until the next call; quick find matches on it |

- Display columns: one per char, a tab advances to the next multiple of 8, and
  the low half of a surrogate pair takes none.
- A lookup in a sparse index reads the file and can throw
  `UncheckedIOException`.
- After `LogDocument.setHideAnsi(true)` every reader of the document removes
  ANSI SGR sequences (`AnsiCodes`) at decode, so display, highlighting, copy,
  columns and quick find all see the stripped text. A search sees it only with
  `SearchQuery.hideAnsi` set.

## Viewport

`Viewport` is the visible window (top line, rows, first column, columns) and
its scroll bar mapping; the UI thread owns it. With
`maxTop = max(0, lineCount - rows)`:

| Mode | When | Thumb | Maximum | Selection for `top` |
|---|---|---|---|---|
| One unit per line | `maxTop <= 2^30` | `rows` | `maxTop + rows` | `top` |
| Scaled | `maxTop > 2^30` | `max(1, rows * 2^30 / maxTop)` | `2^30 + thumb` | `round(top / maxTop * 2^30)` |

The scaled mapping and its inverse run in `double`, exact at both ends for
`maxTop < 2^53`. Keys, the wheel and go to line set the top line directly;
only a thumb drag goes through `setScrollbarSelection`.

`Selection` (anchor, caret), `TextPosition` (line, display column), `Rgb` and
`DocumentId` are value records.

## Quick find

`LineFinder.find(reader, index, rows, pattern, line, column, forward, token)`
returns the nearest `Match` (line, start and end display columns) after or
before a position, visiting the lines a `RowCursor` lists, or null when none is
left in that direction.

- Forward, a match starts at or after the column on the start line; backward,
  it starts before it. Empty matches are skipped.
- It blocks; run it on a worker.
- It checks the token every 1,024 lines and every 4,096 chars a matcher reads,
  so a backtracking regex ends on cancel.
