#!/usr/bin/env bash
# Checks whether this runner enforces the hardened runtime: a small loader
# dlopens an Eclipse-signed SWT jnilib, an ad-hoc dylib and maps MAP_JIT memory,
# signed ad hoc with the runtime flag under each entitlement set.
set -uo pipefail

echo "--- SIP and boot arguments"
csrutil status || true
nvram boot-args 2>&1 || true
sysctl kern.bootargs 2>&1 || true
spctl --status || true

dir="$PWD/controls"
mkdir -p "$dir"
cat >"$dir/probe.c" <<'C'
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
int main(int argc, char **argv) {
  void *p = mmap(NULL, 16384, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANON | MAP_JIT, -1, 0);
  printf("MAP_JIT: %s\n", p == MAP_FAILED ? strerror(errno) : "ok");
  void *q = mmap(NULL, 16384, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANON, -1, 0);
  printf("RWX without MAP_JIT: %s\n", q == MAP_FAILED ? strerror(errno) : "ok");
  for (int i = 1; i < argc; i++) {
    void *h = dlopen(argv[i], RTLD_NOW);
    printf("dlopen %s: %s\n", strrchr(argv[i], '/') + 1, h ? "ok" : dlerror());
  }
  return 0;
}
C
printf 'int probe_value(void) { return 1; }\n' >"$dir/lib.c"
clang -o "$dir/probe" "$dir/probe.c"
clang -dynamiclib -o "$dir/libadhoc.dylib" "$dir/lib.c"
codesign -s - -f "$dir/libadhoc.dylib"

ent() {
  local file=$1
  shift
  {
    echo '<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict>'
    for key in "$@"; do echo "<key>com.apple.security.cs.$key</key><true/>"; done
    echo '</dict></plist>'
  } >"$file"
}
ent "$dir/none.plist"
ent "$dir/all3.plist" allow-jit allow-unsigned-executable-memory disable-library-validation

swt="$(ls "$PWD"/run/all3/settings/cache/swt/*/libswt-pi-cocoa-*.jnilib)"
for mode in plain runtime-none runtime-all3; do
  cp "$dir/probe" "$dir/probe-$mode"
  case "$mode" in
    plain) codesign -s - -f "$dir/probe-$mode" ;;
    runtime-none) codesign -s - -f -o runtime --entitlements "$dir/none.plist" "$dir/probe-$mode" ;;
    runtime-all3) codesign -s - -f -o runtime --entitlements "$dir/all3.plist" "$dir/probe-$mode" ;;
  esac
  echo "=== control $mode"
  codesign -dv "$dir/probe-$mode" 2>&1 | grep flags=
  "$dir/probe-$mode" "$swt" "$dir/libadhoc.dylib" 2>&1 | sed "s/^/CONTROL $TARGET $mode: /"
done
