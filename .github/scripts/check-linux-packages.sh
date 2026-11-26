#!/usr/bin/env bash
# Installs the $TARGET .deb from dist/ with apt, checks its files and desktop
# entry, runs check-bundle.sh against /opt/qlogg through /usr/bin/qlogg, and
# removes it. Then installs the .rpm with dnf in a Fedora container, runs
# `qlogg -v` and `rpm -V`, and removes it. Run from the repository root on an
# Ubuntu runner of the target's architecture, after linux-display-deps.sh.
set -euo pipefail

# Fedora 44, multi-architecture index.
fedora=fedora:44@sha256:15da97ed9aa94dd3d92c3925eb89520f6c80e07ea7ee24cba848d1f8b6912b3e

deb=$(ls dist/qlogg-*-"$TARGET".deb)
rpm=$(ls dist/qlogg-*-"$TARGET".rpm)
version=${deb#dist/qlogg-}
version=${version%-"$TARGET".deb}

echo "--- $deb"
dpkg-deb --info "$deb"
.github/scripts/apt-update.sh
sudo apt-get install -y --no-install-recommends x11-utils desktop-file-utils "./$deb"

dpkg-query -W -f '${Version}\n' qlogg | grep -qxF "${version//-/\~}-1" \
  || { echo "FAIL: package version is not ${version//-/\~}-1" >&2; exit 1; }
[ "$(readlink /usr/bin/qlogg)" = /opt/qlogg/bin/qlogg ] \
  || { echo "FAIL: /usr/bin/qlogg does not link to /opt/qlogg/bin/qlogg" >&2; exit 1; }
for marker in qlogg.conf bin/qlogg.conf portable.txt bin/portable.txt; do
  [ ! -e "/opt/qlogg/$marker" ] || { echo "FAIL: the package holds $marker" >&2; exit 1; }
done
desktop-file-validate /usr/share/applications/qlogg.desktop
grep -qx 'StartupWMClass=QLogg' /usr/share/applications/qlogg.desktop \
  || { echo "FAIL: the desktop entry lacks StartupWMClass=QLogg" >&2; exit 1; }
for size in 16 22 24 32 48 64 128 256 512; do
  [ -f "/usr/share/icons/hicolor/${size}x${size}/apps/qlogg.png" ] \
    || { echo "FAIL: no ${size}x${size} icon" >&2; exit 1; }
done
[ -f /usr/share/icons/hicolor/scalable/apps/qlogg.svg ] \
  || { echo "FAIL: no scalable icon" >&2; exit 1; }
echo "PASS: $deb installed /opt/qlogg, /usr/bin/qlogg, the desktop entry and every icon"

QLOGG_HOME=/opt/qlogg QLOGG_LAUNCHER=/usr/bin/qlogg .github/scripts/check-bundle.sh

sudo apt-get remove -y qlogg
for path in /opt/qlogg /usr/bin/qlogg /usr/share/applications/qlogg.desktop; do
  [ ! -e "$path" ] || { echo "FAIL: $path remains after removal" >&2; exit 1; }
done
echo "PASS: removing the .deb removed /opt/qlogg, /usr/bin/qlogg and the desktop entry"

echo "--- $rpm"
docker run --rm -v "$PWD/dist:/dist:ro" -e RPM="/$rpm" "$fedora" sh -euc '
  rpm -qip "$RPM"
  rpm -qp --requires "$RPM"
  dnf install -y -q "$RPM"
  rpm -V qlogg
  /usr/bin/qlogg -v
  [ ! -e /opt/qlogg/qlogg.conf ] && [ ! -e /opt/qlogg/portable.txt ]
  dnf remove -y -q qlogg
  [ ! -e /opt/qlogg ] && [ ! -e /usr/bin/qlogg ]
'
echo "PASS: $rpm installed with dnf on Fedora 44, ran qlogg -v and removed cleanly"
