# Rotated sets

`core.rotate` reads a log and its rotated files as one byte stream with one
line index. Module overview: [qlogg-core](../README.md).

## Discovery

`RotatedSet.discover(given, held, fallback, forced, ignoreCase)` lists the
directory of `given` once, not recursively, stats each matching file and reads
the first 64 KiB of each member. It blocks; call it on a worker.

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
