#!/bin/sh
# Renders the icon set from qlogg-dist/src/icons/qlogg.svg and qlogg-light.svg.
# Needs ImageMagick 6 (convert) and Python 3 with Pillow; the build uses the
# committed outputs and needs neither.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
icons="$root/qlogg-dist/src/icons"
png="$root/qlogg-ui-swt/src/main/resources/io/github/ajdailey/qlogg/ui/swt/icons"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

render() {
    # -strip and the excluded chunks keep dates and tool names out of the PNG.
    convert -background none -density 384 "$1" -resize "$2x$2" -strip \
        -define png:exclude-chunks=date,time,tEXt,zTXt,iTXt "PNG32:$3"
}

for size in 16 24 32 48 64 128 256; do
    render "$icons/qlogg.svg" "$size" "$png/qlogg-$size.png"
    render "$icons/qlogg-light.svg" "$size" "$png/qlogg-light-$size.png"
done

convert "$png/qlogg-16.png" "$png/qlogg-24.png" "$png/qlogg-32.png" \
    "$png/qlogg-48.png" "$png/qlogg-64.png" "$png/qlogg-128.png" \
    "$png/qlogg-256.png" "$icons/qlogg.ico"

render "$icons/qlogg.svg" 1024 "$work/qlogg-1024.png"
python3 -I - "$work/qlogg-1024.png" "$icons/QLogg.icns" <<'PY'
import sys
from PIL import Image
Image.open(sys.argv[1]).save(sys.argv[2], format="ICNS")
PY
