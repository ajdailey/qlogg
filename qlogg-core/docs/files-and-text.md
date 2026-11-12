# File access and text

`core.io` validates paths and reads files; `core.text` detects the encoding,
decodes lines and bounds text that reaches a logger or a user regex. Module
overview: [qlogg-core](../README.md).

## Path validation

Every path from outside the process goes through `SafePaths`. A log viewer
opens whatever readable file its user names, so a log path is canonicalized
and type-checked, not contained to a directory.

| Method | Rule |
|---|---|
| `logPath`, `logFile` | Rejects empty, NUL and, on Windows (`OsTraits.opensDeviceNames`), device forms (`\\.\`, `\\?\`, `CON`, `NUL`, `COM1`...). Made absolute and normalized. An existing path resolves to its real path and must be a regular file: a FIFO blocks the reader and a device never ends. The file named to `holdLock` is rejected under any name. A missing path needs an existing parent directory, so a log that appears later can be opened. `logPath` returns the path as given (absolute, normalized, symbolic links kept) and the real path; `logFile` returns the real path |
| `logDirectory` | A folder to search: the same form checks as `logFile`, made absolute and normalized, must be an existing directory, resolved to its real path |
| `settingsDirectory` | Absolute, normalized, created with POSIX `rwx------` where supported, resolved to its real path, must be a directory |
| `child` | A fixed name matching `[A-Za-z0-9._-]+`, resolved inside the directory |
| `holdLock`, `releaseLock` | Name and forget the process's instance lock file, compared by real path and file key, so a hard link matches. On POSIX a close of any channel on the file ends the process's lock, and a replace of the name leaves the lock on the old file |

`FileByteSource.open` validates on every open and opens the real path;
`path()` is the path as given and `realPath()` the file opened.

## Byte sources and buffers

| Class | Contract |
|---|---|
| `FileByteSource` | `open` validates through `SafePaths.logFile`, then opens a `FileChannel` for reading. Positional reads only, thread-safe. `identity()` holds the file key (null on Windows), creation and modified times, size, the CRC32C of the first 4 KiB and the real path opened. On Windows the JDK opens the file with delete sharing, so a writer can rename, truncate or delete it while it is open |
| `ReopeningByteSource` | Holds no file between reads ([Keep file closed](documents.md#keep-file-closed)). `size()` reads the attributes of the real path |
| `MemoryByteSource` | A byte array in the heap, for pasted text. `path()` is null; the size never changes |
| `BlockCache` | LRU of fixed-size blocks for random reads, keyed by source and block number. `fromBudget` creates the process's cache: 64 KiB blocks, the smaller of the `BLOCK_CACHE` share and 64 MiB, charged for the life of the process. A miss loads into an evicted block's array, so a full cache allocates nothing; bytes are copied out under the lock, which is never held during I/O. A block read short at end of file is read again when a request reaches past it. `invalidateFrom` drops a block and everything after it; `invalidate(source)` drops a source's blocks, which `LogDocument.close` calls |
| `ReadBuffers` | Pool of little-endian direct buffers for sequential scans, reused while operations run. The first two are charged to `IO_BUFFERS` unconditionally; past the share, `tryAcquire` returns null and `acquire` waits. `trim` drops free buffers past the first two and releases their reservation; an `Indexer` calls it when an operation ends (unless created to keep its pool), the search engine when a search ends with the share short of a full pool |

A read after `close` throws `ClosedChannelException` on every `ByteSource`.

Nothing is memory mapped: Java 21 releases a mapping only through garbage
collection, and on Windows a live mapping blocks the writer's truncation.

## Atomic writes

`AtomicFiles.write` puts the content in a temporary file in the target's
directory (POSIX `rw-------`), then moves it over the target with
`ATOMIC_MOVE`. On failure the target is unchanged and the temporary file is
deleted. Nothing goes to `java.io.tmpdir`. A target that is a symbolic link is
resolved first, dangling or not, so the link stays and the file it names is
replaced. Where POSIX permissions do not exist (Windows), a move that throws a
`FileSystemException` is tried up to 10 times, 25 ms apart: a target another
process holds open without delete sharing refuses the move for that moment.
Any other `IOException` throws at once.

- `write(Path, InputStream)` streams the content through a 64 KiB buffer.
- `writeOwnerOnly`, for a secret, also replaces the temporary file's ACL with
  one entry for the current user where there are ACLs but no POSIX
  permissions (Windows). The file's owner is the fallback when no account
  name resolves.

`write` and `writeOwnerOnly` are for QLogg's own files. `writeUserFile` writes
a file the user names (Save Selection, Save Filtered Results, filter and
highlighter exports) and keeps its group and mode:

| Target | Write |
|---|---|
| New | Temporary file with the default mode (umask), forced, then moved |
| Existing | Temporary file owner-only while written, then given the target's group and POSIX permissions (Windows: hidden and system attributes), forced, then moved |
| Not writable | `AccessDeniedException`; nothing written |
| More than one hard link, another owner, a group the temporary file cannot take, a directory that is not writable, or a move still refused after its tries | Written in place: truncate, write, force. A failure partway leaves the target partly written |

`PropertiesFiles.read` reads every properties file QLogg loads, its own and
imported ones: at most a given size, one leading UTF-8 BOM dropped, then UTF-8,
or ISO-8859-1 when the bytes hold a malformed UTF-8 sequence. QLogg writes
ASCII with Unicode escapes, which reads the same either way.

## Encoding detection

`EncodingDetector.detect` reads the first 64 KiB and applies the first rule
that matches:

| Rule | Result |
|---|---|
| BOM `EF BB BF`, `FF FE 00 00`, `00 00 FE FF`, `FF FE`, `FE FF`, checked in that order | UTF-8, UTF-32LE, UTF-32BE, UTF-16LE, UTF-16BE; the first line starts after the BOM. A UTF-16LE file whose first char is U+0000 reads as UTF-32LE |
| At least 30% zero bytes at odd offsets and under 5% at even ones (or the reverse) | UTF-16LE (or UTF-16BE) |
| Valid UTF-8; a sequence cut off by the end of the sample counts as valid | UTF-8 |
| None | The fallback charset; ISO-8859-1 maps every byte to a char |

`EncodingDetector.forced` applies a charset the user chose and skips that
charset's own BOM. A BOM-sensing `UTF-16` or `UTF-32` follows the BOM's byte
order; for a forced UTF-16 charset only the 2-byte BOMs count.

`TextEncoding` derives the LF unit width (1, 2 for UTF-16, 4 for UTF-32) from
the charset and reads `UTF-16` and `UTF-32` as big-endian.

## Line decoding

`LineDecoder` (one per thread) decodes a line's bytes with malformed input
replaced by U+FFFD and drops one trailing CR unit; a lone CR elsewhere stays.
`AnsiCodes` removes ANSI SGR sequences from decoded text.

## Bounded text

`LogText.oneLine` reduces a value that did not originate in QLogg's code (a
path, a pattern, an exception message) to one line before it reaches a logger.
C0 controls, DEL, NEL, U+2028 and U+2029 become `_`, and the result is cut at
1,024 chars. `config/findsecbugs-taint.txt` registers it with find-sec-bugs as
removing CR and LF.

`BoundedChars` is the `CharSequence` a user regex runs over outside a search.
`charAt` throws `BoundedChars.LimitReached` past the limit given to `reset`,
and `used()` counts the reads. `lineLimit(line)` is `8 * length + 65,536`.
Built with a `CancellationToken`, it checks the token every 4,096 reads.
`find(matcher, line)` runs `find` at the line limit and returns false when the
limit is reached or the regex stack overflows. `FoldRules`, `Triggers`,
`UserFormat` and the app's `HighlightSelector` run through `find`;
`Highlighter` and `CommentTemplate` use the chars directly, for a per-matcher
budget and a failure message. A backtracking pattern ends at the bound instead
of stalling the reading thread.
