# Search

`core.search` runs a query over a document's lines and holds the matching
line numbers. Module overview: [qlogg-core](../README.md). Quick find
(`LineFinder`) is in [Documents](documents.md#quick-find).

## SearchEngine

`SearchEngine.search(source, query, results, listener)` searches the lines of
the source's current index snapshot, limited to `SearchQuery.scope` (null for
every indexed line), and adds the matching line numbers to a `MutableLineSet`
in ascending order.

A coordinator on a `qlogg-search-` virtual thread cuts the range into chunks
of whole lines (10,000 lines or 4 MiB, whichever comes first; a longer line is
a chunk of its own), reads each chunk in one positional read and submits it to
the CPU pool. A worker splits its chunk at LF units, drops a trailing CR from
each line and matches it. The coordinator merges results in chunk order, so
they stay in line order however chunks finish, and no match spans two chunks.
At most `maxChunksInFlight` chunks (default three per CPU thread) are read but
not merged.

`SearchListener.progressed` runs at most every 100 ms and once at the end,
before `finished`; both run on the coordinator thread.

### Outcomes

| End | Outcome | Limit |
|---|---|---|
| Range searched | `COMPLETED` | `NONE` |
| `maxResults` reached (0 is no limit) | `COMPLETED` | `MAX_RESULTS`; the results stop at the limit |
| `RESULTS` refuses the next chunk's matches | `COMPLETED` | `MEMORY`; the results up to the last whole chunk stay |
| `cancel()`, a read on a closed source, or closed workers | `CANCELED` | `NONE` |
| A regex that does not compile (`PatternSyntaxException`), a regex that overflows the stack on a line (`PatternTooComplexException` with the line), an I/O error, or a file shorter than its index | `FAILED` | `NONE` |

### Buffers and long lines

- A chunk reads into a pooled direct buffer of `chunkBytes` (`ReadBuffers`,
  from `IO_BUFFERS`).
- A line longer than `chunkBytes` reads into a heap buffer, and 3 bytes per
  line byte (the bytes and their decoded chars) are reserved from
  `IO_BUFFERS` until it is matched. A refused reservation trims the pool and
  retries once.
- A search that ends with less of `IO_BUFFERS` free than a full pool
  (`maxChunksInFlight` x `chunkBytes`) trims the pool, so a pool grown by an
  earlier parallel search does not hold the share. With more free, the pool
  stays for the next search: reallocating its direct buffers cost 5.4 ms per
  search on a 16 MiB file (`SearchTrimProbe`).
- A long line that `IO_BUFFERS` refuses with nothing in flight, or that is
  longer than `SearchEngine.MAX_LINE_BYTES` (`Integer.MAX_VALUE - 8`), is
  skipped. It is not matched, not reported as a match under inverse, and the
  search continues after it. `SearchListener.finished` reports the count of
  skipped lines; `progressed` counts them as searched.

### Cancellation and runaway regexes

Cancellation is checked before each chunk, every 1,024 lines in a worker, and
every 4,096 `charAt` calls a regex makes on `LineText.chars()`, so a regex
that backtracks without end stops on cancel.

A worker catches a `StackOverflowError` from a regex (`(a|b)*c` raises one on
a 400,000-char line) and the search ends with `PatternTooComplexException`.
The pool thread survives and nothing reaches the workers' failure handler.
`LineFinder` (quick find) throws the same exception.

## Matching

`StandardMatchers` compiles a query into one `LineMatcher` per worker. Each
term takes one of these forms:

| Term | Runs as |
|---|---|
| Literal, case-sensitive | Byte search for the pattern encoded in the document charset |
| Literal, case folded, ASCII pattern, byte-aligned ASCII-compatible encoding, no `hideAnsi` (`foldsAsciiOnly`) | Byte search folding `A`-`Z` |
| Any other literal, or one whose pattern does not encode | `Pattern.quote` regex on decoded text |
| Case-sensitive regex with no metacharacter (`\ ^ $ . \| ? * + ( ) [ ] { }`) and no U+FFFD | As a case-sensitive literal |
| Any other regex | `java.util.regex.Pattern` on decoded text with `StandardMatchers.regexFlags`: `UNIX_LINES`, plus `CASE_INSENSITIVE \| UNICODE_CASE` when case folds |

- A byte search finds candidates eight bytes per `getLong` (`ByteScan`) by the
  pattern's first byte and its last nonzero byte together, then compares the
  rest. Workers split chunks at LF the same way.
- A case-sensitive regex whose first chars before any metacharacter form a
  literal of 3 or more encoded bytes (`worker-1` in `worker-1[0-5]`) runs that
  literal as a byte search first and decodes only the lines that contain it.
  A pattern with `|` anywhere, or a quantifier `?`, `*` or `{` on the
  literal's last char, gives a shorter literal or none. Not with `hideAnsi`,
  and only where byte search runs.
- `LineText` decodes a line in a direct buffer through an 8 KiB heap window,
  because the JDK decoders' fast loops run only on heap buffers. UTF-16LE and
  UTF-16BE lines copy units to chars; a line with an odd byte count, a
  surrogate or U+FFFE goes to the decoder.

Byte search and regex differ in what they see:

- Byte search runs only where a byte match is a character match: UTF-8,
  US-ASCII, single-byte charsets, and UTF-16 or UTF-32 at unit-aligned
  offsets. Other multi-byte charsets (Shift_JIS, GBK) run as regex.
- A byte search sees the file's bytes; a regex sees malformed input as
  U+FFFD.
- Byte folding folds ASCII letters only, so `k` does not match U+212A KELVIN
  SIGN, which a case-folded regex matches. `StandardMatchers.foldsAsciiOnly`
  reports which a case-folded literal gets; the match highlight
  (`HighlightComposer.matchRule`) uses it to fold as the search did.

Line and option rules:

- The line excludes LF and a trailing CR. `^` and `$` anchor at its ends, and
  `UNIX_LINES` makes no other char a line terminator.
- Whole word: a term matches only where the chars before and after it are not
  `[A-Za-z0-9_]`. A byte search checks the bytes around the match
  (ASCII-compatible encodings only); otherwise `StandardMatchers.wholeWord`
  wraps the regex in `(?<!\w)(?:...)(?!\w)`. A pattern that ends inside `\Q`,
  a `(?x)` comment, or a `\Q` inside such a comment (`Pattern` reads it as a
  quote) gets `\E`, a line feed or both before the closing parenthesis.
- Inverse negates the whole expression.
- With `SearchQuery.hideAnsi`, `LineText` removes ANSI SGR sequences from the
  decoded chars and every literal runs as a quoted regex, because the file's
  bytes still hold the sequences.

### Combining expressions

`ExpressionParser.parse(text, mode, combining)` reads the combining syntax:

- Terms in double quotes. `\"` is a quote inside a term; other backslashes
  stay, so regex escapes pass through.
- Operators `and` `&`, `or` `|`, `xor`, `not` `!`, the aliases `nand`, `nor`
  and `xnor`, and parentheses. Operator words are case-insensitive.
- Precedence, highest first: `not`, `and`, `xor`, `or`.
- Combining text without a double quote is one term.
- A syntax error throws `ExpressionException` with a `Reason` and a 1-based
  column; nesting deeper than 256 is an error.

## Range search

A query with `SearchQuery.rangeEnd` set is a range search. A line matching
`expression` opens a range, and the first later line matching `rangeEnd`
closes it. The results are every line from the opening line through the
closing line; a range still open at the end of the scope runs to it.

- Workers match each line against both expressions (each with the query's
  case, whole-word and ANSI rules, not inverted) and return both hit lists.
  The coordinator walks them in chunk order and carries the open state across
  chunks, so the chunk partition cannot change the result.
- The opening line is not tested against the end, and a start line inside an
  open range neither opens nor closes anything.
- `inverse` returns the lines outside every range.
- `scope` limits the scan; `rangeOpen` states whether a range is open before
  the scope's first line. A skipped line inside an open range is in it.
- When a range search completes with no limit,
  `SearchListener.rangeOpenBeforeLast` reports whether a range was open
  before the scope's last line. A search from that line on (an auto-refresh)
  starts from that state.

`RangeFinder.around` finds the range around one line: the nearest line at or
above it matching the start, through the first later line matching the end, or
the last line. It returns null when no start precedes the line or that range
closed above it. It reads blocks of whole lines (4,096 lines, 1 MiB) through
one heap buffer and matches the first 1 MiB of a longer line. It looks back
only to the nearest start, so where start lines repeat before an end, its
range begins later than the range search's.

## Result sets

`MutableLineSet` holds line numbers in buckets of 65,536 lines: a sorted
`char[]` of the low 16 bits up to 4,096 entries, else a 1,024-word bitmap. A
bitmap returns to an array at 2,048 entries or fewer. Counts of the entries
before each bucket make `rank` and `select` a binary search over buckets.

- Cost (`retainedBytes()`): 40 B per bucket (object and array header), plus
  2 B per array slot padded to 8 B or 8 KiB per bitmap, plus 20 B per slot of
  the bucket directory, which doubles as it grows. These are HotSpot sizes
  with compressed references (heaps under 32 GiB).
- With a budget, the set holds exactly `retainedBytes()` of `RESULTS`.
  `addSorted` reserves an upper bound of the batch's growth first and adds
  nothing when the share refuses; `add` and `toggle` charge without the limit
  check; removals and `clear()` release.
- A `ReentrantReadWriteLock` guards the set. A writer holds it for one batch
  and never across I/O, so a reader on the UI thread waits at most for one
  chunk merge.
- `version()` counts changes, so a reader detects a toggle that left the size
  unchanged.

## Search in a folder

`core.folder.FolderSearch` searches the files of a folder without opening
them as documents the application shows. `start(query, results, listener)`
runs a coordinator on a `qlogg-search-` virtual thread:

1. `SafePaths.logDirectory` validates the folder and returns its real path.
2. The walk takes files whose name matches `names` (`;`-separated globs,
   `NamePattern.DEFAULT` is `*.log*`) and not `exclude`
   (`NamePattern.excludes`, which matches nothing when blank), in subfolders
   only with `recursive`, and sorts them by path.
3. Each file in turn: `LogDocument.open` with encoding detection, a full
   index, a `SearchEngine` search into a `MutableLineSet`, a `LineReader`
   read of each matching line, then close. The heap holds one file's index
   and result set at a time.

| `FileHits.Status` | When |
|---|---|
| `SEARCHED` | The file was searched; `matches()` counts its matching lines and `size()` the lines listed |
| `UNREADABLE` | A file or folder the walk cannot read, a link whose target is missing, or a file that does not open or index; `detail()` gives the reason |
| `ACCESS_DENIED` | As `UNREADABLE`, for an `AccessDeniedException`: permissions refuse listing the folder or reading the file; `detail()` is null |
| `NOT_TEXT` | A one-byte encoding with a NUL byte in the first 64 KiB, such as a compressed rotation `app.log.2.gz` |
| `FAILED` | That file's search failed, for example a regex that overflows the stack on one of its lines; the walk goes on |

- A symbolic link to a file is searched, because an open follows it. A link
  to a folder is not entered.
- A folder whose real path the walk has entered is skipped: Windows reports a
  directory junction as a folder, and one naming an ancestor would recur until
  the path is too long. Each entered folder's real path holds `RESULTS` as a
  walked file does until the walk ends.
- A regex that does not compile ends the search `FAILED` with the
  `PatternSyntaxException`.
- `FolderListener.progressed` runs at most every 100 ms and once at the end,
  before `finished`; both run on the coordinator thread.
- Cancellation is checked at every walked entry, every 50 ms while a file
  indexes or searches, and at every listed line. The file in progress is
  canceled and closed before `finished`, and is not listed.

### Folder search memory

Each walked file reserves 96 B plus 2 B per path char from `RESULTS`; each
listed line reserves 80 B plus 2 B per char, its text cut at 512 chars.
`FolderResults` holds these reservations until `close()`. A refused
reservation, here or in a file's `SearchEngine` search, first calls
`FolderListener.resultsRefused` on the coordinator thread. A true return means
the listener released `RESULTS` bytes, such as an older run's results: the
reservation is retried, or the file searched again. Otherwise the search
stops: the file in progress keeps the lines listed so far (`complete()`
false), `limited()` turns true, and the search ends `COMPLETED` with
`SearchLimit.MEMORY`.

A run reads files through its own `Indexer` and its own line cache rather than
the shared ones:

- The run's `Indexer` uses 1 MiB blocks, 2 per CPU thread, and keeps its pool
  until the run ends (`Indexer(workers, settings, false)`, then `trim()`). The
  shared indexer trims its pool after every file, and a trimmed direct buffer
  frees its native memory only at a later GC, so one pool per file would
  accumulate.
- `LogDocument.newReader(cache)` reads the listed lines through a 64 KiB cache
  of 8 KiB blocks, so a listing neither fills nor evicts the shared
  `BlockCache`.
