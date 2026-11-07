# qlogg-core

Model-side code: thread ownership, the memory budget, file access, text
handling, the line index, search, timestamps, file monitoring and the document
model. It has no SWT and no logging dependency, and its tests run without a
display.

---

## Map

| Package | Contents | Detail |
|---|---|---|
| `core.concurrent` | `Workers`, background operations, `OperationGate`, `MemoryBudget` | [Threads and memory budget](docs/threads-and-memory.md) |
| `core.io` | `SafePaths`, byte sources, `BlockCache`, `ReadBuffers`, `AtomicFiles` | [File access and text](docs/files-and-text.md) |
| `core.text` | Encoding detection, `LineDecoder`, `AnsiCodes`, `LogText`, `BoundedChars` | [File access and text](docs/files-and-text.md) |
| `core.index` | `GrowableLineIndex` (full and sparse), the parallel `Indexer` | [Line index](docs/line-index.md) |
| `core.search` | `SearchEngine`, matchers, combining expressions, range search, `MutableLineSet` | [Search](docs/search.md) |
| `core.time` | Timestamp formats and parsers, format detection, lookups by time, time spread | [Timestamps](docs/timestamps.md) |
| `core.watch` | `FileMonitor`, `ChangeDetector` | [File monitoring](docs/file-monitoring.md) |
| `core.model` | `LogDocument`, `LineReader`, `Viewport`, `LineFinder` | [Documents](docs/documents.md) |
| `core.model` | Folding, `Highlighter`, keyword triggers, marks, `OverviewBins`, `FilteredView` | [View models](docs/view-models.md) |
| `core.util` | `Checks`, `Streams` | [Utilities](#utilities) |

## Threads

`Workers` creates every thread; nothing else creates a thread, an executor, a
`ThreadFactory` or a `java.util.Timer`. Blocking work runs on named virtual
threads and CPU-bound work on a fixed platform pool.

| Class or call | Thread |
|---|---|
| `Indexer` coordinator, `IndexListener` | `qlogg-index-` |
| `SearchEngine` coordinator, `SearchListener` | `qlogg-search-` |
| Index block scans, search chunk matching | `qlogg-cpu-N` |
| `FileMonitor` watch, `MonitorListener` | `qlogg-follow-` |
| `LogDocument.closeAll` | `qlogg-lifecycle-`, one per document |
| `LogDocument.open`, `LineReader`, `TimeReader`, `TimeSearch`, `FoldClassifier`, `LineFinder`, `RangeFinder` | The caller's worker; these block |
| `Viewport`, `FoldMap`, `FilteredView` | Confined to one thread (the UI thread in the app) |

Listener calls from a coordinator must not block toward the UI thread.
`OperationGate.enter` and `exit`, `MutableLineSet` reads and `MarkComments` are
safe from the UI thread.

## Memory budget

Every structure that grows with a file reserves from a `MemoryBudget` share
before it allocates and releases on close. A refused reservation degrades the
structure (a sparse index, a search stopped at `MEMORY`, a skipped line, a
partial snapshot, an uncolored row) instead of failing with
`OutOfMemoryError`. Shares and holders are in
[Threads and memory budget](docs/threads-and-memory.md#memory-budget).

## Document lifecycle

Every operation on a document's file holds a `LogDocument` entry
(`enter()`, `exit()`) while it runs. Callers cancel their operations before
`LogDocument.close`, which waits a bounded time for entries to exit. Detail in
[Documents](docs/documents.md#operations-and-close).

## Path and file rules

- Every path from outside the process goes through `SafePaths`.
- `AtomicFiles` replaces a file through a temporary file in the target's
  directory; nothing goes to `java.io.tmpdir`.
- Nothing is memory mapped.
- A value that did not originate in QLogg's code passes `LogText.oneLine`
  before it reaches a logger.

## Utilities

`core.util` depends on no other QLogg package (`ArchitectureTest`).

| Class | Contract |
|---|---|
| `Checks` | `atLeast` and `notNegative` throw `IllegalArgumentException` naming the argument |
| `Streams` | `readLimited` reads a stream to its end and throws `IOException` once it passes the limit, so an oversized file is refused rather than truncated |

## Testing

`./mvnw -pl qlogg-core verify` runs the tests; none needs a display. The
strategy, the reference oracles and the platform skips are in
[TESTING.md](TESTING.md).

The test jar carries `testkit` for the other modules' tests and `qlogg-bench`:

| Class | Use |
|---|---|
| `SyntheticLog` | Deterministic log writer |
| `Poll.until` | Bounded poll for state another thread produces |
| `TempTrees.delete` | Deletes a tree and retries until a bound, because Windows keeps a file a terminated child process had open delete-pending for a moment |
