#!/usr/bin/env bash
# Builds the on-device native resolver test (app/src/test/cpp/device) with the NDK and runs it
# over adb on ONE explicitly named device, e.g. an arm64 emulator:
#   scripts/native-device-test.sh emulator-5554
# Needs ANDROID_HOME with the NDK and CMake versions the app module uses.
set -euo pipefail

serial="${1:?usage: native-device-test.sh <adb serial>}"
root="$(git rev-parse --show-toplevel)"
sdk="${ANDROID_HOME:?ANDROID_HOME is not set}"
ndk="$sdk/ndk/$(sed -n 's/.*ndkVersion = "\(.*\)".*/\1/p' "$root/app/build.gradle.kts")"
cmake_bin="$(ls -d "$sdk"/cmake/*/bin | sort -V | tail -1)"
build="$root/app/build/native-device-tests"
remote=/data/local/tmp/hpnative
adb=("$sdk/platform-tools/adb" -s "$serial")

"$cmake_bin/cmake" -S "$root/app/src/test/cpp/device" -B "$build" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$cmake_bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DANDROID_STL=c++_static >/dev/null
"$cmake_bin/cmake" --build "$build" >/dev/null
"${adb[@]}" shell "rm -rf $remote && mkdir -p $remote"
for f in hp_device_test libhptarget_early.so libhptarget_late.so libhptarget_norelro.so; do
  "${adb[@]}" push "$build/$f" "$remote/$f" >/dev/null
done
"${adb[@]}" shell "chmod 755 $remote/hp_device_test && $remote/hp_device_test $remote && $remote/hp_device_test $remote inplace"
