# qlogg-core tests

`./mvnw -pl qlogg-core verify` runs them; none needs a display. Module
overview: [qlogg-core](README.md).

## Patterns

| Technique | Use |
|---|---|
| Reference oracles | Each index, search and time lookup is compared with an independent computation: `ReferenceIndex` (a byte-by-byte scan), `String` and `Pattern` on decoded lines, a `TreeSet`, `BigInteger` arithmetic, an in-memory time oracle, a sequential range scan |
| Small budgets | `MemoryBudget.of(bytes)` forces each degraded path: the sparse index, the `MEMORY` stop, a skipped line, a partial snapshot, a refused charge |
| Gates | `GatedSource` blocks one read and a gated `MatcherFactory` signals once matching has started, so cancellation and close land at a known point without sleeps |
| Generated large inputs | Sparse files (holes read as zeros, little disk used) and `GeneratedSource`, a `ByteSource` that computes fixed-width lines from their offset, reach offsets past 2^31 without a multi-gigabyte fixture. The large-file tests run in the default build |
| Chunk and block sweeps | Block sizes of 8 to 64 bytes and chunks of 1 line or 1 byte put CRLF pairs, UTF-16 units and matches on every boundary |

## Line index

| Class | Covers |
|---|---|
| `IndexerTest` | Every `lineStart` and `lineEnd`, and `lineAt` at every offset for content up to 20,000 bytes (above that at each line's first, second and last byte and inside its LF, since each sparse lookup reads the file). `lineStart` backward and in a strided order, so a sparse lookup that started from a remembered line past its target fails. 4 MiB blocks with one 64 MiB line. The sparse fallback (150,000 bytes refuses the first full block) and the failure when sparse mode does not fit (3,000 bytes). A sparse `lineAt` 2 MiB into a 16 MiB line reads, through a counting `ByteSource`, at most that distance plus two cache blocks; a UTF-16 sparse index stops on a unit. Cancel and close at a known block. An indexer created to keep its pool holding it past the minimum after an operation, and `trim()` returning it |
| `GrowableLineIndexTest` | `retainedBytes()` against the allocation formula for full mode at widths 4-16 and for sparse mode, and equal to the `LINE_INDEX` reservation |
| `LargeFileTest` | A 2.2 GB sparse file, offsets past 2^31 |

Regression cases:

- `IndexerTest.aScanOutlivingTheDrainBoundReturnsItsBuffer` holds every CPU
  thread past the 5 s end-of-operation wait, so both scans of a two-buffer
  indexer start after their operation ended. The next operation must
  complete: a lost buffer left it waiting.
- `IndexerTest.aFailedReadReturnsItsBuffer`: failed reads past the pool size
  must leave the next pass a buffer. A lost buffer left the pass waiting at 0%.
- A sparse `lineAt` inside a long line read to the end of the line.

## Search

| Class | Covers |
|---|---|
| `SearchEngineTest` | A generated corpus (LF and CRLF lines, empty lines, a 735-byte line, no final newline) against `String` and `Pattern`, at chunk settings from 1 line or 1 byte per chunk (every line through the long-line path) to the defaults; the same queries on UTF-8 with and without a BOM and on UTF-16LE and BE with a BOM. Catastrophic backtracking canceled within 5 s. The `MEMORY` stop (100,000 bytes holds two dense buckets) and a skipped line (1,000,000 bytes refuses a 200,000-byte line's buffer; the lines on both sides match and 1 is reported skipped). `aSearchKeepsItsGrownPoolWhileTheShareHoldsAnother` gates the workers until the pool reaches `maxChunksInFlight` buffers; with a share holding another full pool, the search must end with all of them reserved |
| `StandardMatchersTest` | Byte search against a quoted regex and an independent reference on random lines in five charsets, case folding and whole word on and off. The documented differences: U+212A folds only in a regex, a malformed UTF-8 byte is U+FFFD only to a regex, an unaligned UTF-16 byte match does not count. A case-sensitive regex without metacharacters runs as bytes and matches what `Pattern` finds. A regex with a literal prefix runs on random UTF-8 (with malformed bytes), Latin-1, windows-1252 and UTF-16 lines in a little-endian direct buffer, as search chunks are, and must match what `Pattern` finds in the decoded text; `requiredPrefix` cases cover quantifiers, `\|`, escapes and inline flags |
| `ByteScanTest` | The word-wide byte and byte-pair scans against a byte loop at random ranges in heap and direct buffers of both byte orders, over bytes next to the case bit, the sign bit and zero |
| `LineTextTest` | Lines longer than the 8 KiB window decoded from a direct buffer, with sequences and malformed bytes split at window edges, and UTF-16 lines with surrogate pairs, lone surrogates, U+FFFE and odd lengths; each must equal a one-shot heap decode |
| `RangeSearchTest` | Range searches against a sequential scan at chunk sizes that cut ranges at every partition; a file cut below its index fails the search and `RangeFinder` with an `IOException` |
| `MutableLineSetTest` | Random adds, toggles, batches and `removeFrom` against a `TreeSet`: `select` and `rank` at every member; `ceiling`, `floor`, `contains` and `rank` around every member and bucket edge, over 4 buckets and over 2^40 lines. The array-to-bitmap switch at 4,097 entries and the return at 2,048; `retainedBytes()` against the formula and equal to the `RESULTS` reservation |
| `LargeFileSearchTest` | A 2.2 GB sparse file of 1 MiB lines with matches at the first line, just past 2^31 bytes and in the partial last line; a second line longer than `MAX_LINE_BYTES` skipped with the lines after it matched |
| `FolderSearchTest` | The walk: name globs, path order, subfolders, exclude globs, a NUL byte listed as not text and a UTF-16LE file with a BOM searched. A 64 MiB file under a 32 MiB budget searched with the match count of a line-by-line reference. The `RESULTS` share of a 4 MiB budget stopping the search with the lines listed so far and no later file, and a refusal the listener frees by `resultsRefused` retried to a complete listing. Cancel while a runaway regex runs ending within 1 s with every reservation returned. POSIX only: an unreadable file and folder and a dangling link listed, a linked file searched, a linked folder not entered, a link to an ancestor walked once. A missing folder, a file given as the folder and a regex that does not compile failing the search |
| `SearchLifecycleTest` | 25 rounds of open, index, search and close with cancels and closes at random points; then no thread survives `Workers.close()`, every file is closed, and `LINE_INDEX` and `RESULTS` are back to 0 |

- Catastrophic backtracking uses `(.*a){25}x` on 60 `a`s. Java's regex
  memoizes simpler forms such as `(a|aa)+b`, which return at once and test
  nothing.
- `(a|b)*c` on a 400,000-char line overflows the regex stack. The search must
  end `FAILED` with `PatternTooComplexException` naming the line and an empty
  failure handler, and the next search on the engine must run;
  `LineFinderTest` checks quick find the same way.
- Whole word on a regex ending inside `\Q`, inside a `(?x)` comment, and
  inside a `\Q` in such a comment must match as the pattern alone does.
  Regression: the plain wrapper failed these with "Unclosed group".
- `aLongLineStillSearchesAfterThePoolGrew` searches a 100,000-byte line, then
  a 400,000-line file five times, then the line again, with a 600,000-byte
  `IO_BUFFERS` share. Each search must end with two buffers reserved.
  Regression: a pool that kept its peak reservation stopped the last search
  with `MEMORY`.
- `MutableLineSetTest` also checks one match in each of 100,000 buckets is
  charged at least 60% and at most 125% of the heap the set holds after a full
  collection. The heap reading varies by host, so the exact charge is pinned
  by the formula cases, not the heap.

## Documents and viewport

| Class | Covers |
|---|---|
| `LogDocumentTest` | Real files: tab expansion, CR handling, column windows, UTF-16 by BOM, the 1 MiB line cap, a line read past 2^31 bytes in a sparse file, close releasing `LINE_INDEX`, close during indexing, close dropping the document's `BlockCache` blocks, a read after close throwing `ClosedChannelException`, timestamp format detection (ISO, UTF-16 by BOM, none, skipped on request) and `newTimeReader` |
| `InMemoryDocumentTest` | Pasted text, including its format detection |
| `BlockCacheTest` | `concurrentMissesReadTheirOwnBytesWhileArraysAreReused`: four threads of random two-block reads through a four-block cache, every byte checked; fails when a hit is copied after the lock is released, because the array is refilled for another block in between |
| `LogDocumentFollowTest` | Real files with a 20 ms poll: appended lines indexed, including a last line that grew; truncation reported once; rename-rotation and delete-then-recreate (`MISSING`, then `REPLACED`) with the old content still readable; stop loading, then a rewrite grown past the indexed bytes reading as `REPLACED`; close keeping the file open until an admitted operation exits. Failed reopen, symbolic links and `keepClosed`, below |
| `ReopeningByteSourceTest` | Appended bytes read; a rename over the path lands and the next read fails; a rewritten head, a rewritten or truncated tail, a replaced file and a link at the real path throw `FileChangedException`; a deleted file throws `NoSuchFileException`; a retargeted link keeps reading the file opened |
| `FilteredViewTest` | Rows in each `Visibility` over sets past one bucket and at line 2^40: `sourceLine` and `rowOf` agree, the union follows mark toggles and a growing result set once a snapshot is installed, a stale snapshot stays in place and one built for other matches is dropped, `sourceLines` and the cursor agree with `select`, the snapshot is charged and released, a share too small gives a partial snapshot |
| `HighlighterTest` | Spans per rule: overlap (the later rule wins), whole line dropping earlier spans, capture groups including an unmatched group, case folding and literal mode, opacity, disabled and empty rules, variance stable per string, tab columns and window clipping, the 256-span cap and its cost, a backtracking pattern abandoned within the bound, a pattern that does not compile |
| `BoundedCharsTest` | The limit, `used()` and `reset`; `find` taking the limit and a stack overflow as no match; a canceled token ending a run |
| `OverviewBinsTest` | `lineAt` against `BigInteger` up to `Long.MAX_VALUE` lines, `rowOf` past 2^31 lines, bins for dense, sparse, fewer-lines-than-pixels and uneven-span cases |
| `MarkToggleTest` | A contiguous range marked and unmarked, listed rows, unmarking the marks view while walking it, cancel from the progress callback, the results share stop over 2^32 lines |
| `LineFinderTest` | Quick find over a tab and a CRLF line: columns after tab expansion, forward and backward from a column, no match either way, the CR excluded, rows limited by a cursor, reads through `readRows`, a canceled token |
| `ViewportTest` | The scroll bar mapping at line counts up to 2^52: exact endpoints, monotonic both ways, a round trip within one scroll unit, the 2^30 boundary, clamping |

`LogDocumentFollowTest` cases:

- After `reopenFailed()` the next check reports the change again. With no
  file at the path it reports `MISSING` once and nothing more over five checks
  of a second watch, then `REPLACED`. A check that finds the file unchanged or
  grown ends the wait. Regression: one failed reopen stopped the checks for
  good.
- A symbolic link retargeted by an atomic rename reads as `REPLACED`, and a
  reopen of `path()` reads the new target. The same holds for a new target
  with the old creation time that starts with the old bytes, which on Windows
  only the real path tells apart. Growth of a link's target stays `APPENDED`.
  These tests rename through `Renames.replace`, a bounded retry, because
  Windows refuses the rename while a check has the link open.
- With `keepClosed`, a rename-rotation and a rename over the name read as
  `REPLACED` with the old content unreadable, a delete fails reads until the
  file returns, and growth stays `APPENDED`. A tail rewritten under the same
  size and modification time, which the check alone cannot see, is reported
  through the failed read.

`ReopeningByteSourceTest` regression cases:

- A check during the first indexing, held before its first progress event by
  CPU workers blocked on a latch, must classify the file as `UNCHANGED`. It
  reported `APPENDED`, which started a second indexing pass at once and left a
  go to line past the end waiting for it.
- `fastGrowthIsOnlyAppended` appends 5,000-line batches every 20 ms for 3 s
  and accepts only `APPENDED` and `UNCHANGED`. A check during an indexing pass
  compared the file's end with the previous pass's tail digest and reported
  `MODIFIED`, which stopped following. It is a stress test and fails only
  intermittently without the fix.
- `aRewriteAfterStopLoadingIsNotAnAppend` stops the first pass from its first
  progress event, held past 100 ms by a task that takes the single CPU thread
  for 20 ms before each 64 KiB block. A rewrite with the same first 4 KiB, grown
  past the indexed bytes, must read as `REPLACED`: the canceled pass recorded
  no tail, and the check reported `APPENDED` with the old lines showing new
  bytes.
- `truncateAndRegrowBetweenChecksIsReplaced` opens an empty file, appends,
  then truncates and writes longer content through one channel. It must report
  `REPLACED` (or `TRUNCATED` when a check lands between the two calls) and no
  `APPENDED`: the new bytes were indexed after the old ones.

The rename on Windows lands only because the file is opened with delete
sharing and nothing is mapped.

`HighlighterTest` backtracking uses `(.*a){12}$` on 32 `a`s and `!`: JDK 21
returns `(a+)+$` at once and leaves the bound untested. With the line bound
removed, the matcher's 2^26-char budget runs out and the next rule's span is
missing.

## Folding, line markers and ANSI colors

| Class | Covers |
|---|---|
| `FoldMapTest` | `rowOf` and `lineAt` against hand-computed rows on both sides of two entries, refusal of overlapping and one-line entries, cursor snapshots, the `FOLDS` charge up to a full share and back to 0 |
| `ViewFeaturesReadTest` | `FoldClassifier` over a stack trace from inside an entry and with an entry-start rule, a backtracking rule ending on cancel, a continuation rule overflowing the stack on a 400,000-char line finding no match, an entry-start rule that reaches its bound or overflows starting an entry; block columns through a tab; window, copy, block and marked-row edges after a surrogate pair; whitespace markers with CRLF, a lone CR and a last line without LF; hidden ANSI colors in reads and copy |
| `AnsiSearchTest` | A literal search with `hideAnsi` matches across removed sequences, which a byte search would miss |

## Timestamps

| Class | Covers |
|---|---|
| `TimeSearchTest` | `locate` and `locateLinear` against an in-memory oracle for every millisecond target across generated files with equal stamps and continuation lines, from before the first stamp to after the last; `bisectionOverThreeGigabytes` over `GeneratedSource`; descents and jitter; bracket cases |
| `TimeSpreadTest` | Samples built directly (`TimeSamples.of`), so each level boundary, the clamp at 16 and the rows left out of the averages have exact expected values |

- Two concatenated days must give one descent in 256 samples. A +-5 ms jitter
  must land bisection within 11 ms of the target (two jitter widths plus one
  step).
- The bracket tests place a block at a known probe: 34-byte entries put the
  first probes on lines 500, 250 and 750, also past 2^31 with a shifted block
  in `GeneratedSource`. `aBlockThatFitsItsBracketLandsThereAndStaysConsistent`
  pins what the check leaves unseen; a change that closes the gap updates it.
- Zone cases use `America/New_York` 2025 dates for the DST overlap and gap.
- Two-digit years are checked against a 2026-03 reference on both sides of the
  50-year pivot (`76` in April is 1976, in February 2076, in March a tie that
  takes 1976) and against the current date.
- A `StackOverflowError` in a user format is provoked by a `CharSequence`
  whose `charAt` throws it: the 256-char prefix is too short to overflow the
  regex stack on every platform.

## Threads and file monitoring

| Class | Covers |
|---|---|
| `WorkersTest` | `noTaskRunsAfterCloseReturns` races `tryStart` against `close` 50 times |
| `OperationGateTest` | Admission, refusal after close, the idle wait |
| `ChangeDetectorTest` | Growth, truncation, a renamed file replaced under the name, a rewritten head, a same-size edit past the head digest, a touch with the same bytes, a missing path or a directory |
| `FileMonitorTest` | The poll and the cancel; a directory event waking a watch long before its poll interval (skipped on macOS, whose `WatchService` polls); a throwing listener |

- `noTaskRunsAfterCloseReturns`: without the second `closed` check in `start`,
  a thread started after `close` took its snapshot of the live set. The test
  polls `liveThreads()` after close, because `ConcurrentHashMap.size` lags a
  removal the set already shows.
- Two `ChangeDetectorTest` growth cases must be `REPLACED`, not `APPENDED`: a
  file opened empty, then written, truncated and regrown longer; and a longer
  rewrite past an unchanged 4 KiB head.
- A `FileMonitorTest` listener that throws on checks 1, 2 and 4 must still be
  called a sixth time, and the handler must receive checks 1 and 4 only.
  Regression: an exception ended the watch thread silently, and with it
  following.

## Atomic writes

| Class | Covers |
|---|---|
| `AtomicFilesTest` | Write and replace leaving no temporary file; a failed move or read keeping the target. The Windows move retry runs on every host through `AtomicFiles.tryMove` with a `Move` that throws: access denied and a sharing-violation `FileSystemException` tried again, a move refused every time throwing after the last attempt, `NoSuchFileException` tried to the last attempt and a plain `IOException` thrown at once. `writeUserFile`: a replaced file keeping its mode and a new one taking the default (POSIX); a symbolic link, dangling or not, staying a link for both writes; written in place for a hard-linked file (`unix` view) and in a folder that is not writable (POSIX, not root); a read-only file refused unchanged |

## Operating system seam

| Class | Covers |
|---|---|
| `OsTraitsTest` | Each `os.name` form selecting one class, every answer of each class from a fixed name, `current()` matching this JVM, `EnvPaths.absolute` refusing null, empty, relative and NUL values |
| `SafePathsTest` | `deviceNamesAreRefusedOnWindowsOnly` characterizes the refusal on the host; `deviceFormsFollowTheSystem` checks it for each system through `OsTraits.of` |

## Platform assumptions

| Case | Skips |
|---|---|
| `SafePathsTest` FIFO (`mkfifo`) | Without POSIX attributes or `mkfifo` |
| `SafePathsTest` `/dev/null` | Where the device is absent |
| `SafePathsTest` symbolic links | On Windows |
| `LogDocumentFollowTest`, `ChangeDetectorTest` symbolic links | On Windows without `SeCreateSymbolicLinkPrivilege` |
| Held lock's link case | Where the file system has no file key |
| `TestkitTest` read-only directory (`TempTrees.delete` rethrows after its bound) | Without POSIX attributes, or when the user can write to it anyway (root) |
