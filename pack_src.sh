#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$SCRIPT_DIR/acglass-src.tar.gz"

tar czf "$OUT" \
    --exclude='weston/builddir*' \
    --exclude='weston/.git' \
    -C "$SCRIPT_DIR" \
    build_and_install.sh \
    common/ \
    libdisplay_producer/ \
    wayland-protocols-override/ \
    weston/

echo "Source package: $OUT ($(du -h "$OUT" | cut -f1))"
echo ""
echo "Deploy to container:"
echo "  adb push $OUT /path/to/rootfs"
echo "  adb shell /data/local/Droidspaces/bin/droidspaces --name=<name> run bash -c 'cd / && tar xzf acglass-src.tar.gz && bash build_and_install.sh'"
