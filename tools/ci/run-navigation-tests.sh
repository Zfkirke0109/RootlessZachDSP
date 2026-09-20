#!/usr/bin/env bash
set -uo pipefail
cd "${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
evidence="$PWD/ci-evidence/emulator"
mkdir -p "$evidence"
./gradlew --stacktrace --console=plain connectedRootlessFdroidDebugAndroidTest 2>&1 | tee "$evidence/gradle.log"
result=${PIPESTATUS[0]}
printf '%s\n' "$result" > "$evidence/exit-code.txt"
if [ "$result" -ne 0 ]; then
  find app/build/outputs/androidTest-results -name '*.xml' -type f -exec cat '{}' + || true
fi
adb logcat -d -t 2000 > "$evidence/logcat.txt" 2>&1 || true
exit "$result"
