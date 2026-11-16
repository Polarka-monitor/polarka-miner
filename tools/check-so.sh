#!/bin/sh
# Checks the built library: no entry of the jump table may point at RandomX's own code or functions, and
# nothing of RandomX may be exported. See the note in app/src/main/cpp/CMakeLists.txt.
# usage: check-so.sh <libpolarkaminer.so>
set -eu
. "$HOME/north-build/env.sh"
T="$ANDROID_HOME/ndk/27.3.13750724/toolchains/llvm/prebuilt/darwin-x86_64/bin"
SO="$1"
bad=$("$T/llvm-readelf" -r "$SO" | grep -c -E "randomx_" || true)
exp=$("$T/llvm-readelf" --dyn-syms "$SO" | grep -c -E "randomx_" || true)
jni=$("$T/llvm-readelf" --dyn-syms "$SO" | grep -c -E "Java_org_polarka_miner_Native_" || true)
echo "relocations naming randomx: $bad, exported randomx symbols: $exp, exported JNI functions: $jni"
if [ "$bad" != "0" ] || [ "$exp" != "0" ] || [ "$jni" = "0" ]; then echo "CHECK FAILED"; exit 1; fi
echo "check passed"
