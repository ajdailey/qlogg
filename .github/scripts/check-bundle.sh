#!/usr/bin/env bash
# Unpacks the $TARGET bundle from dist/, verifies a macOS bundle's signature,
# checks that its runtime reports os.arch $EXPECTED_ARCH, has jdk.charsets and
# no keytool, and starts the bundle's launcher until the main window opens. A
# second launch then hands a file to the running bundle and exits 0. The same
# run follows with the universal zip's bin/qlogg, which has no runtime, on
# $JAVA_HOME. Linux uses a private Xvfb display.
set -euo pipefail

work="$PWD/bundle-run"
rm -rf "$work"
mkdir -p "$work"
case "$TARGET" in
  linux-*)
    tar -xzf dist/qlogg-*-"$TARGET".tar.gz -C "$work"
    launcher="$work/qlogg/bin/qlogg"
    runtime="$work/qlogg/runtime"
    ;;
  macos-*)
    unzip -q dist/qlogg-*-"$TARGET".zip -d "$work"
    launcher="$work/QLogg.app/Contents/MacOS/QLogg"
    runtime="$work/QLogg.app/Contents/runtime/Contents/Home"
    ;;
  *)
    echo "unsupported target $TARGET" >&2
    exit 1
    ;;
esac
runtime="$(cd "$runtime" && pwd -P)"

if [ "$(uname -s)" = Darwin ]; then
  codesign --verify --deep --strict --verbose=2 "$work/QLogg.app"
fi
"$runtime/bin/java" --list-modules | grep '^jdk\.charsets@' >/dev/null \
  || { echo "FAIL: runtime lacks jdk.charsets" >&2; exit 1; }
[ ! -e "$runtime/bin/keytool" ] || { echo "FAIL: runtime has keytool" >&2; exit 1; }

arch="$("$runtime/bin/java" -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^ *os\.arch = //p')"
echo "runtime $runtime: os.arch=$arch, expected $EXPECTED_ARCH"
[ "$arch" = "$EXPECTED_ARCH" ]

if [ "$(uname -s)" = Linux ]; then
  Xvfb :99 -screen 0 1280x1024x24 >/dev/null 2>&1 &
  xvfb=$!
  trap 'kill "$xvfb" 2>/dev/null || true' EXIT
  export DISPLAY=:99 GDK_BACKEND=x11
fi

printf 'mock line\n' >"$work/app.log"
printf 'mock line\n' >"$work/handed.log"

# Waits up to 60 s for line $2 in file $1 while process $3 runs.
await_line() {
  for _ in $(seq 1 60); do
    grep -q "$2" "$1" && return 0
    kill -0 "$3" 2>/dev/null || return 0
    sleep 1
  done
}

# Runs launcher $1 until the main window opens, then hands it a file from a
# second launch; passes when QLogg reports java.home $2 and logs no unhandled
# or UI task failure. $3 names the run's settings directory and output.
check_launcher() {
  local out="$work/$3.out" second_out="$work/$3-second.out"
  local settings="$work/settings-$3"
  "$1" -d 1 --settings "$settings" "$work/app.log" >"$out" 2>&1 &
  local app=$!
  await_line "$out" 'Main window open' "$app"

  # A failed handoff opens its own window and never exits, so the wait is bounded.
  "$1" --settings "$settings" "$work/handed.log" >"$second_out" 2>&1 &
  local second=$!
  for _ in $(seq 1 60); do
    kill -0 "$second" 2>/dev/null || break
    sleep 1
  done
  local second_status=running
  if ! kill -0 "$second" 2>/dev/null; then
    second_status=0
    wait "$second" || second_status=$?
  fi
  await_line "$out" 'Handoff of 1 files' "$app"

  kill "$second" 2>/dev/null || true
  kill "$app" 2>/dev/null || true
  echo "--- $3 output"
  cat "$out"
  echo "--- $3 second launch: status $second_status"
  cat "$second_out"
  echo "---"
  grep -qF "from $2" "$out" \
    || { echo "FAIL: $3 did not report java.home $2" >&2; exit 1; }
  grep -q 'Main window open' "$out" \
    || { echo "FAIL: $3 opened no main window within 60 s" >&2; exit 1; }
  [ "$second_status" = 0 ] \
    || { echo "FAIL: $3 second launch ended with status $second_status" >&2; exit 1; }
  grep -q 'Handoff of 1 files taken' "$out" \
    || { echo "FAIL: $3 primary took no handoff" >&2; exit 1; }
  if grep -q 'Unhandled failure' "$out"; then
    echo "FAIL: $3 logged an unhandled failure" >&2
    exit 1
  fi
  if grep -q 'UI task failed' "$out"; then
    echo "FAIL: $3 logged a UI task failure" >&2
    exit 1
  fi
}

check_launcher "$launcher" "$runtime" bundle
echo "PASS: $TARGET launcher ran $runtime (os.arch $arch), opened the main window and took a handoff"

universal="$(ls dist/qlogg-*.zip | grep -Ev -- '-(linux|macos|windows)-')"
unzip -q "$universal" -d "$work/universal"
jdk="$(cd "$JAVA_HOME" && pwd -P)"
check_launcher "$work/universal/qlogg/bin/qlogg" "$jdk" universal
echo "PASS: $universal bin/qlogg ran $jdk, opened the main window and took a handoff"
