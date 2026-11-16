#!/bin/sh
# Builds the tests and the benchmark that RandomX itself ships (unchanged, from the TrueNorth sources)
# for Android arm64, so that they can be run in a shell on a test device. Nothing here goes into the app.
set -eu
. "$HOME/north-build/env.sh"
SDK="$ANDROID_HOME"
NDK="$SDK/ndk/27.3.13750724"
CM="$SDK/cmake/3.22.1/bin"
SRC="${RANDOMX_DIR:-$HOME/north-build/truenorth-src-v30.5.4/src/randomx}"
OUT="$HOME/north-build/rxtests-android"
mkdir -p "$OUT"
"$CM/cmake" -S "$SRC" -B "$OUT" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release > "$OUT/configure.log" 2>&1
"$CM/cmake" --build "$OUT" --target randomx-tests randomx-benchmark > "$OUT/build.log" 2>&1
ls -l "$OUT/randomx-tests" "$OUT/randomx-benchmark" | awk '{print $5, $NF}' | sed "s#$HOME#HOME#"
echo done
