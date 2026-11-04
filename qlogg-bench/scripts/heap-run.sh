#!/bin/sh
# Harness run at a fixed heap with JFR, a GC log and native memory tracking.
# Writes PREFIX.out (harness output), PREFIX.jfr, PREFIX.gc.log, PREFIX.nmt
# (VM.native_memory summary every 2 s) and PREFIX.heap (heap after each GC).
#
#   heap-run.sh FILE PREFIX [SEARCH...]
#   HEAP=-Xmx256m heap-run.sh "$S/tiny-10g.log" "$S/runs/tiny" literal:blkt literal:flag 'regex:^blkt\d+7$'
#
# HEAP defaults to -Xmx256m. Build qlogg-bench first.
set -eu

[ $# -ge 2 ] || { echo "usage: heap-run.sh FILE PREFIX [SEARCH...]" >&2; exit 2; }
file=$1
prefix=$2
shift 2
scripts=$(cd "$(dirname "$0")" && pwd -P)
jar=$scripts/../target/benchmarks.jar
bin=${JAVA_HOME:+$JAVA_HOME/bin/}
mkdir -p "$(dirname "$prefix")"

# A heap option on the command line keeps the harness in this JVM, so $! is
# the process to sample.
"${bin}java" ${HEAP:--Xmx256m} -XX:NativeMemoryTracking=summary \
    -XX:StartFlightRecording=filename="$prefix.jfr",settings=profile \
    -Xlog:gc:file="$prefix.gc.log" \
    -cp "$jar" io.github.ajdailey.qlogg.bench.Harness run "$file" "$@" >"$prefix.out" &
pid=$!
: >"$prefix.nmt"
while kill -0 $pid 2>/dev/null; do
    "${bin}jcmd" $pid VM.native_memory summary >>"$prefix.nmt" 2>/dev/null || true
    sleep 2
done
wait $pid
"${bin}jfr" print --events jdk.GCHeapSummary "$prefix.jfr" >"$prefix.heap"
cat "$prefix.out"
