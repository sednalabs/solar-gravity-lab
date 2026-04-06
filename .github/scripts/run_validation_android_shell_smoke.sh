#!/usr/bin/env bash
set -euo pipefail

REPORT_ROOT="clients/android/app/build/reports/emulator-smoke"
APP_PACKAGE="com.sednalabs.solarlab"
CLASS_TIMEOUT_SECONDS="${ANDROID_TEST_CLASS_TIMEOUT_SECONDS:-300}"

TEST_CLASSES=(
  "com.sednalabs.solarlab.StartupSmokeInstrumentationTest"
  "com.sednalabs.solarlab.RotationContinuityInstrumentationTest"
  "com.sednalabs.solarlab.SolarLabShellLayoutTest"
)

mkdir -p "${REPORT_ROOT}"

capture_device_state() {
  local class_dir="$1"

  adb logcat -d > "${class_dir}/logcat.txt" || true
  adb shell dumpsys activity activities > "${class_dir}/dumpsys_activity.txt" || true
  adb shell dumpsys window windows > "${class_dir}/dumpsys_window.txt" || true
  adb shell dumpsys gfxinfo "${APP_PACKAGE}" > "${class_dir}/gfxinfo.txt" || true
  adb shell cat /data/anr/traces.txt > "${class_dir}/anr_traces.txt" || true
}

run_test_class() {
  local test_class="$1"
  local class_name="${test_class##*.}"
  local class_dir="${REPORT_ROOT}/${class_name}"
  local command_status=0

  mkdir -p "${class_dir}"
  adb logcat -c || true

  echo "::group::${class_name}"
  echo "Running ${test_class} with timeout ${CLASS_TIMEOUT_SECONDS}s"

  set +e
  timeout --foreground "${CLASS_TIMEOUT_SECONDS}s" \
    ./gradlew -p clients/android --no-daemon --stacktrace \
      :app:connectedDebugAndroidTest \
      "-Pandroid.testInstrumentationRunnerArguments.class=${test_class}" \
      2>&1 | tee "${class_dir}/gradle-output.txt"
  command_status=${PIPESTATUS[0]}
  set -e

  capture_device_state "${class_dir}"

  {
    printf 'test_class=%s\n' "${test_class}"
    printf 'timeout_seconds=%s\n' "${CLASS_TIMEOUT_SECONDS}"
    printf 'exit_code=%s\n' "${command_status}"
  } > "${class_dir}/status.txt"

  if [[ "${command_status}" -eq 124 ]]; then
    echo "Timed out while running ${test_class}" >&2
  elif [[ "${command_status}" -ne 0 ]]; then
    echo "Instrumentation class ${test_class} failed with exit code ${command_status}" >&2
  else
    echo "Instrumentation class ${test_class} completed successfully"
  fi

  echo "::endgroup::"

  return "${command_status}"
}

overall_status=0

for test_class in "${TEST_CLASSES[@]}"; do
  if run_test_class "${test_class}"; then
    continue
  else
    overall_status=$?
    break
  fi
done

exit "${overall_status}"
