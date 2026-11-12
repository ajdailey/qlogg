# File monitoring

`core.watch` checks a watched file for growth, truncation and replacement.
Module overview: [qlogg-core](../README.md). What a document does with each
result is in [Documents](documents.md#watching).

## FileMonitor

`FileMonitor.watch(path, listener)` starts a `qlogg-follow-` thread that calls
`MonitorListener.possiblyChanged` at once, then every `pollMillis` (1,000 by
default) and, with `useWatchService`, on a parent-directory event for the file
name, at most once per 50 ms.

- The poll stays on with directory events, because a `WatchService` misses
  events on network mounts and polls on macOS.
- One `WatchService` serves every directory. Its dispatcher thread ends when
  no directory is registered or the workers close.
- Registration runs on the watch's own thread; a directory that cannot be
  registered is polled only.
- `cancel` ends the watch within one check.
- An exception from the listener goes to the thread's uncaught-exception
  handler, once per run of failing checks, and the watch continues.
- `setSettings` replaces the settings. The interval applies from each watch's
  next wait; directory events apply to watches started afterwards.

## ChangeDetector

`ChangeDetector.classify(baseline, path)` compares the file at the path,
following a symbolic link, with what was indexed. `Baseline` holds the
identity at open, the covered bytes, the head digest, and the tail digest with
its modification time. The detector opens the file only to read a digest and
closes it at once.

The rows apply in order; the first match decides:

| Observation | Result |
|---|---|
| No regular file | `MISSING` |
| Another file key; where there is none (Windows), another creation time or real path | `REPLACED` |
| Size below the covered bytes | `TRUNCATED` |
| CRC32C of the head bytes differs: min(4 KiB, size at open), extended over indexed bytes up to 4 KiB | `REPLACED` |
| Size above the covered bytes, CRC32C of the last 4 KiB before the covered end differs | `REPLACED` |
| Size above the covered bytes | `APPENDED` |
| Same size, newer modification time, CRC32C of the last 4 KiB differs | `MODIFIED` |
| Otherwise | `UNCHANGED` |

### Covered bytes and the tail digest

- While indexing runs, the covered bytes are at least the size at open and
  the size indexing reads to, so a check before the first progress event does
  not report the indexed file as appended.
- `LogDocument` passes the tail digest only when it ends at the covered bytes;
  otherwise both tail rows are skipped for that check. An indexing pass
  publishes its last snapshot before it records the new tail.
- A completed or canceled pass records the tail, so after Stop Loading growth
  whose last 4 KiB before the indexed end changed is `REPLACED`, not
  `APPENDED`.
- The growth row with the tail digest catches a copy-truncate rotation that
  regrows past the covered bytes between two checks: the file key stays the
  same, and the head digest of a file opened short covers only the bytes
  recorded so far. It costs one 4 KiB read per growth check.

### Platforms without a file key

- Creation time decides only without a file key, because JDK 21 on Linux
  reports the modification time as the creation time.
- On Windows a file recreated under the same name within 15 s can inherit the
  old creation time (NTFS tunneling, assumed from the Windows documentation),
  so the head digest decides there. A check that sees the new file before its
  first write lands reports `TRUNCATED`, which reopens the path as `REPLACED`
  does.
- The real path is resolved only without a file key, on the watch's thread; a
  path that does not resolve reads as `MISSING`. The comparison reports a
  retargeted link even when the new target has the old creation time and
  starts with the old bytes.
- A plain file replaced in the same millisecond with the same starting bytes
  stays undetected and reads as `APPENDED`.
