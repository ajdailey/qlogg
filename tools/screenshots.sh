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
#
# "screenshots.sh installer [<run-id>]" instead copies the Windows setup pages
# the guide shows from the installer-screenshots-windows-x86_64 artifact of CI
# run <run-id>, by default the latest successful CI run on main, into
# docs/images/guide/installer-{type,folder,finish}.png. Needs gh and network.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

if [ "${1:-}" = installer ]; then
    run=${2:-$(gh run list --workflow ci.yml --branch main --status success \
        --limit 1 --json databaseId --jq '.[0].databaseId')}
    [ -n "$run" ] || { echo "no successful CI run on main" >&2; exit 1; }
    pages=$(mktemp -d)
    trap 'rm -rf "$pages"' EXIT
    gh run download "$run" -n installer-screenshots-windows-x86_64 -D "$pages"
    # The setup walk's pages 3, 5 and 7: installation type, folder, finish.
    for pair in 3:type 5:folder 7:finish; do
        set -- "$pages"/[0-9][0-9]-setup-"${pair%%:*}".png
        [ $# -eq 1 ] && [ -f "$1" ] || { echo "run $run has no setup page ${pair%%:*}" >&2; exit 1; }
        cp "$1" "docs/images/guide/installer-${pair#*:}.png"
    done
    echo "copied from run $run"
    ls -l docs/images/guide/installer-*.png
    exit 0
fi

logs=/tmp/qlogg-demo
mkdir -m 700 "$logs"
trap 'rm -rf "$logs"' EXIT
./mvnw -q -pl qlogg-launcher -am install -DskipTests
xvfb-run -a -s "-screen 0 1280x800x24" ./mvnw -q -pl qlogg-launcher surefire:test \
    -Dtest=ScreenshotsTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dqlogg.screenshots="$root/docs/images" -Dqlogg.screenshots.logs="$logs"
xvfb-run -a -s "-screen 0 1280x800x24" ./mvnw -q -pl qlogg-ui-swt surefire:test \
    -Dtest=GuideScreenshotsTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dqlogg.guide.shots="$root/docs/images/guide" -Dqlogg.screenshots.logs="$logs"
ls -l "$root/docs/images" "$root/docs/images/guide"
