#!/usr/bin/env bash
# Builds and runs the host-side native tests (app/src/test/cpp) with ASan + UBSan.
# Needs cmake (PATH, or the Android SDK's cmake/<version>/bin) and a host C++20 compiler.
# Usage: scripts/native-test.sh   (run from anywhere inside the repository)
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
build="$root/app/build/native-host-tests"
if ! command -v cmake >/dev/null 2>&1; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  bin="$(ls -d "$sdk"/cmake/*/bin 2>/dev/null | sort -V | tail -1 || true)"
  [[ -n "$bin" ]] || { echo "native-test: cmake not found" >&2; exit 1; }
  PATH="$bin:$PATH"
fi
gen=()
command -v ninja >/dev/null 2>&1 && gen=(-G Ninja)
cmake -S "$root/app/src/test/cpp" -B "$build" "${gen[@]}" -DCMAKE_BUILD_TYPE=Debug >/dev/null
cmake --build "$build" >/dev/null
ctest --test-dir "$build" --output-on-failure
