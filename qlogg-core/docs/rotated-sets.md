# Rotated sets

`core.rotate` reads a log and its rotated files as one byte stream with one
line index. Module overview: [qlogg-core](../README.md).

## Discovery

`RotatedSet.discover(given, held, fallback, forced, ignoreCase)` lists the
directory of `given` once, not recursively, stats each matching file and reads
the first 64 KiB of each member. It blocks; call it on a worker.
`RotatedSet.rescan(..., keep)` lists the series the same way but keeps an open
set's encoding, so a newer file in another line feed width is left out rather
than changing the set.

`MemberNames` matches names. The base is `given` with its compression
extension and rotation token removed; a token is `\d+([._-]\d+)*`.

| Scheme | Example | Matched as |
|---|---|---|
| Suffix | `app.log.1`, `app.log-20261125`, `app.log.2026-11-25_13` | base + `.`, `_` or `-` + token |
| Before the extension | `app.1.log`, `app-2026-11-25.log`, `app.2026-11-25.0.log` | stem + separator + token + extension |
| Compressed | `app.log.2.gz`, `app.1.log.zip` | either form plus `.gz`, `.bz2`, `.xz`, `.zst`, `.zip` or `.Z` |

`app.log.bak`, `app.logger`, `app2.log` and `app.log.1.tmp` are not members.
Names compare without regard to case when `ignoreCase` is true, as
`OsTraits.fileNamesIgnoreCase` answers on Windows and macOS. A token at the end
is removed before one in front of the extension, so `my-app-1.0.log` opened
alone has the base `my-app.log`.

Members are regular files that pass `SafePaths.logPath`; a file that vanishes
during the listing is skipped. The base file need not exist: with only dated
files, the newest is last.

- Order: the base file last; the others by modification time, oldest first;
  equal times by token compared as numbers, higher first.
- Encoding: the forced charset, or the detection of the newest member that has
  bytes. The set's encoding has byte order mark length 0; each `Member` keeps
  the set's charset with its own mark length. A member whose bytes are not
  valid in the set's charset decodes to U+FFFD.

| `LeftOut.Reason` | Files |
|---|---|
| `COMPRESSED` | Every compressed member |
| `OVER_CAP` | Members older than the newest `MAX_MEMBERS` (100) |
| `ENCODING` | A member with bytes whose line feed differs in width or byte order from the set's (UTF-16 against a one-byte charset, UTF-16LE against BE). An empty member is kept |

## Member identity

`MemberIdentity.read(file, indexedBytes)` records a member;
`find(candidates)` returns `FOUND` with the one file that matches, `NONE`, or
`AMBIGUOUS`.

- With a file key (Linux, macOS), a candidate with the same key matches.
- Without one (Windows), a candidate matches when it holds at least
  `indexedBytes` and the CRC32C of the first and last 4 KiB of that region are
  equal. Growth past the region keeps the match. Creation time only picks
  between several matches: NTFS tunneling gives a file created under a
  just-renamed name the old creation time.
- An empty region matches every file, so an empty member is `AMBIGUOUS`
  whenever another member exists.
- `byContent()` drops the file key, so `find` compares digests: a copy of the
  member matches, and the member itself only while it holds the indexed
  bytes.

## RotatedBytes

`RotatedBytes.open(base, encoding, parts)` concatenates member sources, oldest
first, into one `ByteSource`, and owns them from then on.

- Every member's byte order mark is left out, so the stream has none.
- A member before the last that does not end with LF is followed by one LF in
  the set's encoding. The last member never is, so its partial last line stays
  partial.
- A member before the last keeps the length it had at `open`, cut to whole code
  units (`memberEnd`). A read inside a member that has since shrunk throws
  `FileChangedException`. The last member grows with its file; `size()` follows
  it.
- `read` stops at each member boundary and before an added LF; `path()` is the
  base and `identity()` the last member's.
- `member(i)` is member i in its own offsets, cut at `memberEnd(i)`. Index a
  member through it so its index ends where the stream stops taking its bytes.

## RotatedIndex

`RotatedIndex.of(bytes, snapshots)` is an immutable `LineIndex` over the
stream, built from one member snapshot per member from member 0. Each snapshot
indexes `member(i)` with the member's byte order mark length. Every snapshot
but the last must reach `memberEnd`, or `of` throws
`IllegalArgumentException`; so line numbers below the last given member never
change while it grows.

- Lookups binary-search the members, then look up the member's snapshot and
  add the member's line and byte base. A sparse member's lookups read its
  file.
- `lastLineComplete` is true when the last given member is complete and not
  the last member, otherwise that member's value.
- `maxLineBytes` is the largest member's; `generation` is the sum of the
  members' generations.
- `memberOf(line)` and `memberLine(line)` give a line's member and its line
  in that member.

## Rotated documents

`LogDocument.openRotated(path, forcedCharset, services, detectTimeFormat,
keepClosed, ignoreCase)` opens the set `path` belongs to through
`RotatedContent`. The caller passes the case rule (`OsTraits` on the caller's
side); the document shows the base name and `path()` is the base path, which
need not exist.

- Each member is a `SetMember`: a `FileByteSource`, or a `ReopeningByteSource`
  with `keepClosed`, under the member's current name, and its own
  `GrowableLineIndex` with its own byte order mark. A member before the last
  shows the size it had when it stopped being last.
- `SetBytes` is the one `ByteSource` the document hands out. It holds the
  members and the `RotatedBytes` over them and replaces both when members join;
  a join changes no byte the published index covers.
- Indexing runs member by member, oldest first, as one operation toward the
  listener. The published `RotatedIndex` holds the completed members and the
  growing prefix of the current one. `stopIndexing` leaves later members
  unindexed.
- The timestamp format is detected from the newest member with bytes.

### Follow and roll-over

`SetFollow` runs one `FileMonitor` watch on the base path with a name
predicate for the series. Each check classifies the last member, and the member
before it once that one is indexed, with `FileCheck`. It lists the series again
(`RotatedSet.rescan`) when either reports anything but growth of the last
member, when a member's `ReopeningByteSource` read another file, when the
directory's modification time moved, while newer files wait to join, and every
10th check. `RollOver` matches the loaded members to the listing by
`MemberIdentity`, by name where a member has no digests yet or its digests
match several files.

| Listing | Action | Line numbers |
|---|---|---|
| Every member keeps its rank, members before the last keep their size, the last holds its indexed bytes | Renamed members re-pointed; newer files join once one has bytes (`APPENDED`); until then growth of the renamed last member is an append | Kept |
| Last member truncated, an unmatched file holds its indexed bytes | Copy-truncate: the copy takes the member's place, the truncated file joins | Kept |
| An unmatched file before the last member starts with its bytes, the last member not truncated | Copy in progress: ignored until its size holds for two rescans | - |
| Last member gone, nothing in its place | `MISSING` once; the content stays readable and the checks go on | Kept |
| Anything else: a member gone, compressed or past the cap, a member before the last grown or shrunk, the order changed, the last member rewritten | `ROLLED` once, with `lineMap()`; the checks stop until the owner's reopen fails | Mapped |

`MISSING` and `ROLLED` wait until two rescans in a row agree: a listing taken
while a writer renames the series can miss a member.

A re-pointed member keeps its handle when it holds the renamed file open
(`FileByteSource` with a file key). Otherwise it opens the new name, verified by
file key or by `byContent` digests, and records the digests again, so a
`ReopeningByteSource` reads the renamed file.

### LineMap

`LineMap` lists each old member with its old line base, its line count and its
new line base, or `DROPPED`. `map(line)` translates one old line number;
`dropped()` lists the members that left. New line bases count the lines of the
listing's order: a loaded member's index where it covers the file, the file
itself for the rest, a last line without LF included.
