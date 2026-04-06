#!/usr/bin/env bash
set -euo pipefail

REPORT_ROOT="clients/android/app/build/reports/emulator-smoke"
APP_PACKAGE="com.sednalabs.solarlab"
CLASS_TIMEOUT_SECONDS="${ANDROID_TEST_CLASS_TIMEOUT_SECONDS:-300}"
ADB_CAPTURE_TIMEOUT_SECONDS="${ANDROID_TEST_ADB_CAPTURE_TIMEOUT_SECONDS:-20}"
LOGCAT_SHUTDOWN_TIMEOUT_SECONDS="${ANDROID_TEST_LOGCAT_SHUTDOWN_TIMEOUT_SECONDS:-5}"
LOGCAT_FILTER_SPECS=(
  "SolarLabInstrumentation:I"
  "SolarLabDevTelemetry:I"
  "TestRunner:I"
  "AndroidJUnitRunner:D"
  "AndroidRuntime:E"
  "ActivityManager:E"
  "*:S"
)
LAST_LOGCAT_PID=""

TEST_CLASSES=(
  "com.sednalabs.solarlab.StartupSmokeInstrumentationTest"
  "com.sednalabs.solarlab.RotationContinuityInstrumentationTest"
  "com.sednalabs.solarlab.SolarLabShellLayoutTest"
)

mkdir -p "${REPORT_ROOT}"

run_capture() {
  local output_path="$1"
  shift

  if ! timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" "$@" > "${output_path}" 2>&1; then
    true
  fi
}

run_binary_capture() {
  local output_path="$1"
  shift

  if ! timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" "$@" > "${output_path}"; then
    true
  fi
}

capture_device_state() {
  local class_dir="$1"
  local phase_label="${2:-post}"
  local screen_png="${class_dir}/screen.${phase_label}.png"
  local in_app_full_png="${class_dir}/startup-ready.in-app.png"
  local in_app_stage_png="${class_dir}/startup-ready-stage.in-app.png"

  run_capture "${class_dir}/logcat.txt" adb logcat -d
  run_capture "${class_dir}/dumpsys_activity.txt" adb shell dumpsys activity activities
  run_capture "${class_dir}/dumpsys_activity_top.txt" adb shell dumpsys activity top
  run_capture "${class_dir}/dumpsys_window.txt" adb shell dumpsys window windows
  run_capture "${class_dir}/gfxinfo.txt" adb shell dumpsys gfxinfo "${APP_PACKAGE}"
  run_capture "${class_dir}/anr_traces.txt" adb shell cat /data/anr/traces.txt
  timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" adb shell uiautomator dump /sdcard/solarlab-window-dump.xml >/dev/null 2>&1 || true
  timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" adb pull /sdcard/solarlab-window-dump.xml "${class_dir}/window_dump.xml" >/dev/null 2>&1 || true
  if timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" adb exec-out sh -c "run-as '${APP_PACKAGE}' cat 'files/validation-screenshots/startup-ready.png'" > "${in_app_full_png}" 2>/dev/null; then
    if [[ ! -s "${in_app_full_png}" ]]; then
      rm -f "${in_app_full_png}"
    fi
  else
    rm -f "${in_app_full_png}"
  fi
  if timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" adb exec-out sh -c "run-as '${APP_PACKAGE}' cat 'files/validation-screenshots/startup-ready-stage.png'" > "${in_app_stage_png}" 2>/dev/null; then
    if [[ ! -s "${in_app_stage_png}" ]]; then
      rm -f "${in_app_stage_png}"
    fi
  else
    rm -f "${in_app_stage_png}"
  fi
  run_binary_capture "${screen_png}" adb exec-out screencap -p
  if [[ ! -s "${screen_png}" ]]; then
    rm -f "${screen_png}"
  elif [[ "${phase_label}" == "post" ]]; then
    cp "${screen_png}" "${class_dir}/screen.png"
  fi
}

start_live_logcat() {
  local class_dir="$1"
  local output_file="${class_dir}/live-logcat.txt"

  echo "Streaming filtered logcat for $(basename "${class_dir}")"
  adb logcat -v threadtime "${LOGCAT_FILTER_SPECS[@]}" > >(tee "${output_file}") &
  LAST_LOGCAT_PID=$!
}

stop_live_logcat() {
  local logcat_pid="${1:-}"
  if [[ -z "${logcat_pid}" ]]; then
    return
  fi

  kill "${logcat_pid}" >/dev/null 2>&1 || true
  for _ in $(seq 1 $((LOGCAT_SHUTDOWN_TIMEOUT_SECONDS * 10))); do
    if ! kill -0 "${logcat_pid}" >/dev/null 2>&1; then
      break
    fi
    sleep 0.1
  done
  kill -9 "${logcat_pid}" >/dev/null 2>&1 || true
  wait "${logcat_pid}" >/dev/null 2>&1 || true
}

emit_failure_summary() {
  local class_dir="$1"
  local class_name
  class_name="$(basename "${class_dir}")"

  echo "Failure summary for ${class_name}:"
  tail -n 80 "${class_dir}/gradle-output.txt" || true
  if [[ -f "${class_dir}/live-logcat.txt" ]]; then
    tail -n 120 "${class_dir}/live-logcat.txt" || true
  fi
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
  start_live_logcat "${class_dir}"

  set +e
  timeout --foreground "${CLASS_TIMEOUT_SECONDS}s" \
    ./gradlew -p clients/android --no-daemon --stacktrace \
      :app:connectedDebugAndroidTest \
      "-Pandroid.testInstrumentationRunnerArguments.class=${test_class}" \
      2>&1 | tee "${class_dir}/gradle-output.txt"
  command_status=${PIPESTATUS[0]}
  set -e
  stop_live_logcat "${LAST_LOGCAT_PID}"
  LAST_LOGCAT_PID=""

  capture_device_state "${class_dir}" "post"

  {
    printf 'test_class=%s\n' "${test_class}"
    printf 'timeout_seconds=%s\n' "${CLASS_TIMEOUT_SECONDS}"
    printf 'exit_code=%s\n' "${command_status}"
  } > "${class_dir}/status.txt"

  if [[ "${command_status}" -eq 124 ]]; then
    capture_device_state "${class_dir}" "fail"
    echo "Timed out while running ${test_class}" >&2
    emit_failure_summary "${class_dir}"
  elif [[ "${command_status}" -ne 0 ]]; then
    capture_device_state "${class_dir}" "fail"
    echo "Instrumentation class ${test_class} failed with exit code ${command_status}" >&2
    emit_failure_summary "${class_dir}"
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
