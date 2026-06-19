#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NDK_VERSION="${ANDROID_NDK_VERSION:-29.0.13113456}"
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_NDK_ROOT:-}" ]; then
    NDK_DIR="$ANDROID_NDK_ROOT"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
    NDK_DIR="$ANDROID_HOME/ndk/$NDK_VERSION"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION" ]; then
    NDK_DIR="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
else
    NDK_DIR="$SCRIPT_DIR/build_tools/android-sdk/ndk/$NDK_VERSION"
fi
BUILD_DIR="$SCRIPT_DIR/build_daemon_android"

if [ ! -f "$NDK_DIR/build/cmake/android.toolchain.cmake" ]; then
    echo "ERROR: Android NDK not found at $NDK_DIR" >&2
    echo "Set ANDROID_NDK_HOME, ANDROID_NDK_ROOT, ANDROID_HOME, or ANDROID_SDK_ROOT." >&2
    exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

cmake -S "$SCRIPT_DIR" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30 \
    -DCMAKE_BUILD_TYPE=Release

cmake --build "$BUILD_DIR" --target display_daemon -j$(nproc)

echo "Built: $BUILD_DIR/display_daemon"
