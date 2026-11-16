#!/bin/sh
# Writes docs/images/qlogg-light.png and qlogg-dark.png (the main window, 1100x700)
# and the user guide's docs/images/guide/*.png from generated mock logs under
# Xvfb. Linux (GTK) only. Needs xvfb-run and a JDK 21 in JAVA_HOME; the test JVM
# uses target/test-home as user.home, so nothing is written to the real home
# directory. Surefire's XDG_CONFIG_DIRS pin draws GTK's Adwaita theme, light or
# dark, whatever the desktop theme. The Recently Closed shot posts Alt+F, which
# fires only with the ibus daemon reachable. The logs are written to
# /tmp/qlogg-demo, the path the path field shows, created owner-only (the run
# stops if it exists) and removed afterwards. The next build embeds the guide
# images.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
logs=/tmp/qlogg-demo
mkdir -m 700 "$logs"
trap 'rm -rf "$logs"' EXIT
cd "$root"
./mvnw -q -pl qlogg-launcher -am install -DskipTests
xvfb-run -a -s "-screen 0 1280x800x24" ./mvnw -q -pl qlogg-launcher surefire:test \
    -Dtest=ScreenshotsTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dqlogg.screenshots="$root/docs/images" -Dqlogg.screenshots.logs="$logs"
xvfb-run -a -s "-screen 0 1280x800x24" ./mvnw -q -pl qlogg-ui-swt surefire:test \
    -Dtest=GuideScreenshotsTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dqlogg.guide.shots="$root/docs/images/guide" -Dqlogg.screenshots.logs="$logs"
ls -l "$root/docs/images" "$root/docs/images/guide"
