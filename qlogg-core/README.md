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
| `core.io` | `SafePaths`, byte sources, `BlockCache`, `ReadBuffers`, `AtomicFiles`, `PropertiesFiles` | [File access and text](docs/files-and-text.md) |
| `core.text` | Encoding detection, `LineDecoder`, `AnsiCodes`, `LogText`, `BoundedChars`, `PathAtCaret` | [File access and text](docs/files-and-text.md) |
| `core.index` | `GrowableLineIndex` (full and sparse), the parallel `Indexer` | [Line index](docs/line-index.md) |
| `core.search` | `SearchEngine`, matchers, combining expressions, range search, `MutableLineSet` | [Search](docs/search.md) |
| `core.folder` | `FolderSearch`: search in a folder without opening its files | [Search](docs/search.md#search-in-a-folder) |
| `core.time` | Timestamp formats and parsers, format detection, lookups by time, time spread | [Timestamps](docs/timestamps.md) |
| `core.watch` | `FileMonitor`, `ChangeDetector` | [File monitoring](docs/file-monitoring.md) |
| `core.rotate` | Rotated sets: member discovery and order, `MemberIdentity`, `RotatedBytes`, `RotatedIndex` | [Rotated sets](docs/rotated-sets.md) |
| `core.model` | `LogDocument`, `LineReader`, `Viewport`, `LineFinder` | [Documents](docs/documents.md) |
| `core.model` | Folding, `Highlighter`, syntax colors (`LogTokens`), keyword triggers, marks, `OverviewBins`, `FilteredView` | [View models](docs/view-models.md) |
| `core.format` | `PrettyPrint` for JSON and XML, `JsonStrings` and `XmlEntities` unescape | [Formats](#formats) |
| `core.util` | `Checks`, `Streams` | [Utilities](#utilities) |
| `core.util.os` | `OsTraits` and one class per operating system, `EnvPaths`, `RunnableTypes` | [Operating system seam](#operating-system-seam) |

## Threads

`Workers` creates every thread; nothing else creates a thread, an executor, a
`ThreadFactory` or a `java.util.Timer`. Blocking work runs on named virtual
threads and CPU-bound work on a fixed platform pool.

| Class or call | Thread |
|---|---|
| `Indexer` coordinator, `IndexListener` | `qlogg-index-` |
| `SearchEngine` coordinator, `SearchListener` | `qlogg-search-` |
| `FolderSearch` coordinator, `FolderListener` | `qlogg-search-` |
| Index block scans, search chunk matching, `CpuSteps` steps | `qlogg-cpu-N` |
| `FileMonitor` watch, `MonitorListener` | `qlogg-follow-` |
| `LogDocument.closeAll` | `qlogg-lifecycle-`, one per document |
| `LogDocument.open`, `LineReader`, `TimeReader`, `TimeSearch`, `FoldClassifier`, `LineFinder`, `RangeFinder`, `MarkToggle` | The caller's worker; these block. Given `Workers`, `LineFinder`, `MarkToggle`, `TimeSearch.locateLinear` and `RangeFinder` matching run in `CpuSteps` steps |
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
  directory; nothing goes to `java.io.tmpdir`. `writeUserFile` keeps a user
  file's group and mode.
- Nothing is memory mapped.
- A value that did not originate in QLogg's code passes `LogText.oneLine`
  before it reaches a logger.

## Formats

`core.format` serves the scratchpad's format and unescape transforms. Each call
takes and returns a string on the caller's thread, with no cancel; the caller
bounds the input.

| Class | Contract |
|---|---|
| `PrettyPrint` | `json` and `xml` change only the whitespace between tokens: two spaces per level, LF breaks. Strings, comments, element text, mixed content, `xml:space="preserve"` content, CDATA, PIs and the DOCTYPE stay verbatim. Output past `maxChars` throws `TOO_LONG` |
| `JsonStrings` | `unescape` reads RFC 8259 escapes in a string's content without its quotes; a malformed escape or a surrogate escape without its escaped pair throws |
| `XmlEntities` | `unescape` replaces the five XML entities, the 252 HTML 4.01 names and decimal and hex references; anything else stays as written |

Refusals throw `FormatException` with a `Reason`, the offset and its 1-based
line and column (`\n`, `\r\n` and `\r` end a line; a column counts chars).

- JSON is RFC 8259 plus `//` and `/* */` comments, which are kept. Trailing
  commas, single quotes, unquoted names, NaN and Infinity are refused. A
  check-only pass runs before the layout, so a malformed deep text reports its
  error rather than `TOO_LONG`.
- XML is split into tags, comments, PIs, CDATA, the DOCTYPE and text, not
  parsed: no entity, DTD or external reference is read. The split refuses an
  unterminated construct, an end tag not matching the open element, an end tag
  with nothing open and an element still open, reported at the end of the text.
  Several top-level items format as a sequence; text at the top level leaves it
  unchanged.
- Both layouts keep their nesting on an explicit stack, so any depth that fits
  the input is formatted without recursion.

## Utilities

`core.util` depends on no other QLogg package (`ArchitectureTest`).

| Class | Contract |
|---|---|
| `Checks` | `atLeast` and `notNegative` throw `IllegalArgumentException` naming the argument |
| `Streams` | `readLimited` reads a stream to its end and throws `IOException` once it passes the limit, so an oversized file is refused rather than truncated |

## Operating system seam

`OsTraits` answers each value that differs between operating systems and
creates the system's class of each seam whose behavior differs; callers call a
seam on every system without checking the system first.
`OsTraits.current()` picks `WindowsTraits`, `MacTraits` or `LinuxTraits` once
from `os.name`; a name that is neither Windows nor macOS takes the Linux
answers. No other class in core, app or the launcher reads `os.name`, and
`app.controller` does not call `current()`: a controller takes the system, or a
value from it, from its caller (`ArchitectureTest`). Tests take another
system's answers from `OsTraits.of(name)`.

| Method | Windows | macOS | Linux | Caller |
|---|---|---|---|---|
| `deviceNames` | `WindowsDeviceNames` | `FileNames` | `FileNames` | `SafePaths` refuses a path `DeviceNames.opensDevice` matches: `\\.\`, `\\?\`, `CON`, `NUL`, `COM1`... on Windows, none elsewhere |
| `confinement` | `WindowsFileConfinement` | `MacFileConfinement` | `LinuxFileConfinement` | `PlatformSetup` and `UserGuide.embedded`; see the next table |
| `userSettings` | `%APPDATA%\QLogg` | `~/Library/Application Support/QLogg` | `$XDG_CONFIG_HOME/qlogg` | `SettingsLocation` per-user mode |
| `launcherDirectory` | null | null | `<home>/bin` | `SettingsLocation` ([portable mode](../qlogg-app/docs/settings.md#settings-directory)) |
| `macKeymap` | false | true | false | `Keymap.defaults()` |
| `takesCtrlShiftZero` | true | false | false | `Keymap.defaults()`: no `Ctrl+Shift+0` for Clear Color Labels |
| `keepFileClosedByDefault` | true | false | false | `FollowSettings.defaults`: the `follow.keepClosed` default |
| `watchServiceByDefault` | true | false | true | `MonitorSettings.defaults`: the `monitor.watchService` default |
| `runsInsteadOfOpening` | `RunnableTypes.windows` with `PATHEXT` | `RunnableTypes.mac`, or the execute bit | the execute bit | `PathCommands.openInEditor` fallback |

`PlatformSetup` calls `confineHome` and `placeBrowserData` on every system
before the Display exists:

| `FileConfinement` | Windows | macOS | Linux |
|---|---|---|---|
| `confineHome` | nothing | nothing | sets `user.home` to the settings directory outside per-user mode |
| `placeBrowserData` | sets `EDGE_DATA_DIR`, the WebView2 data folder | nothing | nothing |
| `embedsGuide` | true | true | per-user mode only |

`OsTraits.description()` is the About line: `os.name`, `os.version` and
`os.arch`.

### Runnable types

`RunnableTypes` lists the file name extensions a system runs, or follows to
another target, when asked to open the file with its default application.
`windows(name, pathext)` matches `RunnableTypes.WINDOWS` and each `.`-prefixed
`PATHEXT` entry, case-insensitively, after dropping trailing dots and spaces as
Windows does. `mac(name)` matches `RunnableTypes.MAC`: `.terminal`, `.command`
and `.tool` run in Terminal, `.jar` in Jar Launcher, and `.fileloc`, `.webloc`
and `.inetloc` open their target, none needing the execute bit. Both lists are
denylists: a program installed later can register another extension to run.

## Testing

`./mvnw -pl qlogg-core verify` runs the tests; none needs a display. The
strategy, the reference oracles and the platform skips are in
[TESTING.md](TESTING.md).

The test jar carries `testkit` for the other modules' tests and `qlogg-bench`:

| Class | Use |
|---|---|
| `SyntheticLog` | Deterministic log writer |
| `Poll.until` | Bounded poll for state another thread produces |
| `OnWorker` | Runs a body on a `qlogg-search-` virtual thread and returns its value or what it threw |
| `TempTrees.delete` | Deletes a tree and retries until a bound, because Windows keeps a file a terminated child process had open delete-pending for a moment |
