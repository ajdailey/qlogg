#!/usr/bin/env bash
# Starts every variant bundle for $TARGET and reports, per variant, whether
# the main window opened, which SWT natives the process mapped and whether it
# holds the opened log file. Never fails on a variant result.
set -uo pipefail

results="$PWD/results-$TARGET.txt"
: >"$results"
for zip in variants/"$TARGET"-*.zip; do
  variant=$(basename "$zip" .zip)
  variant=${variant#"$TARGET"-}
  work="$PWD/run/$variant"
  rm -rf "$work"
  mkdir -p "$work"
  unzip -q "$zip" -d "$work"
  app="$work/QLogg.app"
  echo "================ $TARGET $variant"
  codesign -dvvv --entitlements - "$app" 2>&1 | grep -E "flags=|Signature|TeamIdentifier|key" || true
  if codesign --verify --deep --strict "$app" 2>&1; then verify=valid; else verify=invalid; fi
  printf 'mock line\n' >"$work/app.log"
  start=$(date '+%Y-%m-%d %H:%M:%S')
  "$app/Contents/MacOS/QLogg" -d 1 --settings "$work/settings" "$work/app.log" >"$work/out" 2>&1 &
  pid=$!
  status=running
  for _ in $(seq 1 60); do
    grep -q 'Main window open' "$work/out" && break
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid"
      status="exit $?"
      break
    fi
    sleep 1
  done
  sleep 3
  window=no
  grep -q 'Main window open' "$work/out" && window=yes
  natives=0
  logfile=no
  if kill -0 "$pid" 2>/dev/null; then
    lsof -p "$pid" >"$work/lsof" 2>/dev/null || true
    natives=$(grep -c 'libswt-.*\.jnilib' "$work/lsof")
    grep -q 'app\.log' "$work/lsof" && logfile=yes
    grep -E 'jnilib|app\.log' "$work/lsof" || true
  fi
  kill "$pid" 2>/dev/null || true
  sleep 2
  kill -9 "$pid" 2>/dev/null || true
  echo "--- output"
  tail -40 "$work/out"
  echo "--- natives directory"
  ls -l "$work"/settings/cache/swt/* 2>/dev/null || echo "(none)"
  echo "--- system log"
  log show --style compact --start "$start" --predicate \
    'eventMessage CONTAINS[c] "library validation" OR eventMessage CONTAINS[c] "not valid for use in process" OR eventMessage CONTAINS[c] "mapping process" OR eventMessage CONTAINS[c] "different Team IDs" OR (sender == "AppleMobileFileIntegrity" AND eventMessage CONTAINS[c] "QLogg")' \
    2>/dev/null | tail -15
  line="$TARGET $variant: verify=$verify window=$window natives_mapped=$natives log_open=$logfile process=$status"
  echo "RESULT $line"
  echo "$line" >>"$results"
done

# Gatekeeper's verdict on a quarantined copy of the shipped entitlement set.
app="$PWD/run/all3/QLogg.app"
xattr -w com.apple.quarantine "0081;$(printf %x "$(date +%s)");Safari;" "$app"
echo "--- spctl on quarantined all3"
spctl -a -vvv -t exec "$app" 2>&1 | tee -a "$results" || true
syspolicy_check distribution "$app" 2>&1 | tail -20 | tee -a "$results" || true

echo "================ results"
cat "$results"
cat "$results" >>"$GITHUB_STEP_SUMMARY"
