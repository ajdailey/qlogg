#!/bin/sh
# Runs inside the kdock.sh container: opens FILE in klogg under Xvfb, waits for
# indexing, then searches each PATTERN through the search box (Ctrl+S, select
# all, BackSpace, the text, Return) in klogg's default regex mode. Prints
# klogg's own "Indexing done" and "Searching done" log lines and its peak RSS.
#
#   run-reference.sh FILE [PATTERN...]   default patterns: flagword blockterm and a regex
set -eu

file=$1
shift
[ $# -gt 0 ] || set -- flagword blockterm 'worker-1[0-5]\] #\d+7 '
klogg=/viewer/usr/bin/klogg
log=$(mktemp -d)/klogg.out
bound=${KLOGG_BOUND:-600}

Xvfb :77 -screen 0 1280x800x24 -nolisten tcp >/dev/null 2>&1 &
xvfb=$!
export DISPLAY=:77
i=0
until xdpyinfo >/dev/null 2>&1; do
    i=$((i + 1))
    [ $i -lt 100 ] || { echo "Xvfb did not start" >&2; exit 1; }
    sleep 0.1
done

# The crash handler has no RUNPATH; it finds the bundled libraries here.
LD_LIBRARY_PATH=/viewer/usr/lib QT_QPA_PLATFORM=xcb \
    "$klogg" -d 1 -n "$file" >"$log" 2>&1 &
pid=$!

peak_rss() {
    sed -n 's/^VmHWM:[[:space:]]*//p' "/proc/$pid/status" 2>/dev/null || echo n/a
}

# Waits for the Nth line matching $1 in the log; fails after $bound seconds.
await() {
    n=0
    while [ "$(grep -c "$1" "$log" || true)" -lt "$2" ]; do
        n=$((n + 1))
        if [ $n -gt $((bound * 10)) ] || ! kill -0 $pid 2>/dev/null; then
            echo "klogg: no '$1' line" >&2
            cat "$log" >&2
            exit 1
        fi
        sleep 0.1
    done
}

await 'Indexing done' 1
echo "klogg.file=$file"
echo "klogg.index.ms=$(sed -n 's/.*Indexing done, took \([0-9.]*\) ms.*/\1/p' "$log" | tail -n 1)"
echo "klogg.rss.afterIndex=$(peak_rss)"

window=$(xdotool search --sync --onlyvisible --pid $pid | head -n 1)
count=0
for pattern in "$@"; do
    count=$((count + 1))
    xdotool windowfocus --sync "$window" key --clearmodifiers ctrl+s ctrl+a BackSpace
    xdotool type --delay 0 -- "$pattern"
    xdotool key Return
    await 'Searching done' $count
    printf 'klogg.search[%s].ms=%s\n' "$pattern" \
        "$(sed -n 's/.*Searching done, overall duration \([0-9.]*\) ms.*/\1/p' "$log" | tail -n 1)"
done
echo "klogg.rss.peak=$(peak_rss)"

kill $pid
wait $pid 2>/dev/null || true
kill $xvfb
