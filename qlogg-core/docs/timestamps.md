# Timestamps

`core.time` parses a timestamp at the start of each line and finds lines by
time. It depends on `core.io`, `core.text`, `core.index` and
`core.concurrent`, not on `core.model`. Every call blocks; callers run it on a
worker. Module overview: [qlogg-core](../README.md).

## Built-in formats

| Format id | Layout | Zone |
|---|---|---|
| `iso` | `yyyy-MM-dd`, space or `T`, `HH:mm:ss`, then optional fraction and `Z`, `+hh:mm` or `+hhmm` | The offset when present, else the document zone |
| `ymd-slash`, `ymd-dot` | `yyyy/MM/dd HH:mm:ss`, `yyyy.MM.dd HH:mm:ss` | Document zone |
| `dmy-dot` | `dd.MM.yyyy HH:mm:ss`, or a two-digit year | Document zone |
| `dmy-slash`, `mdy-slash` | `dd/MM/yyyy HH:mm:ss`, `MM/dd/yyyy HH:mm:ss` | Document zone |
| `dmony` | `dd MMM yyyy HH:mm:ss` | Document zone |
| `syslog` | `MMM d HH:mm:ss`, no year | Document zone |
| `clf` | `[dd/MMM/yyyy:HH:mm:ss +hhmm]` anywhere in the first 256 chars | The offset |
| `jul` | `MMM dd, yyyy h:mm:ss a` | Document zone |
| `epoch` | 10 digits of seconds or 13 of milliseconds, optional fraction, then a non-digit | UTC |

`BuiltInFormat` scanners run at the line's first char and after one leading
`[`, and allocate nothing per line. They accept a fraction of 1-9 digits after
`.` or `,`; month names are English in any case. A field out of range (month
13, hour 24, Feb 29 of a non-leap year) is no timestamp.

## Parsing

`TimestampParser.parse` returns epoch nanoseconds in a `long` (1677-09-21 to
2262-04-11) or `NONE`, and `fractionDigits()` for display. Use one parser per
thread.

### Year and zone resolution

`TimeContext` carries the document zone and a reference year and month: the
file's modification time, or `current(zone)` for a document without one.

- `syslog` takes the reference year, or the year before for a month after the
  reference month.
- A two-digit year (`dmy-dot`, or `yy` in a user pattern) takes the century
  that puts the date nearest the reference month, the earlier on a tie
  (`nearestYear`).
- A local time takes `ZoneRules.getOffset`. In a DST gap and in an overlap
  that is the offset before the transition, so the repeated hour of a
  fall-back maps onto its first occurrence and reads as time going backward.
  The offset is cached per local hour with no transition.

### User formats

`UserFormat(name, locate, pattern)` compiles both at construction. It throws
`IllegalArgumentException` for a regex without a group named `ts`, a pattern
`DateTimeFormatter` refuses, or a value over 256 chars. Its `id()` is `user:`
and the name.

- The regex runs with `find` over the first 256 chars under `BoundedChars`
  (`8 * length + 65,536` reads). Reaching the bound or a `StackOverflowError`
  is no timestamp.
- The group's text resolves to an instant when it carries an offset or zone,
  else a local date and time, else a month and day with the `syslog` year
  rule. A time alone is no timestamp.
- The formatter resolves `SMART`, so Feb 30 becomes Feb 28.

### Format detection

`FormatDetector.detect` runs the built-ins over the first 200 non-empty lines
of the encoding head and picks the one that parses the most, with at least 3
lines and 20% of the sample. Ties go to table order, except that `mdy-slash`
wins a tie with `dmy-slash`, which parses more only when a first field is
over 12. It returns null when nothing qualifies.

## Lookups

`TimeReader` (one per worker) holds a 64 KiB heap buffer; a lookup loads 8 KiB
at a time, a sequential scan the whole buffer. It decodes the first 256 units
of a line through `LineDecoder`, so UTF-16 and UTF-32 parse like UTF-8. A line
that does not parse continues the stamped line above it.

| Method | Reads | Bound |
|---|---|---|
| `next(offset, end, token)` | Forward through the `BlockCache`: the first stamp on a line that starts at or after `offset` and before `end` | 10,000 lines or 1 MiB from `offset`; past it, null |
| `at(lineStart, token)` | The line's own stamp, else backward to the nearest stamped line | 10,000 lines or 1 MiB above the line; past it, null |
| `TimeSearch.locate` | Bisection over byte offsets with `next`: about log2 of the size in probes, plus two for the first bracket | Each probe as `next` |
| `TimeSearch.locateLinear` | Every line in file order, from the source directly so a full pass does not evict the cache; `ScanProgress` every 4 MiB and at the end | None; the token cancels between lines |

Both searches return the first stamp equal to the target, else the last stamp
before it, else the first stamp, or null without stamps. `locateLinear` stops
at the first stamp at or after the target.

### Bisection consistency

`locate` is exact only when stamps do not decrease in file order. It returns a
`Bisection`; `consistent()` false means the caller must run `locateLinear`.

- A probe is inconsistent when it lies more than `JITTER_NANOS` (1 s) outside
  its bracket, or stopped at the `next` bound (`stoppedAtBound`) with a stamp
  read elsewhere. With no stamp read, such a probe counts as at or after the
  target, so a region without stamps reports none without a scan.
- The bracket starts as the first stamp and a stamp in the last 8 KiB, and
  narrows to the nearest stamps read under and at or over the target.
- A step back within 1 s counts as jitter and lands `locate` within the jitter
  window.
- Disorder no probe touches goes unseen, so run `TimeSamples` first.

### Sampling

`TimeSamples.sample(reader, index, lineCount, count, token)` reads the stamp at
or after `LineIndex.nearestStart` of `count` evenly spaced lines, first and
last included. `nearestStart` returns the line itself in a full index and its
checkpoint in a sparse one, so sampling reads no line start.

- `descents()` counts samples earlier than the stamped sample before them; any
  descent marks the document out of order.
- The caller reserves `bytesFor(count)` (`8 * count + 64`) before sampling.
- The overload taking `long[] lines` samples those lines. Lines that snap to
  one start share one read, and a line past the last reads the last.

### Time spread

`TimeSpread.of(samples, lines, mode)` gives one level per pixel row from a
stamp at each row boundary: `floor(log2(ratio))` from 0 to 4, unshaded below
twice the average.

| Mode | Ratio |
|---|---|
| `PAUSES` | The row's elapsed time over the average row's |
| `ACTIVITY` | The row's lines per second over the whole span's; a row with lines and no elapsed time takes level 4 |

A row whose time runs backward is `DISORDER`. Backward rows, rows without lines
and rows missing a stamp stay out of the averages.
