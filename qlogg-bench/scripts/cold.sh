#!/bin/sh
# Cold-cache index and rare-literal search: RUNS rounds of the harness
# (literal:flagword) and then klogg (flagword) on FILE, a name inside SCRATCH.
# The page cache is not dropped (that needs root), so FILE must be larger than
# RAM for the runs to read from disk.
#
#   cold.sh KLOGG_TREE SCRATCH FILE [RUNS]
#   cold.sh ~/klogg-linux "$S" ascii-short-50g.log 2
#
# Build qlogg-bench first.
set -eu

[ $# -ge 3 ] || { echo "usage: cold.sh KLOGG_TREE SCRATCH FILE [RUNS]" >&2; exit 2; }
tree=$1
scratch=$(cd "$2" && pwd -P)
name=$3
runs=${4:-2}
scripts=$(cd "$(dirname "$0")" && pwd -P)
jar=$scripts/../target/benchmarks.jar
java=${JAVA_HOME:+$JAVA_HOME/bin/}java

round=1
while [ $round -le "$runs" ]; do
    echo "round=$round"
    "$java" -cp "$jar" io.github.ajdailey.qlogg.bench.Harness run "$scratch/$name" literal:flagword
    "$scripts/kdock.sh" "$tree" "$scratch" /scripts/run-reference.sh "/data/$name" flagword
    round=$((round + 1))
done
