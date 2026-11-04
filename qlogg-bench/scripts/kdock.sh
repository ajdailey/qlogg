#!/bin/sh
# Runs a command in a --network none container that can drive klogg under
# Xvfb. KLOGG_TREE is an extracted klogg Linux tree (usr/bin/klogg,
# usr/lib); it is copied to SCRATCH/viewer once, because klogg writes its
# portable configuration beside the binary. SCRATCH is mounted at /data, the
# klogg copy at /viewer and this directory at /scripts.
#
#   kdock.sh KLOGG_TREE SCRATCH COMMAND [ARG...]
#   kdock.sh ~/klogg-linux "$S" /scripts/run-reference.sh /data/ascii-short-1g.log
#
# The image, qlogg-bench-x:local, is built on first use from qlogg-build:local
# (docker compose build) plus xdotool, x11-utils and Qt's X11 libraries.
set -eu

[ $# -ge 3 ] || { echo "usage: kdock.sh KLOGG_TREE SCRATCH COMMAND [ARG...]" >&2; exit 2; }
tree=$(cd "$1" && pwd -P)
scratch=$(cd "$2" && pwd -P)
shift 2
scripts=$(cd "$(dirname "$0")" && pwd -P)
image=qlogg-bench-x:local

[ -x "$tree/usr/bin/klogg" ] || { echo "no usr/bin/klogg under $tree" >&2; exit 2; }
[ -d "$scratch/viewer" ] || cp -a "$tree" "$scratch/viewer"

if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker build -t "$image" - <<'EOF'
FROM qlogg-build:local
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      xdotool x11-utils imagemagick libfontconfig1 libfreetype6 libgl1 libegl1 libharfbuzz0b \
      libxcb1 libxcb-cursor0 libxrender1 libxi6 libsm6 libice6 libglu1-mesa procps \
 && rm -rf /var/lib/apt/lists/*
EOF
fi

exec docker run --rm --network none --init \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -v "$scratch:/data" \
    -v "$scratch/viewer:/viewer" \
    -v "$scripts:/scripts:ro" \
    "$image" "$@"
