# qlogg-core

Model-side code with no SWT and no logging dependency: thread ownership, the
memory budget, file access, text handling, the line index, search, the
document model and small utilities. Tests run without a display.

The test jar carries `testkit` for the other modules' tests and `qlogg-bench`:
`SyntheticLog`, the deterministic log writer; `Poll.until`, a bounded poll for
state another thread produces; and `TempTrees.delete`, which deletes a tree and
retries until a bound, because Windows keeps a file a terminated child process
had open delete-pending for a moment.

## Threads (`core.concurrent`)

`Workers` owns every thread QLogg starts, and nothing else creates a thread or
an executor (enforced by `ArchitectureTest` in `qlogg-launcher`). The launcher
creates one instance and closes it at shutdown.

| Work | Runs on | Names |
|---|---|---|
| CPU-bound (`executeCpu`) | Fixed pool of platform threads, bounded queue of 4,096 | `qlogg-cpu-N` |
| Blocking (`start(Kind, Runnable)`) | A virtual thread per task | `qlogg-index-`, `qlogg-read-`, `qlogg-search-`, `qlogg-follow-`, `qlogg-ipc-`, `qlogg-lifecycle-`, `qlogg-watchdog-` |

- Every task runs inside a wrapper that passes an exception it ends with to the
  failure handler given to the constructor, which is also each thread's
  uncaught exception handler. No task returns a `Future`.
- After `close`, `start` and `executeCpu` throw `RejectedExecutionException`;
  `tryStart` returns false instead.
- `close(bound)` rejects new tasks, waits up to the bound for running ones,
  then interrupts what is left (`shutdownNow` for the pool). A virtual thread
  that calls `close` does not wait for itself. `liveThreads()` counts what has
  not ended, and `liveThreadNames()` names it for a log line.
- `shutdownHook(task)` creates the JVM shutdown hook's platform thread,
  `qlogg-shutdown`, unstarted and outside the live set, so `close` neither
  waits for nor interrupts it.
- `start` adds the thread to the live set, then checks `closed` again before
  starting it, and `close` reads the set until it holds no other thread. A
  start racing `close` is therefore rejected or waited for; no task starts
  after `close` returns.
- Pool threads are daemon threads, so a missed `close` cannot keep the JVM
  alive.
- Code that catches `InterruptedException` restores the interrupt flag and
  returns.

A background operation returns an `OperationHandle` (`cancel`, `isDone`,
`await`) and ends `COMPLETED`, `CANCELED` or `FAILED`. Its listener's final
call comes before `isDone()` turns true. `CancellationToken.check()` throws
`CancellationException` once canceled.

`OperationGate` counts the operations using a resource: `enter()` admits one
until `close()`, `exit()` ends it, and `awaitIdle` returns once the gate is
closed and the count is zero. `enter` and `exit` are lock-free, so the UI
thread may call them.

## Memory budget (`core.concurrent`)

`MemoryBudget` divides `Runtime.maxMemory()` (or a fixed size, in tests) into
shares that all open documents draw from:

| Share | Percent | Holder |
|---|---|---|
| `LINE_INDEX` | 40 | `GrowableLineIndex` |
| `RESULTS` | 20 | Search results, marks |
| `BLOCK_CACHE` | 10 | `BlockCache` |
| `IO_BUFFERS` | 10 | `ReadBuffers` |
| `FOLDS` | 3 | Fold maps |
| `HIGHLIGHT` | 2 | Highlight and layout caches |
| `COPY` | 5 | Text being copied to the clipboard |
| `PASTED` | 2 | Text opened from the clipboard, while its document is open |

The remaining 8% is headroom. A structure calls `reserve` before it allocates
and `release` when it closes; a refused reservation makes it degrade instead of
failing with `OutOfMemoryError`. `charge` reserves without the limit check, for
the minimum a structure needs to work at all.

## Paths (`core.io`)

Every path from outside the process goes through `SafePaths`.

| Method | Rule |
|---|---|
| `logFile` | Rejects empty, NUL and, on Windows, device forms (`\\.\`, `\\?\`, `CON`, `NUL`, `COM1`...). Made absolute and normalized. An existing path resolves to its real path and must be a regular file: a FIFO blocks the reader and a device never ends. A missing path needs an existing parent directory, so a log that appears later can be opened |
| `settingsDirectory` | Absolute, normalized, created with POSIX `rwx------` where supported, resolved to its real path, must be a directory |
| `child` | A fixed name matching `[A-Za-z0-9._-]+`, resolved inside the directory |

A log viewer opens whatever readable file its user names, so a log path is
canonicalized and type-checked, not contained.

## File access (`core.io`)

| Class | Contract |
|---|---|
| `FileByteSource` | `open` validates through `SafePaths.logFile`, then opens a `FileChannel` for reading. Positional reads only, thread-safe. A read after `close` throws `ClosedChannelException`. `identity()` holds the file key (null on Windows), creation and modified times, size and the CRC32C of the first 4 KiB. On Windows the JDK opens the file with delete sharing, so a writer can rename, truncate or delete it while it is open |
| `MemoryByteSource` | A `ByteSource` over a byte array in the heap, for pasted text. `path()` is null; the size never changes. A read after `close` throws `ClosedChannelException` |
| `BlockCache` | LRU of fixed-size blocks for random reads, keyed by source and block number; the lock is never held during I/O. A block read short at end of file is read again when a request reaches past it. `invalidateFrom` drops a block and everything after it |
| `ReadBuffers` | Pool of little-endian direct buffers for sequential scans, kept for reuse. The first two are charged to `IO_BUFFERS` unconditionally; past the share, `tryAcquire` returns null and `acquire` waits |
| `AtomicFiles` | `write` puts the content in a temporary file in the target's directory (POSIX `rw-------`), then moves it over the target with `ATOMIC_MOVE`; on failure the target is unchanged and the temporary file deleted. Nothing goes to `java.io.tmpdir`. `write(Path, InputStream)` streams the content through a 64 KiB buffer. `writeOwnerOnly`, for a secret, also replaces the temporary file's ACL with one entry for its owner where there are ACLs but no POSIX permissions (Windows) |

Nothing is memory mapped: Java 21 releases a mapping only through garbage
collection, and on Windows a live mapping blocks the writer's truncation.

## Encoding (`core.text`)

`EncodingDetector.detect` reads the first 64 KiB and applies the first rule
that matches:

| Rule | Result |
|---|---|
| BOM `EF BB BF`, `FF FE`, `FE FF` | UTF-8, UTF-16LE, UTF-16BE; the first line starts after the BOM |
| At least 30% zero bytes at odd offsets and under 5% at even ones (or the reverse) | UTF-16LE (or UTF-16BE) |
| Valid UTF-8; a sequence cut off by the end of the sample counts as valid | UTF-8 |
| None | The fallback charset; ISO-8859-1 maps every byte to a char |

`EncodingDetector.forced` applies a charset the user chose and skips that
charset's own BOM; a BOM-sensing `UTF-16` follows the BOM's byte order.
`TextEncoding` derives the LF unit width (1, 2 for UTF-16, 4 for UTF-32) from
the charset and takes `UTF-16` and `UTF-32` as big-endian.

`LineDecoder` (one per thread) decodes a line's bytes with malformed input
replaced by U+FFFD and drops one trailing CR unit; a lone CR elsewhere stays.

## Line index (`core.index`)

`GrowableLineIndex` holds one document's line starts. One `Indexer` operation
at a time writes it (a second gets `IllegalStateException`); any thread reads
`snapshot()`, an immutable `LineIndex` whose `generation()` increases with each
publication. Lines end at LF only: `lineEnd` excludes the LF, a CR before it is
part of the line until `LineDecoder` drops it, and a region ending without LF
counts its partial last line. `resumeOffset()` is the start of the last line,
so a later operation rescans a partial line as the file grows.

| Mode | Storage | Cost | Lookup |
|---|---|---|---|
| Full | Blocks of 128 lines: base offset, bit width, word index (13 B), then the 127 line lengths packed at the block's width into 64 KiB `long[]` pages | (13 + 8 x ceil(127 x w / 64)) / 128 B per line: 0.60 at w = 4, 0.98 at w = 7, 1.10 at w = 8, 2.10 at w = 16 | In memory: binary search over block bases, then at most 127 lengths summed |
| Sparse | A checkpoint (line, offset) at the first line 1,024 lines or 1 MiB past the previous one | 16 B per checkpoint, about 0.016 B per line for short lines | Reads forward from the nearest checkpoint through the `BlockCache`, or from the snapshot's last found line start when that lies between the checkpoint and the target, so consecutive lookups read each line once; at most 1 MiB before the target line starts. A failed read throws `UncheckedIOException` |

Every allocation (metadata chunks of 1,024 blocks, pages, checkpoint chunks of
1,024, doubling directories) is reserved from `LINE_INDEX` first, and
`retainedBytes()` equals what is reserved. A refused reservation converts the
index in place to sparse mode, which lasts until the index is closed. When
even the sparse index is refused, indexing fails with `IndexCapacityException`
naming the bytes needed and available. `close()` returns the reservation;
published snapshots stay readable.

## Indexing (`core.index`)

`Indexer.index` starts a coordinator on a `qlogg-index-` virtual thread. It
reads blocks (4 MiB by default, a multiple of 8 so no UTF-16 or UTF-32 unit
straddles two) into `ReadBuffers` and submits each block to the CPU pool,
which marks the LF units in a bitmap, eight bytes at a time for 1- and 2-byte
units. Only units at multiples of the unit width count, so a `0x0A` byte inside
a UTF-16 unit is not an LF. The coordinator appends results in file order and
publishes a snapshot at most every 100 ms and once at the end.

- At most `maxBlocksInFlight` blocks (default two per CPU thread) are read but
  not appended; fewer when `IO_BUFFERS` is short.
- Cancellation is checked before each read and while waiting for a scan, so an
  operation stops within one block. A read on a closed source also ends it as
  `CANCELED`; an I/O error or a refused index ends it as `FAILED`.
- A trailing partial UTF-16 or UTF-32 unit is not indexed until the file grows.
- `IndexListener` calls run on the coordinator thread and must not block toward
  the UI thread.

## Search (`core.search`)

`SearchEngine.search(source, query, results, listener)` searches the lines of
the source's current index snapshot, limited to `SearchQuery.scope` (null for
every indexed line), and adds the matching line numbers to a `MutableLineSet`
in ascending order. A coordinator on a `qlogg-search-` virtual thread cuts the
range into chunks of whole lines (10,000 lines or 4 MiB, whichever comes
first; a longer line is a chunk of its own), reads each chunk in one
positional read and submits it to the CPU pool. At most `maxChunksInFlight`
chunks (default three per CPU thread) are read but not merged. A worker splits
its chunk at LF units, drops a trailing CR from each line and matches it; the
coordinator merges the results in chunk order, so they stay in line order
however chunks finish, and no match spans two chunks.

| End | Outcome | Limit |
|---|---|---|
| Range searched | `COMPLETED` | `NONE` |
| `maxResults` reached (0 is no limit) | `COMPLETED` | `MAX_RESULTS`; the results stop at the limit |
| `RESULTS` refuses the next chunk's matches, or `IO_BUFFERS` refuses a long line's buffer with nothing in flight | `COMPLETED` | `MEMORY`; the results up to the last whole chunk stay |
| `cancel()`, a read on a closed source, or closed workers | `CANCELED` | `NONE` |
| A regex that does not compile (`PatternSyntaxException`), an I/O error, or a file shorter than its index | `FAILED` | `NONE` |

- A chunk reads into a pooled direct buffer of `chunkBytes` (`ReadBuffers`,
  from `IO_BUFFERS`). A line longer than `chunkBytes` reads into a heap
  buffer, and 3 bytes per line byte (the bytes and their decoded chars) are
  reserved from `IO_BUFFERS` until it is matched.
- Cancellation is checked before each chunk, every 1,024 lines in a worker,
  and every 4,096 `charAt` calls a regex makes on `LineText.chars()`, so a
  regex that backtracks without end stops on cancel.
- `SearchListener.progressed` runs at most every 100 ms and once at the end,
  before `finished`; both run on the coordinator thread.

### Matching

`StandardMatchers` compiles a query into one `LineMatcher` per worker; each
term takes one of these forms:

| Term | Runs as |
|---|---|
| Literal, case-sensitive | Byte search for the pattern encoded in the document charset |
| Literal, case folded, ASCII pattern, ASCII-compatible encoding | Byte search folding `A`-`Z` |
| Any other literal, or one whose pattern does not encode | `Pattern.quote` regex on decoded text |
| Case-sensitive regex with no metacharacter (`\ ^ $ . \| ? * + ( ) [ ] { }`) and no U+FFFD | As a case-sensitive literal |
| Any other regex | `java.util.regex.Pattern` on decoded text with `UNIX_LINES`, plus `CASE_INSENSITIVE \| UNICODE_CASE` when case folds |

- Byte search runs only where a byte match is a character match: UTF-8,
  US-ASCII, single-byte charsets, and UTF-16 or UTF-32 at unit-aligned
  offsets. Other multi-byte charsets (Shift_JIS, GBK) run as regex.
- A byte search sees the file's bytes; a regex sees malformed input as U+FFFD.
- Byte folding folds ASCII letters only, so `k` does not match U+212A KELVIN
  SIGN, which a case-folded regex matches.
- The line excludes LF and a trailing CR. `^` and `$` anchor at its ends, and
  `UNIX_LINES` makes no other char a line terminator.
- Whole word: a term matches only where the chars before and after it are not
  `[A-Za-z0-9_]`. A byte search checks the bytes around the match (ASCII-compatible
  encodings only); otherwise the regex is wrapped in `(?<!\w)(?:...)(?!\w)`.
- Inverse negates the whole expression.
- With `SearchQuery.hideAnsi`, `LineText` removes ANSI SGR sequences from the
  decoded chars and every literal runs as a quoted regex, because the file's
  bytes still hold the sequences.

`ExpressionParser.parse(text, mode, combining)` reads the combining syntax:
terms in double quotes (`\"` is a quote inside a term; other backslashes stay,
so regex escapes pass through), `and` `&`, `or` `|`, `xor`, `not` `!`, the
aliases `nand`, `nor` and `xnor`, and parentheses. Precedence, highest first:
`not`, `and`, `xor`, `or`. Operator words are case-insensitive. Combining text
without a double quote is one term. A syntax error throws
`ExpressionException` with a `Reason` and a 1-based column; nesting deeper
than 256 is an error.

### Result sets

`MutableLineSet` holds line numbers in buckets of 65,536 lines: a sorted
`char[]` of the low 16 bits up to 4,096 entries, else a 1,024-word bitmap; a
bitmap returns to an array at 2,048 entries or fewer. Counts of the entries
before each bucket make `rank` and `select` a binary search over buckets.

- Cost (`retainedBytes()`): 24 B per bucket, plus 2 B per array slot or 8 KiB
  per bitmap.
- With a budget, the set holds exactly that much of `RESULTS`. `addSorted`
  reserves an upper bound of the batch's growth first and adds nothing when
  the share refuses; `add` and `toggle` charge without the limit check;
  removals and `clear()` release.
- A `ReentrantReadWriteLock` guards the set. A writer holds it for one batch
  and never across I/O, so a reader on the UI thread waits at most for one
  chunk merge.
- `version()` counts changes, so a reader detects a toggle that left the size
  unchanged.

## Log text (`core.text`)

`LogText.oneLine` reduces a value that did not originate in QLogg's code (a
path, a pattern, an exception message) to one line before it reaches a logger:
C0 controls, DEL, NEL, U+2028 and U+2029 become `_`, and the result is cut at
1,024 chars. `config/findsecbugs-taint.txt` registers it with find-sec-bugs as
removing CR and LF.

## File monitoring (`core.watch`)

`FileMonitor.watch(path, listener)` starts a `qlogg-follow-` thread that calls
`MonitorListener.possiblyChanged` at once, then every `pollMillis` (1,000 by
default) and, with `useWatchService`, on a parent-directory event for the file
name, at most once per 50 ms. The poll stays on with directory events, because
a `WatchService` misses events on network mounts and polls on macOS. One
`WatchService` serves every directory; its dispatcher thread ends when no
directory is registered or the workers close. Registration runs on the watch's
own thread; a directory that cannot be registered is polled only. `cancel`
ends the watch within one check. `setSettings` replaces the settings: the
interval applies from each watch's next wait, directory events to watches
started afterwards.

`ChangeDetector.classify(baseline, path)` compares the file at the path with
what was indexed (`Baseline`: identity at open, covered bytes, tail digest and
its modification time), in this order. While indexing runs, the covered bytes
are at least the size at open and the size indexing reads to, so a check
before the first progress event does not report the indexed file as appended.
`LogDocument` passes the tail digest only when it ends at the covered bytes;
otherwise the `MODIFIED` row is skipped for that check, because an indexing
pass publishes its last snapshot before it records the new tail.

| Observation | Result |
|---|---|
| No regular file | `MISSING` |
| Another file key; where there is none (Windows), another creation time | `REPLACED` |
| Size below the covered bytes | `TRUNCATED` |
| CRC32C of the first min(4 KiB, size at open) bytes differs | `REPLACED` |
| Size above the covered bytes | `APPENDED` |
| Same size, newer modification time, CRC32C of the last 4 KiB differs | `MODIFIED` |
| Otherwise | `UNCHANGED` |

Creation time decides only without a file key: JDK 21 on Linux reports the
modification time as the creation time. On Windows a file recreated under the
same name within 15 s can inherit the old creation time (NTFS tunneling,
assumed from the Windows documentation), so the head digest decides there. A
check that sees the new file before its first write lands reports `TRUNCATED`
there, which reopens the path as `REPLACED` does.
The detector opens the file only to read a digest and closes it at once.

## Documents (`core.model`)

`LogDocument` is one open file. `open(path, forcedCharset, services)` validates
the path (`FileByteSource.open`), reads the first 64 KiB, detects the encoding
and creates the line index; it blocks, so it runs on a worker.
`startIndexing()` starts the `Indexer` and returns. `index()` and `status()`
read published state without I/O. `DocumentServices` carries the shared
`Workers`, `Indexer`, `SearchEngine`, `BlockCache`, `MemoryBudget` and
fallback charset. `marks()` is the document's `MutableLineSet` of marked
lines, charged to `RESULTS`; `close` empties it. `name()` is the file name.

`ofText(name, text, maxBytes, services)` creates a document over pasted text:
the UTF-8 bytes are held in a `MemoryByteSource` and charged to `PASTED` until
`close`. Text over `maxBytes` or the share throws `PastedTextException` with
the size and the limit. Such a document reports `inMemory()`, has a null
`path()`, and ignores `startWatching`.

Every operation on the file holds an `OperationGate` entry while it runs:
indexing, each watch check, and every read, search and scan a controller
starts (`enter()`, `exit()`). `close(bound)` closes the gate, so `enter`
fails, cancels indexing and the watch, waits up to the bound for every entry
to exit and for the watch to end, then releases the index reservation, closes
the file and calls `DocumentListener.closed()`. Callers cancel their own
operations before closing. `close` returns false when an operation outlived
the bound, which leaves the reservation held; that operation then reads a
closed file and ends on `ClosedChannelException`. `closeAll(documents, workers,
bound)` closes documents at the same time, each on its own
`qlogg-lifecycle-` worker, so shutdown with several stuck documents takes one
bound rather than one per document; it returns the documents that did not
close cleanly.

`startWatching(monitor)` checks the path on each `FileMonitor` call:

- `APPENDED` indexes from `GrowableLineIndex.resumeOffset()`, the start of the
  last line, so a partial last line is scanned again. One indexing operation
  runs at a time; growth seen while one runs starts another when it ends.
- `TRUNCATED`, `REPLACED` and `MODIFIED` reach `DocumentListener.fileChanged`
  once, and the document stops checking: its owner reopens the path, which is
  how a renamed log is followed by name.
- `MISSING` is reported once; the open file stays readable. A file that
  reappears is reported as `REPLACED`.
- `UNCHANGED` is reported when only the size or modification time moved, for
  the file fields. `status()` carries the file size, modification time and the
  missing flag.
- After each completed indexing operation the document records the
  modification time, then the CRC32C of the indexed tail, for `MODIFIED`.

`stopIndexing()` cancels indexing and leaves later growth unindexed while the
document is open.

`LineReader` (one per thread, from `newReader()`) decodes lines through the
block cache. It reads at most the first 1 MiB of a line, so a longer line
shows and copies its first MiB.

- `read(index, first, count, firstColumn, columns)` returns a `LineBlock`:
  each line cut to the display columns `[firstColumn, firstColumn + columns)`,
  tabs expanded to spaces, a trailing CR dropped, plus each line's full
  display width. Decoded text is bounded by the window, not by the line.
- `readRows(index, firstRow, lines, count, firstColumn, columns,
  highlights)` decodes listed lines as consecutive rows;
  `LineBlock.sourceLine(row)` gives each row's line. With a
  `Highlighter.LineMatcher` it also colors each row from the whole decoded
  line (`LineBlock.spans(row)`). The views read through it.
- `appendText(index, line, fromColumn, toColumn, out)` appends the chars whose
  display column lies in the range, tabs kept, for copy.
- `appendColumns(index, line, fromColumn, toColumn, out)` does the same for a
  block selection, but a tab partly inside the range becomes one space per
  covered column. Nothing is padded.
- `readRows(..., highlights, markers)` with `markers` also records one
  whitespace marker per char of each row (`LineBlock.whitespace`: tab start,
  trailing space, lone CR, which then shows as a space) and how its line ends
  (`LineBlock.terminator`: `LF`, `CRLF`, or `NONE` for a last line without
  LF). `LineDecoder.droppedCarriageReturn()` supplies the CRLF case.
- `decodedLine(index, line)` returns the decoded line, tabs kept, valid until
  the next call; quick find matches on it.
- Display columns: one per char, a tab advances to the next multiple of 8,
  and the low half of a surrogate pair takes none. A lookup in a sparse
  index reads the file and can throw `UncheckedIOException`.
- After `LogDocument.setHideAnsi(true)` every reader of the document removes
  ANSI SGR sequences (`AnsiCodes`) at decode, so display, highlighting, copy,
  columns and quick find all see the stripped text. A search sees it only
  with `SearchQuery.hideAnsi` set.

### Folding

`FoldMap` holds the collapsed entries of the main view: sorted `long` start
and end arrays with a prefix sum of hidden lines, so `rowOf(line)` and
`lineAt(row)` are binary searches and a hidden line maps to its entry's row.
`collapse(start, end)` refuses an entry of one line, one that overlaps a
collapsed entry, and one `FOLDS` cannot hold (24 B per entry). A change
replaces the arrays, so a `cursor(lineCount)` keeps the entries it was created
with and a worker can walk it; it is `contiguous()` only while nothing is
collapsed. Confined to one thread otherwise.

`FoldClassifier.entries(reader, index, rules, from, to, token)` returns the
entries of two or more lines that overlap `[from, to)`, reading only that
region plus the entry around its edges. `FoldRules` decides continuation
lines: with `entryStart` set, every line it finds no match in; otherwise a
line a `continuation` pattern finds a match in (defaults: `^\s`, `^at `,
`^Caused by:`, `^\.\.\. \d+ more`). Line 0 always starts an entry. An entry
longer than 10,000 lines is not reported, so it stays unfolded. It blocks and
checks the token per line.

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

### Highlighting

`HighlightRule` is one highlighter: pattern, regex or literal, case, whole line
or matched text, text and background colors (either null for none),
background opacity 0-255, color variance 0-50 and enabled. `HighlightSet` is a
named, ordered list of rules with an id (at most 1,000 rules).

`Highlighter.of(rules)` takes rules in apply order: each paints over the ones
before it. Where `HighlightSettings` combines sets, the top rule applies last,
so it wins.

- A whole-line rule that matches sets the line's colors (a null color keeps
  the earlier one) and drops the spans earlier rules added.
- A match-only rule adds a span per match, or per captured group when the
  pattern has groups. With variance, each matched string gets its own color,
  from the CRC32 of its UTF-8 bytes, up to that many percent darker or
  lighter, the same every time.
- Spans are display columns clipped to the read's column window, sorted and
  without overlap (`HighlightSpans`, colors packed `0xAARRGGBB`, alpha 0 for
  none). A line keeps at most 256 spans. Cost: 16 bytes per span plus 32.
- Patterns compile on first use, on the reading thread; one that does not
  compile is skipped. A rule that reads more than `8 * length + 65,536`
  chars of a line, or overflows the regex stack, is abandoned for that line,
  and a `LineMatcher` stops coloring after 2^26 chars, so a backtracking
  pattern bounds a read instead of stalling it.
- Measured once on this host, one thread: four rules over a 1 MiB line take
  15 ms; 60 rows of 80 chars take 0.08 ms.

### Overview bins and mark toggles

`OverviewBins.compute(matches, marks, lineCount, height, token)` counts the
lines of each set per pixel row as differences of `rank` calls: a few lookups
per row, whatever the set sizes. `lineAt(y)` is `floor(y * lineCount /
height)`, exact for every `long` line count; `rowOf(line)` is its inverse.
Cost: 8 bytes per pixel row plus 64.

`MarkToggle.run(marks, rows, firstLine, count, token, progress)` marks the
rows' lines unless all are marked, then unmarks them, in batches of 4,096
under one write lock each (`MutableLineSet.addSorted`, `removeSorted`). A
contiguous range checks "all marked" with two `rank` calls. Marking reserves
from `RESULTS` and stops at the first batch the share refuses; cancel stops
between batches. Lines changed before either stay changed.

### Filtered view

`FilteredView` maps the filtered view's rows to lines over a result set and
the marks, per `Visibility`: `MARKS_AND_MATCHES`, `MARKS` or `MATCHES` (in
the order `next()` cycles). It copies nothing per row:

- `MATCHES` and `MARKS` use `select` and `rank` on the one set.
- `MARKS_AND_MATCHES` uses the matches plus an `Extras` snapshot: the marks
  that were not matches when it was built, in a `MutableLineSet` that
  reserves from `RESULTS` (a partial snapshot when the share refuses).
  `buildExtras` walks the marks in batches (`MutableLineSet.fill`) on a
  worker; `installExtras` puts it in place and releases the previous one;
  `extrasStale` reports a change of the marks' `version()` or the matches.
  Until the next snapshot is installed, marks toggled meanwhile are not
  listed and a mark the matches gained meanwhile counts twice. A row's line
  is a binary search over line values on the sum of the two ranks.
- `rowOf(line)` returns the row of a line or of the next listed line.
- The class is confined to one thread. `cursor()` returns a thread-safe
  `RowCursor` (`next`, `previous` over the sets the mode lists) for a worker; copy and
  quick find walk rows through it. `RowCursor.all(lineCount)` walks every
  line.

### Quick find

`LineFinder.find(reader, index, rows, pattern, line, column, forward, token)`
returns the nearest `Match` (line, start and end display columns) after or
before a position, visiting the lines a `RowCursor` lists; null when none is
left in that direction. Forward, a match starts at or after the column on the
start line; backward, it starts before it. Empty matches are skipped. It
blocks: the caller runs it on a worker. It checks the token every 1,024 lines
and every 4,096 chars a matcher reads, so a backtracking regex ends on cancel.

## Utilities (`core.util`)

`core.util` depends on no other QLogg package (`ArchitectureTest`).

| Class | Contract |
|---|---|
| `Checks` | `atLeast` and `notNegative` throw `IllegalArgumentException` naming the argument |
| `Streams` | `readLimited` reads a stream to its end and throws `IOException` once it passes the limit, so an oversized file is refused rather than truncated |
