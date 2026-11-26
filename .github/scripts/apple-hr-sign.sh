#!/usr/bin/env bash
# Re-signs each macOS bundle in dist/ ad hoc with the hardened runtime under
# each entitlement variant and writes variants/<target>-<variant>.zip.
set -euo pipefail

archive=apple-codesign-0.29.0-x86_64-unknown-linux-musl.tar.gz
curl -fsSL -o "$archive" \
  "https://github.com/indygreg/apple-platform-rs/releases/download/apple-codesign%2F0.29.0/$archive"
echo "dbe85cedd8ee4217b64e9a0e4c2aef92ab8bcaaa41f20bde99781ff02e600002  $archive" | sha256sum -c -
tar -xzf "$archive"
rcodesign="$PWD/apple-codesign-0.29.0-x86_64-unknown-linux-musl/rcodesign"

# Writes an entitlements plist holding keys $2.. to $1.
entitlements() {
  local file=$1
  shift
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">'
    echo '<plist version="1.0">'
    echo '<dict>'
    for key in "$@"; do
      echo "  <key>com.apple.security.cs.$key</key>"
      echo '  <true/>'
    done
    echo '</dict>'
    echo '</plist>'
  } >"$file"
}

mkdir -p ent variants
entitlements ent/all3.plist allow-jit allow-unsigned-executable-memory disable-library-validation
entitlements ent/none.plist
entitlements ent/jit.plist allow-jit
entitlements ent/jit-uem.plist allow-jit allow-unsigned-executable-memory
entitlements ent/jit-dlv.plist allow-jit disable-library-validation
entitlements ent/uem-dlv.plist allow-unsigned-executable-memory disable-library-validation

for target in macos-x86_64 macos-aarch64; do
  for plist in ent/*.plist; do
    variant=$(basename "$plist" .plist)
    work="work/$target-$variant"
    rm -rf "$work"
    mkdir -p "$work"
    unzip -q dist/qlogg-*-"$target".zip -d "$work"
    "$rcodesign" sign --code-signature-flags runtime --entitlements-xml-file "$plist" "$work/QLogg.app"
    echo "=== $target $variant"
    for bin in MacOS/QLogg runtime/Contents/Home/bin/java runtime/Contents/Home/lib/server/libjvm.dylib; do
      echo "--- $bin"
      "$rcodesign" print-signature-info "$work/QLogg.app/Contents/$bin" \
        | grep -E "flags: CodeSignatureFlags|<key>" | sort -u || true
    done
    (cd "$work" && zip -qry "../../variants/$target-$variant.zip" QLogg.app)
  done
done
