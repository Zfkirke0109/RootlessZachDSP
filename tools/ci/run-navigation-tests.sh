#!/usr/bin/env bash
set -uo pipefail
cd "${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
evidence="$PWD/ci-evidence/emulator"
mkdir -p "$evidence"
# Every instrumentation test prepares its own preconditions, so the whole suite runs in one
# Gradle invocation. The notification-channel regression in particular deletes and recreates
# the channel it checks instead of relying on a fresh install: the emulator delivers
# BOOT_COMPLETED to the freshly started app process, which already declares every channel.
./gradlew --stacktrace --console=plain connectedRootlessFdroidDebugAndroidTest 2>&1 | tee "$evidence/gradle.log"
result=${PIPESTATUS[0]}
printf '%s\n' "$result" > "$evidence/exit-code.txt"
if [ "$result" -ne 0 ]; then
  find app/build/outputs/androidTest-results -name '*.xml' -type f -exec cat '{}' + || true
fi
adb logcat -d -t 2000 > "$evidence/logcat.txt" 2>&1 || true
exit "$result"
