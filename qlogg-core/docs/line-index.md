# Line index

`core.index` holds each document's line starts and builds them in parallel.
Module overview: [qlogg-core](../README.md).

## GrowableLineIndex

`GrowableLineIndex` holds one document's line starts. One `Indexer` operation
at a time writes it; a second gets `IllegalStateException`. Any thread reads
`snapshot()`, an immutable `LineIndex` whose `generation()` increases with each
publication.

- Lines end at LF only. `lineEnd` excludes the LF; a CR before it is part of
  the line until `LineDecoder` drops it.
- A region ending without LF counts its partial last line. `resumeOffset()`
  is the start of the last line, so a later operation rescans a partial line
  as the file grows.

### Storage modes

| Mode | Storage | Cost per line | Lookup |
|---|---|---|---|
| Full | Blocks of 128 lines: base offset, bit width, word index (13 B), then the 127 line lengths packed at the block's width into 64 KiB `long[]` pages | (13 + 8 x ceil(127 x w / 64)) / 128 B: 0.60 at w = 4, 1.10 at w = 8, 2.10 at w = 16 | In memory: binary search over block bases, then at most 127 lengths summed |
| Sparse | A checkpoint (line, offset) at the first line 1,024 lines or 1 MiB past the previous one | 16 B per checkpoint, about 0.016 B per line for short lines | Reads forward through the `BlockCache` from the nearest checkpoint, or from the snapshot's last found line start when that lies between the checkpoint and the target, so consecutive lookups read each line once. At most 1 MiB is read before the target line starts. `lineAt` stops at the target offset, so an offset inside a long line reads only its distance from the checkpoint. A failed read throws `UncheckedIOException` |

### Reservation and fallback

Every allocation (metadata chunks of 1,024 blocks, pages, checkpoint chunks of
1,024, doubling directories) is reserved from `LINE_INDEX` first, and
`retainedBytes()` equals what is reserved.

- A refused reservation converts the index in place to sparse mode, which
  lasts until the index is closed.
- When even the sparse index is refused, indexing fails with
  `IndexCapacityException` naming the bytes needed and available.
- `close()` returns the reservation; published snapshots stay readable.

## Indexer

`Indexer.index` starts a coordinator on a `qlogg-index-` virtual thread. The
coordinator reads blocks into `ReadBuffers` and submits each to the CPU pool,
which marks the LF units in a bitmap, eight bytes at a time for 1- and 2-byte
units. The coordinator appends results in file order and publishes a snapshot
at most every 100 ms and once at the end.

- Blocks are 4 MiB by default and a multiple of 8, so no UTF-16 or UTF-32
  unit straddles two. Only units at multiples of the unit width count, so a
  `0x0A` byte inside a UTF-16 unit is not an LF.
- A trailing partial UTF-16 or UTF-32 unit is not indexed until the file
  grows.
- At most `maxBlocksInFlight` blocks (default two per CPU thread) are read
  but not appended; fewer when `IO_BUFFERS` is short.
- Each `Indexer` has its own `ReadBuffers` pool and trims it when an
  operation ends. `Indexer(workers, settings, false)` keeps the pool until
  `trim()`, for a run that indexes many files in turn
  ([Search in a folder](search.md#search-in-a-folder)).
- `IndexListener` calls run on the coordinator thread and must not block
  toward the UI thread.

### Ending an operation

| Cause | Outcome |
|---|---|
| Cancellation, checked before each read and while waiting for a scan | `CANCELED` within one block |
| A read on a closed source | `CANCELED` |
| An I/O error or a refused index | `FAILED` |

At its end an operation waits up to 5 s for scans still running. A scan that
outlives the wait returns its buffer to the pool when it finishes; an atomic
hand-off decides whether the coordinator or the scan returns it. A failed read
also returns its buffer, so the next pass is never left waiting for one.
