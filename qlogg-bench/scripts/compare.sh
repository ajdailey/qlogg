#!/bin/sh
# QLogg against klogg on the same files: RUNS rounds, each running the harness
# and then klogg (kdock.sh, run-reference.sh) on every FILE after a cat warms the
# page cache. FILEs are names inside SCRATCH. Raw output goes to
# SCRATCH/compare/<file>.<tool>.<round>; the medians are printed at the end.
#
#   compare.sh KLOGG_TREE SCRATCH RUNS FILE...
#   compare.sh ~/klogg-linux "$S" 5 ascii-short-100m.log ascii-short-1g.log
#
# Patterns: the harness defaults and run-reference.sh's, the same three searches.
# Build qlogg-bench first (./mvnw -pl qlogg-bench -am package -DskipTests).
set -eu

[ $# -ge 4 ] || { echo "usage: compare.sh KLOGG_TREE SCRATCH RUNS FILE..." >&2; exit 2; }
tree=$1
scratch=$(cd "$2" && pwd -P)
runs=$3
shift 3
scripts=$(cd "$(dirname "$0")" && pwd -P)
jar=$scripts/../target/benchmarks.jar
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
out=$scratch/compare
mkdir -p "$out"

round=1
while [ $round -le "$runs" ]; do
    for name in "$@"; do
        cat "$scratch/$name" >/dev/null
        "$java" -cp "$jar" io.github.ajdailey.qlogg.bench.Harness run "$scratch/$name" \
            >"$out/$name.qlogg.$round"
        cat "$scratch/$name" >/dev/null
        "$scripts/kdock.sh" "$tree" "$scratch" /scripts/run-reference.sh "/data/$name" \
            >"$out/$name.klogg.$round"
        echo "round $round $name done" >&2
    done
    round=$((round + 1))
done

# Median of the numbers on standard input (the first field of each line).
median() {
    sort -n | awk '{ v[NR] = $1 } END { if (NR) print (NR % 2 ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}

for name in "$@"; do
    for tool in qlogg klogg; do
        # Keys can hold spaces (a regex), so they are read one per line.
        cat "$out/$name.$tool".* | sed -n 's/^\([^=]*\(\.ms\|rss\.peak\)\)=.*/\1/p' | sort -u |
            while IFS= read -r key; do
                value=$(cat "$out/$name.$tool".* | K="$key=" awk 'index($0, ENVIRON["K"]) == 1' |
                    sed 's/^[^=]*=\([0-9.]*\).*/\1/' | median)
                printf '%s\t%s\t%s\n' "$name" "$key" "$value"
            done
    done
done
