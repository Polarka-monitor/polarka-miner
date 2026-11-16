#!/bin/sh
# Builds tools/rxflow for Android arm64 (a shell copy of the app's flow, for finding faults on a test device).
set -eu
. "$HOME/north-build/env.sh"
SDK="$ANDROID_HOME"
NDK="$SDK/ndk/27.3.13750724"
CM="$SDK/cmake/3.22.1/bin"
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${RANDOMX_DIR:-$HOME/north-build/truenorth-src-v30.5.4/src/randomx}"
OUT="$HOME/north-build/rxflow-android"
mkdir -p "$OUT"
"$CM/cmake" -S "$HERE/rxflow" -B "$OUT" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE="${BUILD_TYPE:-Release}" -DRANDOMX_DIR="$SRC" > "$OUT/configure.log" 2>&1
"$CM/cmake" --build "$OUT" --target rxflow > "$OUT/build.log" 2>&1
ls -l "$OUT/rxflow" | awk '{print $5}'
echo done
