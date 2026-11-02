# qlogg-bench

Benchmarks: a headless harness, a log generator and JMH probes. Not shipped
and not scanned by SpotBugs. Inputs go to a scratch directory, never the
repository; delete generated files when done.

```sh
./mvnw -pl qlogg-bench -am package -DskipTests
```

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

## Harness

`Harness` builds the same core services as the application (budget from the
runtime, block cache of its share, every CPU) and prints one `key=value` line
per measurement. Run it in its own JVM so heap and diagnostic options apply:

```sh
H="java -cp qlogg-bench/target/benchmarks.jar io.github.ajdailey.qlogg.bench.Harness"
$H generate ascii-short 1000000000 <scratch>/ascii-short-1g.log [seed]
$H run <scratch>/ascii-short-1g.log [literal:TEXT | literal-i:TEXT | regex:PATTERN ...]
$H tail <scratch>/tail.log 50000 20
```

| Command | Measures |
|---|---|
| `generate` | Writes a new file of at least the given size; fails when it exists |
| `run` | Open; index (time, CPU, lines, index bytes per line, sparse or full); each search (time, CPU, matches, result-set bytes), by default `flagword`, `blockterm` and a regex; 10,000 random 50-line reads (p50, p99, max); the last screen; heap after GC with the result sets held; every share's reservation; process CPU and peak RSS (`VmHWM`, Linux) |
| `tail` | Appends lines at the given rate for the given seconds to a new file opened with the default monitor settings; latency from the write to the line entering the index (p50, p99, max) |

For the heap run, add `-Xmx256m -XX:NativeMemoryTracking=summary
-XX:StartFlightRecording=filename=<f>.jfr,settings=profile -Xlog:gc:file=<f>.log`
and read native memory with `jcmd <pid> VM.native_memory summary` while it
runs.

## JMH probes

```sh
java -Dqlogg.bench.dir=<scratch> -jar qlogg-bench/target/benchmarks.jar ReadProbe
java -Dqlogg.bench.dir=<scratch> -jar qlogg-bench/target/benchmarks.jar SearchProbe
```

| Benchmark | Measures |
|---|---|
| `ReadProbe` | Sequential LF count over a generated log (`-p fileMib=1024` by default): `positional` reads into one 4 MiB direct buffer, the indexer's read path; `mapped` reads `MappedByteBuffer` windows of 1 GiB. Both use the same counting loop. Warm page cache |
| `SearchProbe` | Whole-file `SearchEngine` search of a generated, indexed log (`-p fileMib=1024`) with the default settings and every CPU: `literal` (byte search), `literalIgnoreCase` (ASCII folding on bytes), `regex` (decoded text). Warm page cache |

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
