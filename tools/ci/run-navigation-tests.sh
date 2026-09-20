#!/usr/bin/env bash
set -uo pipefail
cd "${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
evidence="$PWD/ci-evidence/emulator"
mkdir -p "$evidence"
# Requires a fresh emulator install. Never clear user app data to meet this precondition.
cold_start_test="me.timschneeberger.rootlessjamesdsp.utils.notifications.ServiceNotificationHelperTest"
./gradlew --stacktrace --console=plain connectedRootlessFdroidDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$cold_start_test" 2>&1 | tee "$evidence/cold-start.log"
result=${PIPESTATUS[0]}
if [ -d app/build/outputs/androidTest-results ]; then
  cp -R app/build/outputs/androidTest-results "$evidence/cold-start-results"
fi
if [ "$result" -eq 0 ]; then
  ./gradlew --stacktrace --console=plain connectedRootlessFdroidDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.notClass=$cold_start_test" 2>&1 | tee "$evidence/gradle.log"
  result=${PIPESTATUS[0]}
fi
printf '%s\n' "$result" > "$evidence/exit-code.txt"
if [ "$result" -ne 0 ]; then
  find app/build/outputs/androidTest-results -name '*.xml' -type f -exec cat '{}' + || true
fi
adb logcat -d -t 2000 > "$evidence/logcat.txt" 2>&1 || true
exit "$result"
