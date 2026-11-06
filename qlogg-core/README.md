# qlogg-core

Model-side code with no SWT and no logging dependency: thread ownership, the
memory budget, file access, text handling, the line index, search,
timestamps, the document model and small utilities. Tests run without a
display.

The test jar carries `testkit` for the other modules' tests and `qlogg-bench`:
`SyntheticLog`, the deterministic log writer; `Poll.until`, a bounded poll for
state another thread produces; and `TempTrees.delete`, which deletes a tree and
retries until a bound, because Windows keeps a file a terminated child process
had open delete-pending for a moment.

## Threads (`core.concurrent`)

`Workers` owns every thread QLogg starts, and nothing else creates a thread, an
executor, a `ThreadFactory` or a `java.util.Timer` (enforced by
`ArchitectureTest` in `qlogg-launcher`). The launcher
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
| `RESULTS` | 20 | Search results, marks, mark comments, marks list rows, quiet trigger lines |
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
| `logPath`, `logFile` | Rejects empty, NUL and, on Windows, device forms (`\\.\`, `\\?\`, `CON`, `NUL`, `COM1`...). Made absolute and normalized. An existing path resolves to its real path and must be a regular file: a FIFO blocks the reader and a device never ends. A missing path needs an existing parent directory, so a log that appears later can be opened. `logPath` returns the path as given (absolute, normalized, symbolic links kept) and the real path; `logFile` returns the real path |
| `settingsDirectory` | Absolute, normalized, created with POSIX `rwx------` where supported, resolved to its real path, must be a directory |
| `child` | A fixed name matching `[A-Za-z0-9._-]+`, resolved inside the directory |

A log viewer opens whatever readable file its user names, so a log path is
canonicalized and type-checked, not contained. `FileByteSource.open` validates
on every open and opens the real path; `path()` is the path as given and
`realPath()` the file opened.

## File access (`core.io`)

| Class | Contract |
|---|---|
| `FileByteSource` | `open` validates through `SafePaths.logFile`, then opens a `FileChannel` for reading. Positional reads only, thread-safe. A read after `close` throws `ClosedChannelException`. `identity()` holds the file key (null on Windows), creation and modified times, size and the CRC32C of the first 4 KiB. On Windows the JDK opens the file with delete sharing, so a writer can rename, truncate or delete it while it is open |
| `MemoryByteSource` | A `ByteSource` over a byte array in the heap, for pasted text. `path()` is null; the size never changes. A read after `close` throws `ClosedChannelException` |
| `BlockCache` | LRU of fixed-size blocks for random reads, keyed by source and block number; the lock is never held during I/O. A block read short at end of file is read again when a request reaches past it. `invalidateFrom` drops a block and everything after it; `invalidate(source)` drops a source's blocks, which `LogDocument.close` calls |
| `ReadBuffers` | Pool of little-endian direct buffers for sequential scans, reused while operations run. The first two are charged to `IO_BUFFERS` unconditionally; past the share, `tryAcquire` returns null and `acquire` waits. `trim` drops free buffers past the first two and releases their reservation; the indexer and the search engine call it when an operation ends |
| `AtomicFiles` | `write` puts the content in a temporary file in the target's directory (POSIX `rw-------`), then moves it over the target with `ATOMIC_MOVE`; on failure the target is unchanged and the temporary file deleted. Nothing goes to `java.io.tmpdir`. `write(Path, InputStream)` streams the content through a 64 KiB buffer. `writeOwnerOnly`, for a secret, also replaces the temporary file's ACL with one entry for its owner where there are ACLs but no POSIX permissions (Windows) |

Nothing is memory mapped: Java 21 releases a mapping only through garbage
collection, and on Windows a live mapping blocks the writer's truncation.

## Encoding (`core.text`)

`EncodingDetector.detect` reads the first 64 KiB and applies the first rule
that matches:

| Rule | Result |
|---|---|
| BOM `EF BB BF`, `FF FE 00 00`, `00 00 FE FF`, `FF FE`, `FE FF`, checked in that order | UTF-8, UTF-32LE, UTF-32BE, UTF-16LE, UTF-16BE; the first line starts after the BOM. A UTF-16LE file whose first char is U+0000 reads as UTF-32LE |
| At least 30% zero bytes at odd offsets and under 5% at even ones (or the reverse) | UTF-16LE (or UTF-16BE) |
| Valid UTF-8; a sequence cut off by the end of the sample counts as valid | UTF-8 |
| None | The fallback charset; ISO-8859-1 maps every byte to a char |

`EncodingDetector.forced` applies a charset the user chose and skips that
charset's own BOM; a BOM-sensing `UTF-16` or `UTF-32` follows the BOM's byte
order. For a forced UTF-16 charset only the 2-byte BOMs count.
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
| Sparse | A checkpoint (line, offset) at the first line 1,024 lines or 1 MiB past the previous one | 16 B per checkpoint, about 0.016 B per line for short lines | Reads forward from the nearest checkpoint through the `BlockCache`, or from the snapshot's last found line start when that lies between the checkpoint and the target, so consecutive lookups read each line once; at most 1 MiB before the target line starts. `lineAt` stops at the target offset, so an offset inside a long line reads only its distance from the checkpoint. A failed read throws `UncheckedIOException` |

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
- At its end an operation waits up to 5 s for scans still running. A scan that
  outlives the wait returns its buffer to the pool when it finishes; an atomic
  hand-off decides whether the coordinator or the scan returns it.
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
| `RESULTS` refuses the next chunk's matches | `COMPLETED` | `MEMORY`; the results up to the last whole chunk stay |
| `cancel()`, a read on a closed source, or closed workers | `CANCELED` | `NONE` |
| A regex that does not compile (`PatternSyntaxException`), a regex that overflows the stack on a line (`PatternTooComplexException` with the line), an I/O error, or a file shorter than its index | `FAILED` | `NONE` |

- A chunk reads into a pooled direct buffer of `chunkBytes` (`ReadBuffers`,
  from `IO_BUFFERS`). A line longer than `chunkBytes` reads into a heap
  buffer, and 3 bytes per line byte (the bytes and their decoded chars) are
  reserved from `IO_BUFFERS` until it is matched. A refused reservation trims
  the pool and retries once, and every search trims the pool when it ends, so
  a pool grown by an earlier parallel search does not hold the share.
- A long line that `IO_BUFFERS` refuses with nothing in flight, or that is
  longer than `SearchEngine.MAX_LINE_BYTES` (`Integer.MAX_VALUE - 8`), is
  skipped: it is not matched, not reported as a match under inverse, and the
  search continues after it. `SearchListener.finished` reports the count of
  skipped lines; `progressed` counts them as searched.
- A worker catches a `StackOverflowError` from a regex, as `(a|b)*c` raises
  on a 400,000-char line, and the search ends with
  `PatternTooComplexException`; the pool thread survives and nothing reaches
  the workers' failure handler. `LineFinder` (quick find) throws the same
  exception.
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
  encodings only); otherwise `StandardMatchers.wholeWord` wraps the regex in
  `(?<!\w)(?:...)(?!\w)`. A pattern that ends inside `\Q`, a `(?x)` comment,
  or a `\Q` inside such a comment (`Pattern` reads it as a quote) gets `\E`, a
  line feed or both before the closing parenthesis.
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

### Range search

A query with `SearchQuery.rangeEnd` set is a range search: a line matching
`expression` opens a range, and the first later line matching `rangeEnd`
closes it. The results are every line from the opening line through the
closing line; a range still open at the end of the scope runs to it.

- Workers match each line against both expressions (each with the query's
  case, whole-word and ANSI rules, not inverted) and return both hit lists.
  The coordinator walks them in chunk order and carries the open state across
  chunks, so the partition cannot change the result (`RangeSearchTest`
  compares every chunk size with a sequential scan).
- The opening line is not tested against the end, and a start line inside an
  open range neither opens nor closes anything.
- `inverse` returns the lines outside every range.
- `scope` limits the scan; `rangeOpen` states whether a range is open before
  the scope's first line. A skipped line inside an open range is in it.
- When a range search completes with no limit,
  `SearchListener.rangeOpenBeforeLast` reports whether a range was open before
  the scope's last line; a search from that line on (an auto-refresh) starts
  from it.

`RangeFinder.around` finds the range around one line: the nearest line at or
above it matching the start, through the first later line matching the end, or
the last line. It returns null when no start precedes the line or that range
closed above it. It reads blocks of whole lines (4,096 lines, 1 MiB) through
one heap buffer and matches the first 1 MiB of a longer line. It looks back
only to the nearest start, so where start lines repeat before an end, its range
begins later than the range search's.

### Result sets

`MutableLineSet` holds line numbers in buckets of 65,536 lines: a sorted
`char[]` of the low 16 bits up to 4,096 entries, else a 1,024-word bitmap; a
bitmap returns to an array at 2,048 entries or fewer. Counts of the entries
before each bucket make `rank` and `select` a binary search over buckets.

- Cost (`retainedBytes()`): 40 B per bucket (object and array header), plus
  2 B per array slot padded to 8 B or 8 KiB per bitmap, plus 20 B per slot of
  the bucket directory, which doubles as it grows. These are HotSpot sizes with
  compressed references (heaps under 32 GiB). For one match per bucket that
  is 74 B; `MutableLineSetTest` measures it against the heap.
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

`BoundedChars` is the `CharSequence` a user regex runs over outside a search:
`charAt` throws `BoundedChars.LimitReached` past the limit given to `reset`,
and `used()` counts the reads. `lineLimit(line)` is `8 * length + 65,536`.
`Highlighter` and the app's `HighlightSelector` use it, so a backtracking
pattern ends at the bound instead of stalling the reading thread.

## Timestamps (`core.time`)

`core.time` parses a timestamp at the start of each line and finds lines by
time. It depends on `core.io`, `core.text`, `core.index` and
`core.concurrent`, not on `core.model`. Every call blocks; callers run it on a
worker.

| Format id | Layout | Zone |
|---|---|---|
| `iso` | `yyyy-MM-dd`, space or `T`, `HH:mm:ss`, then optional fraction and `Z`, `+hh:mm` or `+hhmm` | The offset when present, else the document zone |
| `ymd-slash`, `ymd-dot` | `yyyy/MM/dd HH:mm:ss`, `yyyy.MM.dd HH:mm:ss` | Document zone |
| `dmy-dot` | `dd.MM.yyyy HH:mm:ss`, or a two-digit year | Document zone |
| `dmy-slash`, `mdy-slash` | `dd/MM/yyyy HH:mm:ss`, `MM/dd/yyyy HH:mm:ss` | Document zone |
| `dmony` | `dd MMM yyyy HH:mm:ss` | Document zone |
| `syslog` | `MMM d HH:mm:ss`, no year | Document zone |
| `clf` | `[dd/MMM/yyyy:HH:mm:ss +hhmm]` anywhere in the first 256 chars | The offset |
| `jul` | `MMM dd, yyyy h:mm:ss a` | Document zone |
| `epoch` | 10 digits of seconds or 13 of milliseconds, optional fraction, then a non-digit | UTC |

- `BuiltInFormat` scanners run at the line's first char and after one leading
  `[`, allocate nothing per line, and accept a fraction of 1-9 digits after
  `.` or `,`; month names are English in any case. A field out of range (month
  13, hour 24, Feb 29 of a non-leap year) is no timestamp.
- `TimestampParser.parse` returns epoch nanoseconds in a `long`
  (1677-09-21 to 2262-04-11) or `NONE`, and `fractionDigits()` for display.
  One parser per thread.
- `TimeContext` carries the document zone and a reference year and month:
  the file's modification time, or `current(zone)` for a document without
  one. `syslog` takes the reference year, or the year before for a month
  after the reference month. A two-digit year (`dmy-dot`, or `yy` in a user
  pattern) takes the century that puts the date nearest the reference month,
  the earlier on a tie (`nearestYear`).
- A local time takes `ZoneRules.getOffset`: in a DST gap and in an overlap,
  the offset before the transition, so the repeated hour of a fall-back maps
  onto its first occurrence and reads as time going backward. The offset is
  cached per local hour with no transition.
- `UserFormat(name, locate, pattern)` compiles both at construction and throws
  `IllegalArgumentException` for a regex without a group named `ts`, a
  pattern `DateTimeFormatter` refuses, or a value over 256 chars. Its `id()`
  is `user:` and the name. The regex runs with `find` over the first 256
  chars under `BoundedChars` (`8 * length + 65,536` reads); reaching the
  bound or a `StackOverflowError` is no timestamp. The group's text resolves
  to an instant when it carries an offset or zone, else a local date and
  time, else a month and day with the `syslog` year rule; a time alone is no
  timestamp. The formatter resolves `SMART`, so Feb 30 becomes Feb 28.
- `FormatDetector.detect` runs the built-ins over the first 200 non-empty lines
  of the encoding head and picks the one that parses the most, with at least
  3 lines and 20% of the sample; ties go to table order, except that
  `mdy-slash` wins a tie with `dmy-slash`, which parses more only when a
  first field is over 12. It returns null when nothing qualifies.

### Lookups

`TimeReader` (one per worker, a 64 KiB heap buffer; a lookup loads 8 KiB at a
time, a sequential scan the whole buffer) decodes the first 256
units of a line through `LineDecoder`, so UTF-16 and UTF-32 parse like UTF-8. A
line that does not parse continues the stamped line above it.

| Method | Reads | Bound |
|---|---|---|
| `next(offset, end, token)` | Forward through the `BlockCache`: the first stamp on a line that starts at or after `offset` and before `end` | 10,000 lines or 1 MiB from `offset`; past it, null |
| `at(lineStart, token)` | The line's own stamp, else backward to the nearest stamped line | 10,000 lines or 1 MiB above the line; past it, null |
| `TimeSearch.locate` | Bisection over byte offsets with `next`: about log2 of the size in probes, plus two for the first bracket | Each probe as `next` |
| `TimeSearch.locateLinear` | Every line in file order, from the source directly so a full pass does not evict the cache; `ScanProgress` every 4 MiB and at the end | None; the token cancels between lines |

- Both searches return the first stamp equal to the target, else the last
  stamp before it, else the first stamp, or null without stamps.
  `locateLinear` stops at the first stamp at or after the target.
- `locate` is exact only when stamps do not decrease in file order, and
  returns a `Bisection`. `consistent()` false means run `locateLinear`: a probe
  lay more than `JITTER_NANOS` (1 s) outside its bracket, or stopped at the
  `next` bound (`stoppedAtBound`) with a stamp read elsewhere. With no stamp
  read, such a probe counts as at or after the target, so a region without
  stamps reports none without a scan. The bracket starts as the first stamp
  and a stamp in the last 8 KiB, and narrows to the nearest stamps read under
  and at or over the target. A step back within 1 s counts as jitter and lands
  `locate` within the jitter window. Disorder no probe touches goes unseen, so
  run `TimeSamples` first.
- `TimeSamples.sample(reader, index, lineCount, count, token)` reads the stamp
  at or after `LineIndex.nearestStart` of `count` evenly spaced lines, first
  and last included. `nearestStart` returns the line itself in a full index
  and its checkpoint in a sparse one, so sampling reads no line start.
  `descents()` counts samples earlier than the stamped sample before them;
  any descent marks the document out of order. The caller reserves
  `bytesFor(count)` (`8 * count + 64`) before sampling. The overload taking
  `long[] lines` samples those lines; lines that snap to one start share one
  read and a line past the last reads the last.
- `TimeSpread.of(samples, lines, mode)` gives one level per pixel row from a
  stamp at each row boundary: `floor(log2(ratio))` from 0 to 4, unshaded
  below twice the average. `PAUSES` compares the row's time with the average
  row's; `ACTIVITY` compares its lines per second with the whole span's, and
  a row with lines and no elapsed time takes level 4. A row whose time runs
  backward is `DISORDER`. Backward rows, rows without lines and rows missing a
  stamp stay out of the averages.

## File monitoring (`core.watch`)

`FileMonitor.watch(path, listener)` starts a `qlogg-follow-` thread that calls
`MonitorListener.possiblyChanged` at once, then every `pollMillis` (1,000 by
default) and, with `useWatchService`, on a parent-directory event for the file
name, at most once per 50 ms. The poll stays on with directory events, because
a `WatchService` misses events on network mounts and polls on macOS. One
`WatchService` serves every directory; its dispatcher thread ends when no
directory is registered or the workers close. Registration runs on the watch's
own thread; a directory that cannot be registered is polled only. `cancel`
ends the watch within one check. An exception from the listener goes to the
thread's uncaught-exception handler, once per run of failing checks, and the
watch continues. `setSettings` replaces the settings: the
interval applies from each watch's next wait, directory events to watches
started afterwards.

`ChangeDetector.classify(baseline, path)` compares the file at the path,
following a symbolic link, with
what was indexed (`Baseline`: identity at open, covered bytes, head digest,
tail digest and its modification time), in this order. While indexing runs,
the covered bytes are at least the size at open and the size indexing reads
to, so a check before the first progress event does not report the indexed
file as appended.
`LogDocument` passes the tail digest only when it ends at the covered bytes;
otherwise both tail rows are skipped for that check, because an indexing pass
publishes its last snapshot before it records the new tail.

| Observation | Result |
|---|---|
| No regular file | `MISSING` |
| Another file key; where there is none (Windows), another creation time | `REPLACED` |
| Size below the covered bytes | `TRUNCATED` |
| CRC32C of the head bytes differs: min(4 KiB, size at open), extended over indexed bytes up to 4 KiB | `REPLACED` |
| Size above the covered bytes, CRC32C of the last 4 KiB before the covered end differs | `REPLACED` |
| Size above the covered bytes | `APPENDED` |
| Same size, newer modification time, CRC32C of the last 4 KiB differs | `MODIFIED` |
| Otherwise | `UNCHANGED` |

Creation time decides only without a file key: JDK 21 on Linux reports the
modification time as the creation time. On Windows a file recreated under the
same name within 15 s can inherit the old creation time (NTFS tunneling,
assumed from the Windows documentation), so the head digest decides there. A
check that sees the new file before its first write lands reports `TRUNCATED`
there, which reopens the path as `REPLACED` does.
The growth row with the tail digest catches a copy-truncate rotation that
regrows past the covered bytes between two checks: the file key stays the same,
and the head digest of a file opened short covers only the bytes recorded so
far. It costs one 4 KiB read per growth check.
The detector opens the file only to read a digest and closes it at once.

## Documents (`core.model`)

`LogDocument` is one open file. `open(path, forcedCharset, services)` validates
the path (`FileByteSource.open`), reads the first 64 KiB, detects the encoding
and the timestamp format from those bytes (`FormatDetector`; `timeFormat()`,
null for none) and creates the line index; it blocks, so it runs on a worker.
`open(..., false)` skips the format detection, for a file whose format the
owner remembers. `newTimeReader(parser)` is a `TimeReader` over the document
through the shared `BlockCache`, and `openedModifiedMillis()` the modification
time at open, for `TimeContext`.
`startIndexing()` starts the `Indexer` and returns. `index()` and `status()`
read published state without I/O. `DocumentServices` carries the shared
`Workers`, `Indexer`, `SearchEngine`, `BlockCache`, `MemoryBudget` and
fallback charset. `marks()` is the document's `MutableLineSet` of marked
lines and `markComments()` its `MarkComments`, both charged to `RESULTS`;
`close` empties them. `path()` is the path as
given, absolute and normalized; `realPath()` is the file opened; `name()` is
the file name of `path()`. A symbolic link stays a link: the watch follows it,
a retarget reads as `REPLACED`, and the owner's reopen opens the new target.

`ofText(name, text, maxBytes, services)` creates a document over pasted text:
the UTF-8 bytes are held in a `MemoryByteSource` and charged to `PASTED` until
`close`; its timestamp format is detected as for a file. Text over `maxBytes` or the share throws `PastedTextException` with
the size and the limit. Such a document reports `inMemory()`, has a null
`path()`, and ignores `startWatching`.

Every operation on the file holds an `OperationGate` entry while it runs:
indexing, each watch check, and every read, search and scan a controller
starts (`enter()`, `exit()`). `close(bound)` closes the gate, so `enter`
fails, cancels indexing and the watch, waits up to the bound for every entry
to exit and for the watch to end, then releases the index reservation, closes
the file, drops its blocks from the `BlockCache` and calls
`DocumentListener.closed()`. Callers cancel their own operations before
closing. `close` returns false when an operation outlived
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
  modification time, then the CRC32C of the indexed tail, and extends the
  head digest over the indexed bytes until it covers 4 KiB.

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
background opacity 0-255, color variance 0-50, enabled and a `RuleTrigger`
(`NONE` by default). `HighlightSet` is a
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
  chars of a line (`BoundedChars`), or overflows the regex stack, is abandoned
  for that line,
  and a `LineMatcher` stops coloring after 2^26 chars, so a backtracking
  pattern bounds a read instead of stalling it.
- Measured once on this host, one thread: four rules over a 1 MiB line take
  15 ms; 60 rows of 80 chars take 0.08 ms.

### Keyword triggers

`RuleTrigger` holds a rule's actions on appended lines: stop follow, set a
mark with a comment template (at most 1,000 chars), and quiet.
`Triggers.of(rules)` takes the rules highest priority first and keeps the
enabled ones with a pattern and an action; a pattern that does not compile is
left out. Every kept rule that matches a line acts, whatever its color
precedence. A rule matches with `find` under the highlighter's bound;
reaching it or a stack overflow counts as no match.

`Triggers.scan` reads at most `BATCH_LINES` (4,096) lines and returns a
`Batch`: lines scanned, lines no quiet rule matched, the first stop-follow
rule and its line, marks added, refusals and template failures. It applies the
batch itself:

- Marks go in with one `addSorted`, all or none against `RESULTS`. A refusal
  adds no mark and no comment.
- A marked line keeps an existing comment. Otherwise it takes the expansion of
  the first marking rule with a template, through `MarkComments.put`; a
  comment past `MAX_COMMENTS` or the share is a refusal and the mark stays.
- Quiet lines go into the caller's `MutableLineSet`.
- A template that fails expands to its own text and is reported once per
  `Triggers` instance.

`CommentTemplate.parse` compiles a template once. `%L`, `%F`, `%P`, `%N`,
`%M` and `%E` expand to the 1-based line, the path, the directory, the file
name, the name without and the extension without the dot (path placeholders
are empty without a file). `{regex}{replacement}` inserts the line with every
match replaced (`Matcher.appendReplacement` syntax); braces nest and `\`
escapes, so `{a{2}}` is one regex, and a brace that does not open a complete
pair is text. `expand` throws `TemplateException` for a regex that does not
compile, reaches `8 * length + 65,536` reads, overflows the stack, or names a
missing group. Output stops at 1,024 chars; `MarkComments.clean` cuts it to
200.

### Overview bins and mark toggles

`OverviewBins.compute(matches, marks, lines, token)` counts the lines of each
set per pixel row as differences of `rank` calls: a few lookups per row,
whatever the set sizes. `lines` holds each pixel row's first line, then the
line after the last row, so a row covers any span. `lineAt(y)` is `floor(y *
lineCount / height)`, exact for every `long` line count; `rowOf(line)` is its
inverse. Cost: 8 bytes per pixel row plus 64.

`MarkToggle.run(marks, rows, firstLine, count, token, progress)` marks the
rows' lines unless all are marked, then unmarks them, in batches of 4,096
under one write lock each (`MutableLineSet.addSorted`, `removeSorted`). A
contiguous range checks "all marked" with two `rank` calls. Marking reserves
from `RESULTS` and stops at the first batch the share refuses; cancel stops
between batches. Lines changed before either stay changed. The overload with a
`MarkComments` removes the comments of the lines it unmarks.

### Mark comments

`MarkComments` maps a marked line to one single-line comment; every method
holds its monitor, so the gutter reads it on the UI thread while a toggle
removes comments on a worker. `clean` replaces control chars (C0, DEL, C1,
U+2028, U+2029) with spaces, strips the ends and cuts to `MAX_LENGTH` (200)
chars. `put` with text that cleans to empty removes the comment. Each comment
reserves 96 bytes plus 2 per char from `RESULTS`; `put` refuses a comment the
share cannot hold or one past `MAX_COMMENTS` (1,000 per document) and changes
nothing. The class does not check marks: the owner removes a comment with its
mark, and `addAll` skips comments on unmarked lines.

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
- `setContext(before, after, lineCount)` lists each match with the lines
  around it, through `ContextRows` (below); `rows()` is the set the
  `MATCHES` and `MARKS_AND_MATCHES` modes list in place of the matches.
  `MARKS` lists no context. `isContext(line)` and `startsGroup(line)` tell
  the painter which rows to shade and where a gap lies above a row.
- `rowOf(line)` returns the row of a line or of the next listed line.
- The class is confined to one thread. `cursor()` returns a thread-safe
  `RowCursor` (`next`, `previous` over the sets the mode lists) for a worker; copy and
  quick find walk rows through it. `RowCursor.all(lineCount)` walks every
  line.

`ContextRows` is a `LineSet` over the matches widened by `before` and `after`
lines, windows merged and cut to the live line count, so the last match's
context extends as the file grows. It stores no row: match `j` adds
`min(before + after + 1, m(j) - m(j - 1))` lines, and a checkpoint of that sum
every 64 matches (8 B, charged to `RESULTS`) lets `rank` and `select` walk at
most 64 matches. `contains`, `ceiling` and `floor` take one match lookup.

- An instance covers the matches its build saw. `extend` builds the next one
  on a worker from where the last stopped, sharing and growing the checkpoint
  array, and starts over when the set changed below the last covered
  match (`stale()` compares the count and the last covered line). The view
  shows the installed instance until `installContext` replaces it.
- A refused checkpoint stops the build at the matches covered so far
  (`complete()` false); the view lists those and does not retry.
- `FilteredView` releases the reservation when it replaces or drops an
  instance; `reservedBeyond` is what a dropped build charged itself.

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
