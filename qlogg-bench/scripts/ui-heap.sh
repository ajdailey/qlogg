#!/bin/sh
# Runs inside the kdock.sh container: opens FILE in qlogg.jar under Xvfb at a
# fixed heap with JFR and native memory tracking, searches TEXT through the
# search field, goes to the end of the file, then prints NMT committed, the heap
# before and after a full GC, and peak RSS. Writes PREFIX.out (QLogg's log),
# PREFIX.jfr and PREFIX.png (the window at the end of the run).
#
#   ui-heap.sh JAR FILE TEXT PREFIX
#   ui-heap.sh /data/qlogg.jar /data/tiny-10g.log blkt /data/uiheap/tiny
#
# HEAP defaults to -Xmx256m.
set -eu

[ $# -eq 4 ] || { echo "usage: ui-heap.sh JAR FILE TEXT PREFIX" >&2; exit 2; }
jar=$1
file=$2
text=$3
prefix=$4
bound=${UI_BOUND:-600}
mkdir -p "$(dirname "$prefix")"
log=$prefix.out

Xvfb :78 -screen 0 1280x800x24 -nolisten tcp >/dev/null 2>&1 &
xvfb=$!
export DISPLAY=:78 GDK_BACKEND=x11
i=0
until xdpyinfo >/dev/null 2>&1; do
    i=$((i + 1))
    [ $i -lt 100 ] || { echo "Xvfb did not start" >&2; exit 1; }
    sleep 0.1
done

java ${HEAP:--Xmx256m} -XX:NativeMemoryTracking=summary \
    -XX:StartFlightRecording=filename="$prefix.jfr",settings=profile \
    -jar "$jar" --settings "$prefix.settings" -m -n -d 1 "$file" >"$log" 2>&1 &
pid=$!

# Waits for a line matching $1 in the log; fails after $bound seconds.
await() {
    n=0
    until grep -q "$1" "$log"; do
        n=$((n + 1))
        if [ $n -gt $((bound * 10)) ] || ! kill -0 $pid 2>/dev/null; then
            echo "qlogg: no '$1' line" >&2
            cat "$log" >&2
            exit 1
        fi
        sleep 0.1
    done
}

await 'Indexing .* done'
window=$(xdotool search --sync --onlyvisible --pid $pid | head -n 1)
xdotool windowfocus --sync "$window" key --clearmodifiers ctrl+s
xdotool type --delay 0 -- "$text"
xdotool key Return
await 'Search of '
xdotool mousemove --window "$window" 300 200 click 1
xdotool key --clearmodifiers ctrl+End
sleep 2
import -window root "$prefix.png"

jcmd $pid VM.native_memory summary | sed -n 's/^Total: *//p' | sed 's/^/ui.nmt=/'
jcmd $pid GC.heap_info | sed -n 's/.*\(garbage-first heap.*\)/ui.heap.before=\1/p'
jcmd $pid GC.run >/dev/null
jcmd $pid GC.heap_info | sed -n 's/.*\(garbage-first heap.*\)/ui.heap.after=\1/p'
echo "ui.rss.peak=$(sed -n 's/^VmHWM:[[:space:]]*//p' "/proc/$pid/status")"
grep -c OutOfMemoryError "$log" | sed 's/^/ui.oom=/' || true

kill $pid
wait $pid 2>/dev/null || true
kill $xvfb
jfr print --events jdk.GCHeapSummary "$prefix.jfr" >"$prefix.heap"
