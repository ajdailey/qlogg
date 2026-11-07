# qlogg-bench

Benchmarks: a headless harness, a log generator and JMH probes. Not shipped
and not scanned by SpotBugs. Inputs go to a scratch directory, never the
repository; delete generated files when done.

```sh
./mvnw -pl qlogg-bench -am package -DskipTests
```

---

## Log generator

`LogGenerator` writes deterministic files: the same profile, size and seed give
the same bytes. Every profile except `tiny` puts `flagword` in 0.1% and
`blockterm` in 10% of the lines, so one file serves both match densities.

| Profile | Lines |
|---|---|
| `ascii-short` | ASCII, 45 to about 120 bytes, average about 85 |
| `utf8-mixed` | UTF-8, about 45 to 2,000 characters; 5% carry non-ASCII words |
| `utf16le` | `ascii-short` as UTF-16LE after a byte order mark |
| `long-lines` | ASCII, 0 to 7 KiB, plus one 1 MiB line in 2,000 |
| `no-final-newline` | `ascii-short` without a newline after the last line |
| `tiny` | 10 bytes (`mock12345` and LF); `blkt` starts 1% and `flag` 0.1% of them |
| `timed` | `ascii-short` with `yyyy-MM-dd HH:mm:ss.SSS` stamps in file order, 0 to 9 ms apart; one entry in 50 is followed by a 9-line stack trace |

## Harness

`Harness` builds the same core services as the application (budget from the
runtime, `BlockCache.fromBudget`, every CPU) and prints one `key=value` line
per measurement. Run it in its own JVM so heap and diagnostic options apply:

```sh
H="java -cp qlogg-bench/target/benchmarks.jar io.github.ajdailey.qlogg.bench.Harness"
$H generate ascii-short 1000000000 <scratch>/ascii-short-1g.log [seed]
$H run <scratch>/ascii-short-1g.log [SEARCH ...]
$H run <scratch>/ascii-short-1g.log 'expr:"flagword" or "blockterm"' \
    'expr-regex:"worker-1[0-5]" and not "blockterm"'
$H multi literal:blockterm <scratch>/a.log <scratch>/b.log <scratch>/c.log
$H filtered <scratch>/ascii-short-1g.log literal:flagword 3 3
$H time <scratch>/timed-1g.log [go-tos]
$H tail <scratch>/tail.log 50000 20
xvfb-run -a env GDK_BACKEND=x11 $H startup qlogg-dist/target/qlogg.jar \
    <scratch>/ascii-short-100m.log <scratch>/startup-jar [runs]
xvfb-run -a env GDK_BACKEND=x11 $H startup <scratch>/bundle/qlogg/bin/qlogg \
    <scratch>/ascii-short-100m.log <scratch>/startup-bundle [runs]
```

| Command | Measures |
|---|---|
| `generate` | Writes a new file of at least the given size; fails when it exists |
| `run` | Open; index (time, CPU, lines, index bytes per line, sparse or full); each search (time, CPU, matches, result-set bytes), by default `flagword`, `blockterm` and a regex; 10,000 random 50-line reads (p50, p99, max); the last screen; heap after GC with the result sets held; every share's reservation; process CPU and peak RSS (`VmHWM`, Linux) |
| `multi` | One `SEARCH` over several files, as a search across open files runs it: opens and indexes each; searches them in turn (`multi.inTurn.ms`), then starts one search per file on the shared engine (`multi.atOnce.ms`, CPU, each file's time and matches, total matches); heap after GC, shares, peak RSS |
| `filtered` | Filtered-result generation, as `FilteredController` runs it: `SEARCH`, one line in 1,000 marked, context rows for `BEFORE` and `AFTER` lines (0 by default; `filtered.context.ms`), the marks outside them (`filtered.extras.ms`), the row count; 10,000 random 50-row screens, mapped to lines and read (p50, p99, max of each); heap after GC, shares, peak RSS |
| `time` | Timestamp navigation: format detection on the 64 KiB head, the 256 order samples, `GO_TOS` (1,000 by default) go to times by bisection to random times and as many caret-time reads of random lines (p50, p99, max of each), one linear pass; heap after GC, shares, peak RSS. A file with no detected format ends after the detection |
| `tail` | Appends lines at the given rate for the given seconds to a new file opened with the default monitor settings; latency from the write to the line entering the index (p50, p99, max) |
| `startup` | Launches QLogg `runs` times (6 by default), one at a time, with `--settings <dir> -m -n -d debug <file>`, and times each from process start to the first paint that shows file text (`LogCanvas` logs it at DEBUG); the first run, which also fills the settings directory and extracts the SWT natives, is reported apart from the median of the rest |

A `SEARCH` is `literal:TEXT`, `literal-i:TEXT` (case folded), `regex:PATTERN`,
or a search-field expression with combining on: `expr:TEXT` (literal terms)
or `expr-regex:TEXT` (regex terms), terms in double quotes, operators `and`,
`or`, `xor`, `not` and parentheses.

`startup` takes `qlogg.jar`, run with the harness JVM's `java` and
`-XX:MaxRAMPercentage=50`, or an executable launcher: extract the bundle
archive from `dist/` first, because the staging tree under
`qlogg-dist/target` has no execute bits. QLogg writes only inside the
settings directory (on Linux `--settings` also moves `user.home` there),
which holds each run's output as `startup-<n>.out`. Under Xvfb,
`GDK_BACKEND=x11` keeps GTK off a Wayland session.

### Heap

Every command that opens a document uses the shipped launchers' heap,
`-XX:MaxRAMPercentage=50`: a harness JVM started without a heap option
relaunches itself with it and the launchers' `-XX:GCTimeRatio=1`, and each
command except `tail` prints the option in force as `heap.option`. A heap option on the
command line (`-Xmx`, `-XX:MaxRAMPercentage`, `-XX:MaxRAM`,
`-XX:MaxHeapSize`) runs in place; `-Dqlogg.bench.heap=jvm` runs in place at
the JVM default (25% of RAM). Give a run with JFR or NMT options a heap
option: without one, both the first JVM and the relaunched one apply them.

## Comparison scripts

`scripts/` runs the reference viewer beside the harness. Each script takes
the path of an extracted reference-viewer Linux tree (`usr/bin`, `usr/lib`);
nothing in the repository bundles or downloads it. The viewer runs in a
`--network none` container under Xvfb and is driven with `xdotool`; its
times are its own log lines, and its peak RSS is `VmHWM` read before it
exits. Generated files and output go in a scratch directory `$S`. Build
`qlogg-bench` first; the scripts use `$JAVA_HOME/bin/java` when set.

```sh
qlogg-bench/scripts/kdock.sh <tree> $S /scripts/run-reference.sh /data/<file> [PATTERN ...]
qlogg-bench/scripts/compare.sh <tree> $S 5 ascii-short-100m.log ascii-short-1g.log
HEAP=-Xmx256m qlogg-bench/scripts/heap-run.sh $S/tiny-10g.log $S/runs/tiny \
    literal:blkt literal:flag 'regex:^blkt\d+7$'
qlogg-bench/scripts/cold.sh <tree> $S ascii-short-50g.log 2
cp qlogg-dist/target/qlogg.jar $S/
qlogg-bench/scripts/kdock.sh <tree> $S /scripts/ui-heap.sh /data/qlogg.jar \
    /data/tiny-10g.log blkt /data/uiheap/tiny
```

| Script | Does |
|---|---|
| `kdock.sh` | Runs a command in the container, with `$S` at `/data`, a copy of the tree at `/viewer` (made in `$S` on first use; the viewer writes its configuration beside its binary) and `scripts/` at `/scripts`. Builds the image `qlogg-bench-x:local` from `qlogg-build:local` (`docker compose build`) on first use |
| `run-reference.sh` | In the container: opens the file, waits for indexing, then runs each pattern (default `flagword`, `blockterm` and the harness regex) through the search box in the viewer's default regex mode; prints index and search milliseconds and peak RSS |
| `compare.sh` | `RUNS` rounds; each round runs `Harness run` and then `run-reference.sh` on every file after a `cat` warms the cache. Raw output in `$S/compare/<file>.<tool>.<round>`; prints the median of every time and of peak RSS per file and tool. `RUNS` 0 reprints the medians |
| `heap-run.sh` | `Harness run` at `$HEAP` (default `-Xmx256m`) with JFR, a GC log and native memory tracking; writes `<prefix>.out`, `.jfr`, `.gc.log`, `.nmt` (`VM.native_memory summary` every 2 s) and `.heap` (`jdk.GCHeapSummary`) |
| `ui-heap.sh` | In the container: opens the file in `qlogg.jar` at `$HEAP` (default `-Xmx256m`) with JFR and native memory tracking, searches the text through the search field, goes to the end of the file; prints NMT committed, the heap before and after a full GC, peak RSS and the count of `OutOfMemoryError` lines; writes `<prefix>.out`, `.jfr`, `.heap` and `.png` |
| `cold.sh` | Rounds of `Harness run ... literal:flagword` and then the viewer with `flagword`. The page cache is not dropped, so the file must be larger than RAM |

## JMH probes

```sh
java -Dqlogg.bench.dir=<scratch> -jar qlogg-bench/target/benchmarks.jar ReadProbe
java -Dqlogg.bench.dir=<scratch> -jar qlogg-bench/target/benchmarks.jar SearchProbe
java -Dqlogg.bench.dir=<scratch> -jar qlogg-bench/target/benchmarks.jar SearchTrimProbe
java -Dqlogg.bench.dir=<scratch> -jar qlogg-bench/target/benchmarks.jar TimeProbe -p fileMib=2560
```

Each fork runs at `-XX:MaxRAMPercentage=50 -XX:GCTimeRatio=1`;
`-jvmArgsPrepend -XX:MaxRAMPercentage=25` runs the JVM default heap instead.

| Benchmark | Measures |
|---|---|
| `ReadProbe` | Sequential LF count over a generated log (`-p fileMib=1024` by default): `positional` reads into one 4 MiB direct buffer, the indexer's read path; `mapped` reads `MappedByteBuffer` windows of 1 GiB. Both use the same counting loop. Warm page cache |
| `SearchProbe` | Whole-file `SearchEngine` search of a generated, indexed log (`-p fileMib=1024`) with the default settings and every CPU: `literal` (byte search), `literalIgnoreCase` (ASCII folding on bytes), `regex` (decoded text). Warm page cache |
| `SearchTrimProbe` | Repeated whole-file literal searches for `blockterm` in an `ascii-short` file (`-p fileMib=16,256`), with `trim=true` (the shipped engine: the read-buffer pool drops back to `ReadBuffers.MIN_BUFFERS` when a search ends with `IO_BUFFERS` short of a full pool, so the next search allocates its direct buffers again) against `trim=false` (the pool keeps them). The difference is the trim's cost per search; at the default heap the share holds another full pool and neither trims. In package `core.search` for the engine's package-private constructor. Warm page cache |
| `TimeProbe` | Over a generated, indexed `timed` log (`-p fileMib=1024` by default): `parse`, the `iso` scanner per line over the 64 KiB head (ns per line); `detect`, `FormatDetector` on that head; `locate`, one go to time by bisection to a random time; `linear`, a `locateLinear` pass over the whole file on one thread; `samples`, 4,000 `TimeSamples`. Warm page cache |

## Scrolling frame time

`ScrollFrameProbe` in `qlogg-ui-swt` opens a file in the main window (1100 x
700) and scrolls 500 times by one line and 500 times by one page. A frame is
the time from the scroll to the end of the first paint that shows the new top
line with its text. It runs only when `qlogg.bench.file` is set:

```sh
xvfb-run -a ./mvnw -pl qlogg-ui-swt -am test -Dtest=ScrollFrameProbe \
  -Dsurefire.failIfNoSpecifiedTests=false -Dqlogg.bench.file=<file>
SWT_GTK4=1 xvfb-run -a ./mvnw ...   # the same under GTK 4
```

## UI-thread stall at open

`OpenStallProbe` in `qlogg-ui-swt` opens and closes a file in the main window
`qlogg.bench.opens` times (10 by default) after the window settles for
`qlogg.bench.settle` ms (1000 by default), and prints the largest lateness of
a 10 ms UI timer for each open and close. The first open also carries one-time
class loading and GTK setup. It runs only when `qlogg.bench.file` is set:

```sh
xvfb-run -a ./mvnw -pl qlogg-ui-swt -am test -Dtest=OpenStallProbe \
  -Dsurefire.failIfNoSpecifiedTests=false -Dqlogg.bench.file=<file>
```

## Row paint time

`RowPaintProbe` in `qlogg-ui-swt` writes two 2,000-line files of about 150
columns, one ASCII and one whose lines start with CJK, Hangul, fullwidth and
combining characters, opens each in the main window (1100 x 700) and repaints
the first screen 500 times. `RowPainter` draws an ASCII row as a string and
lays out a non-ASCII row in a `TextLayout`. The non-ASCII screen is timed
again with highlight spans and a selection (`nonAsciiSpans`), then with
whitespace markers as well (`nonAsciiMarked`). Each non-ASCII case is also
timed scrolling a screen down or up per frame (`...Scroll`), so every row is
laid out afresh; the repaint cases reuse the last paint's layouts. It prints
the paint time per frame (p50, p90, p99, max, mean) for each case and the
ratio of the non-ASCII to the ASCII mean. It runs only when
`qlogg.bench.rowPaint` is `true`; `-Dqlogg.shots=<dir>` also writes the
window after each case as `rows-<case>.png` (not under GTK 4, where
`Control.print` draws nothing). A run can outlast the test hang
bound of 120 s, so the command raises it:

```sh
xvfb-run -a ./mvnw -pl qlogg-ui-swt -am test -Dtest=RowPaintProbe \
  -Dsurefire.failIfNoSpecifiedTests=false -Dqlogg.bench.rowPaint=true \
  -Dqlogg.test.hangSeconds=900
```

## Testing

| Class | Covers |
|---|---|
| `LogGeneratorTest` | The same seed writing the same bytes; both token densities in `ascii-short`; 10-byte `tiny` lines; the `utf16le` byte order mark; `no-final-newline` ending with text; non-ASCII lines in `utf8-mixed`; `timed` stamps in order with stack traces |
| `HarnessTest` | `multi` counting the same matches in turn and at once; `filtered` with and without context listing matches and marks; `time` running every lookup on a timed file and stopping after detection without a format |
