# qlogg-core tests

`./mvnw -pl qlogg-core verify` runs them; none needs a display.

## Line index

- `IndexerTest` compares each index with `ReferenceIndex`, a byte-by-byte scan
  of the same content: every `lineStart` and `lineEnd`, and `lineAt` at every
  offset for content up to 20,000 bytes (at each line's first, second and last
  byte and inside its LF above that, since each sparse lookup reads the file).
  `lineStart` is also checked backward and in a strided order, so a sparse
  lookup that started from a remembered line past its target fails.
- Block sizes of 8 to 64 bytes put CRLF pairs and UTF-16 units on read-block
  boundaries; 4 MiB blocks cover the default path, including one 64 MiB line.
- Budgets built with `MemoryBudget.of` force the sparse fallback (150,000
  bytes refuses the first full block) and the failure when even sparse mode
  does not fit (3,000 bytes).
- `GatedSource` blocks one read until the test releases it, so cancellation and
  closing the source happen at a known block without sleeps.
- `GrowableLineIndexTest` checks `retainedBytes()` against the allocation
  formula for full mode at widths 4-16 and for sparse mode, and that it equals
  the `LINE_INDEX` reservation.
- A sparse `lineAt` 2 MiB into a 16 MiB line reads, through a counting
  `ByteSource`, at most that distance plus two cache blocks; it read to the
  end of the line. A UTF-16 sparse index checks that the stop falls on a unit.
- `aScanOutlivingTheDrainBoundReturnsItsBuffer` holds every CPU thread past
  the 5 s end-of-operation wait, so both scans of a two-buffer indexer start
  after their operation ended. The next operation must complete: a lost
  buffer left it waiting.
- `LargeFileTest` writes a 2.2 GB sparse file (holes read as zeros, so little
  disk is used) and checks offsets past 2^31. It runs in the default build.

## Search

- `SearchEngineTest` compares each search of a generated corpus (LF and CRLF
  lines, empty lines, a 735-byte line, no final newline) with a reference
  computed on the decoded lines by `String` and `Pattern`. Chunk settings run
  from 1 line or 1 byte per chunk (every line its own chunk, through the long
  line path) to the defaults, so matches fall on the first and last line of a
  chunk and on lines longer than `chunkBytes`.
- The same queries run on UTF-8 with and without a BOM and on UTF-16LE and BE
  with a BOM, with CRLF and no final newline.
- `StandardMatchersTest` checks byte search against a quoted regex and an
  independent reference on random lines in five charsets, with case folding
  and whole word on and off. Cases name the documented differences: U+212A
  folds only in a regex, a malformed UTF-8 byte is U+FFFD only to a regex, and
  an unaligned UTF-16 byte match does not count. A case-sensitive regex without
  metacharacters runs as bytes and matches what `Pattern` finds, with and
  without whole word. Whole word on a regex ending inside `\Q`, inside a
  `(?x)` comment, and inside a `\Q` in such a comment must match as the
  pattern alone does; the plain wrapper failed these with "Unclosed group".
- Catastrophic backtracking uses `(.*a){25}x` on 60 `a`s. Java's regex
  memoizes simpler forms such as `(a|aa)+b`, which then return at once and
  test nothing. A gated `MatcherFactory` signals once matching has started,
  so the cancel lands inside the regex; the search must end within 5 s.
- Budgets built with `MemoryBudget.of` force the `MEMORY` stop and a skipped
  line: 100,000 bytes holds two dense result buckets, and 1,000,000 bytes
  refuses a 200,000-byte line's buffer. The search must match the lines on
  both sides of the refused line and report 1 skipped.
- `RangeSearchTest` compares range searches with a sequential scan at chunk
  sizes that cut ranges at every partition. A file cut below its index must
  fail the search and `RangeFinder` with an `IOException`.
- `aLongLineStillSearchesAfterThePoolGrew` searches a 100,000-byte line, then
  a 400,000-line file five times, then the line again, with a 600,000-byte
  `IO_BUFFERS` share. Each search must end with two buffers reserved; a pool
  that kept its peak reservation stopped the last search with `MEMORY`.
- `(a|b)*c` on a 400,000-char line overflows the regex stack. The search must
  end `FAILED` with `PatternTooComplexException` naming the line and an empty
  failure handler, and the next search on the engine must run;
  `LineFinderTest` checks quick find the same way.
- `MutableLineSetTest` compares random adds, toggles, batches and `removeFrom`
  with a `TreeSet`: `select` and `rank` at every member, `ceiling`, `floor`,
  `contains` and `rank` around every member and bucket edge, over 4 buckets
  and over 2^40 lines. It checks the array-to-bitmap switch at 4,097 entries,
  the return at 2,048, `retainedBytes()` against the formula, and that it
  equals the `RESULTS` reservation. One match in each of 100,000 buckets must
  be charged at least 60% of the heap the set holds after a full collection
  and at most 125%; the 24 B per-bucket charge covered 3.2 MB of 7.4 MB
  measured. The reading varies by host (7.42 MB here, 9.5-10.0 MB on the CI
  runners), so the exact charge is pinned by the formula cases, not the heap.
- `LargeFileSearchTest` searches a 2.2 GB sparse file of 1 MiB lines, with
  matches at the first line, just past 2^31 bytes and in the partial last
  line, and a sparse file whose second line is longer than `MAX_LINE_BYTES`:
  the search skips that line and matches the lines after it. It runs in the
  default build.
- `SearchLifecycleTest` opens, indexes, searches and closes a document 25
  times, canceling searches and closing documents at random points, then
  checks that no thread survives `Workers.close()`, every file is closed, and
  the `LINE_INDEX` and `RESULTS` reservations are back to 0.

## Documents and viewport

- `LogDocumentTest` opens real files: tab expansion, CR handling, column
  windows, UTF-16 by BOM, the 1 MiB line cap, a line read past 2^31 bytes in
  a sparse file, close releasing the `LINE_INDEX` reservation, close during
  indexing, close dropping the document's `BlockCache` blocks, a read after
  close throwing `ClosedChannelException`, timestamp format detection (ISO,
  UTF-16 by BOM, none, skipped on request) and `newTimeReader`.
  `InMemoryDocumentTest` also detects the format of pasted text.
- `LogDocumentFollowTest` watches real files with a 20 ms poll: appended lines
  indexed, including a last line that grew; truncation reported once;
  rename-rotation and delete-then-recreate (`MISSING`, then `REPLACED`) while
  the document holds the file open, with the old content still readable; stop
  loading; and close keeping the file open until an admitted operation exits.
  A symbolic link retargeted by an atomic rename must read as `REPLACED`, and
  a reopen of `path()` must read the new target (POSIX only).
  A check during the first indexing, held before its first progress event by
  CPU workers blocked on a latch, must classify the file as `UNCHANGED`: it
  reported `APPENDED`, which started a second indexing pass at once and left a
  go to line past the end waiting for it.
  `fastGrowthIsOnlyAppended` appends 5,000-line batches every 20 ms for 3 s
  and accepts only `APPENDED` and `UNCHANGED`: a check during an indexing
  pass compared the file's end with the previous pass's tail digest and
  reported `MODIFIED`, which stopped following. It is a stress test: it failed
  in 2 of 10 runs without the fix and in 0 of 10 with it.
  `truncateAndRegrowBetweenChecksIsReplaced` opens an empty file, appends,
  then truncates and writes longer content through one channel. It must report
  `REPLACED` (or `TRUNCATED` when a check lands between the two calls) and no
  `APPENDED`: the new bytes were indexed after the old ones.
  The writer renames and deletes an open file, which Windows allows only
  because the file is opened with delete sharing and nothing is mapped.
- `FilteredViewTest` maps rows in each `Visibility` over sets past one bucket
  and at line 2^40: `sourceLine` and `rowOf` agree, the union follows mark
  toggles and a growing result set once a snapshot is installed, a stale
  snapshot stays in place and one built for other matches is dropped,
  `sourceLines` and the cursor agree with `select`, the snapshot is charged
  and released, and a share too small for it gives a partial snapshot.
- `HighlighterTest` checks spans per rule: overlap (the later rule wins, a
  span inside or over another), whole line dropping earlier spans, capture
  groups including an unmatched group, case folding and literal mode,
  opacity, disabled and empty rules, variance stable per string, tab columns
  and window clipping, the 256-span cap and its cost, a backtracking pattern
  abandoned within the step bound, and a pattern that does not compile. The
  backtracking case uses `(.*a){12}$` on 32 `a`s and `!`: JDK 21 returns
  `(a+)+$` at once and leaves the bound untested. With the line bound
  removed, the matcher's 2^26-char budget runs out and the next rule's span
  is missing. `BoundedCharsTest` checks the limit, `used()` and `reset`.
- `OverviewBinsTest` compares `lineAt` with `BigInteger` arithmetic up to
  `Long.MAX_VALUE` lines, inverts it with `rowOf` past 2^31 lines, and counts
  bins for dense, sparse, fewer-lines-than-pixels and uneven-span cases.
- `MarkToggleTest` marks and unmarks a contiguous range, walks listed rows,
  unmarks the marks view while walking it, cancels from the progress
  callback, and stops at the results share over 2^32 lines.
- `LineFinderTest` runs quick find on a file with a tab and a CRLF line:
  columns after tab expansion, forward and backward from a column, no match
  in either direction, the CR excluded, rows limited by a cursor, row reads
  through `readRows`, and a canceled token.
- `ViewportTest` checks the scroll bar mapping with synthetic line counts up
  to 2^52: exact endpoints, monotonic in both directions, a round trip within
  one scroll unit, the 2^30 boundary, and clamping.

## Folding, line markers and ANSI colors

`FoldMapTest` checks `rowOf` and `lineAt` against hand-computed rows on both
sides of two entries, refusal of overlapping and one-line entries, cursor
snapshots, and the `FOLDS` charge up to a full share and back to 0.
`ViewFeaturesReadTest` runs `FoldClassifier` over a stack trace from inside an
entry and with an entry-start rule, block columns through a tab, whitespace
markers with CRLF, a lone CR and a last line without LF, and hidden ANSI
colors in reads and copy. `AnsiSearchTest` checks that a literal search with
`hideAnsi` matches across removed sequences, which a byte search would miss.

## Timestamps

- `TimeSearchTest` checks `locate` and `locateLinear` against an in-memory
  oracle for every millisecond target across generated files with equal
  stamps and continuation lines, from before the first stamp to after the
  last.
- `bisectionOverThreeGigabytes` runs over `GeneratedSource`, a `ByteSource`
  that computes fixed-width lines from their offset and stores nothing, so
  probes and results past 2^31 run without a 3 GB file.
- Two concatenated days must give one descent in 256 samples; a +-5 ms jitter
  must land bisection within 11 ms of the target (two jitter widths plus one
  step).
- The bracket tests place a block at a known probe: 34-byte entries put the
  first probes on lines 500, 250 and 750, also past 2^31 with a shifted block
  in `GeneratedSource`. `aBlockThatFitsItsBracketLandsThereAndStaysConsistent`
  pins what the check leaves unseen; a change that closes the gap updates it.
- `TimeSpreadTest` builds samples directly (`TimeSamples.of`), so each level
  boundary, the clamp at 16 and the rows left out of the averages have exact
  expected values.
- Zone cases use `America/New_York` 2025 dates for the DST overlap and gap.
- Two-digit years are checked against a 2026-03 reference on both sides of
  the 50-year pivot (`76` in April is 1976, in February 2076, in March a tie
  that takes 1976) and against the current date.
- A `StackOverflowError` in a user format is provoked by a `CharSequence`
  whose `charAt` throws it: the 256-char prefix is too short to overflow the
  regex stack on every platform.

## Threads and file monitoring

- `WorkersTest.noTaskRunsAfterCloseReturns` races `tryStart` against `close`
  50 times. Without the second `closed` check in `start`, a thread started
  after `close` took its snapshot of the live set. It polls `liveThreads()`
  after close, because `ConcurrentHashMap.size` lags a removal the set
  already shows.
- `OperationGateTest` covers admission, refusal after close and the idle wait.
- `ChangeDetectorTest` classifies growth, truncation, a renamed file replaced
  under the name, a rewritten head, a same-size edit past the head digest, a
  touch with the same bytes, and a missing path or a directory. Two growth
  cases must be `REPLACED`, not `APPENDED`: a file opened empty, then written,
  truncated and regrown longer, and a longer rewrite past an unchanged 4 KiB
  head.
- `FileMonitorTest` checks the poll and the cancel, and that a directory event
  wakes a watch long before its poll interval; that case skips on macOS,
  whose `WatchService` polls. A listener that throws on checks 1, 2 and 4 must
  still be called a sixth time, and the handler must receive checks 1 and 4
  only: an exception ended the watch thread silently, and with it following.

## Platform assumptions

`SafePathsTest` creates a FIFO with `mkfifo` and skips that case without POSIX
attributes or `mkfifo`; the `/dev/null` case skips where the device is absent.
The symbolic link cases in `SafePathsTest` and `LogDocumentFollowTest` skip on
Windows, where creating a link needs a privilege.
`TestkitTest` makes a directory read-only to check that `TempTrees.delete`
rethrows after its bound, and skips without POSIX attributes or when the user
can write to it anyway (root).
