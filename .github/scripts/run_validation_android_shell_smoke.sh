#!/usr/bin/env bash
set -euo pipefail

REPORT_ROOT="clients/android/app/build/reports/emulator-smoke"
APP_PACKAGE="com.sednalabs.solarlab"
CLASS_TIMEOUT_SECONDS="${ANDROID_TEST_CLASS_TIMEOUT_SECONDS:-300}"
TEST_SCOPE="${ANDROID_TEST_SCOPE:-core}"
ARTIFACT_MODE="${ANDROID_ARTIFACT_MODE:-failures-only}"
VALIDATION_MODE="${ANDROID_VALIDATION_MODE:-shell-v2}"
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

SHELL_CORE_TEST_CLASSES=(
  "com.sednalabs.solarlab.StartupSmokeInstrumentationTest"
  "com.sednalabs.solarlab.FocusedCompositionInstrumentationTest"
  "com.sednalabs.solarlab.SolarLabShellLayoutTest"
)

SHELL_FULL_TEST_CLASSES=(
  "com.sednalabs.solarlab.StartupSmokeInstrumentationTest"
  "com.sednalabs.solarlab.FocusedCompositionInstrumentationTest"
  "com.sednalabs.solarlab.SolarLabShellLayoutTest"
  "com.sednalabs.solarlab.RotationContinuityInstrumentationTest"
  "com.sednalabs.solarlab.PlaybackContinuityInstrumentationTest"
)

STAGE_FIRST_MIRROR_OFF_CORE_TEST_CLASSES=(
  "com.sednalabs.solarlab.StageFirstLocalStartupInstrumentationTest"
)

STAGE_FIRST_MIRROR_ON_CORE_TEST_CLASSES=(
  "com.sednalabs.solarlab.StageFirstLocalStartupInstrumentationTest"
  "com.sednalabs.solarlab.StageFirstRuntimeMirrorInstrumentationTest"
)

TEST_CLASSES=()
GRADLE_VALIDATION_PROPS=()

mkdir -p "${REPORT_ROOT}"

collect_host_emulator_logs() {
  local destination_root="$1"
  local host_dir="${destination_root}/host-emulator"

  mkdir -p "${host_dir}"

  if compgen -G "${HOME}/.android/adb*" >/dev/null; then
    cp -f ${HOME}/.android/adb* "${host_dir}/" 2>/dev/null || true
  fi

  if [[ -d "${HOME}/.android/avd" ]]; then
    find "${HOME}/.android/avd" -maxdepth 2 -type f \( -name 'emu-*.log' -o -name 'emulator*.log' \) -print0 |
      while IFS= read -r -d '' log_path; do
        local relative_name
        relative_name="$(basename "$(dirname "${log_path}")")-$(basename "${log_path}")"
        cp -f "${log_path}" "${host_dir}/${relative_name}" 2>/dev/null || true
      done
  fi
}

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
  local in_app_screenshots_dir="${class_dir}/in-app-screenshots"

  run_capture "${class_dir}/adb-devices.${phase_label}.txt" adb devices -l
  run_capture "${class_dir}/logcat-buffer.${phase_label}.txt" adb logcat -g
  run_capture "${class_dir}/getprop.${phase_label}.txt" adb shell getprop
  run_capture "${class_dir}/package-path.${phase_label}.txt" adb shell pm path "${APP_PACKAGE}"
  run_capture "${class_dir}/pidof.${phase_label}.txt" adb shell pidof "${APP_PACKAGE}"
  run_capture "${class_dir}/logcat.txt" adb logcat -d
  run_capture "${class_dir}/dumpsys_activity.txt" adb shell dumpsys activity activities
  run_capture "${class_dir}/dumpsys_activity_top.txt" adb shell dumpsys activity top
  run_capture "${class_dir}/dumpsys_services.${phase_label}.txt" adb shell dumpsys activity services
  run_capture "${class_dir}/dumpsys_window.txt" adb shell dumpsys window windows
  run_capture "${class_dir}/gfxinfo.txt" adb shell dumpsys gfxinfo "${APP_PACKAGE}"
  run_capture "${class_dir}/anr_traces.txt" adb shell cat /data/anr/traces.txt
  timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" adb shell uiautomator dump /sdcard/solarlab-window-dump.xml >/dev/null 2>&1 || true
  timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" adb pull /sdcard/solarlab-window-dump.xml "${class_dir}/window_dump.xml" >/dev/null 2>&1 || true
  timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" adb pull "/sdcard/Download/solarlab-validation/startup-ready.png" "${in_app_full_png}" >/dev/null 2>&1 || rm -f "${in_app_full_png}"
  timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" adb pull "/sdcard/Download/solarlab-validation/startup-ready-stage.png" "${in_app_stage_png}" >/dev/null 2>&1 || rm -f "${in_app_stage_png}"
  mkdir -p "${in_app_screenshots_dir}"
  timeout --foreground "${ADB_CAPTURE_TIMEOUT_SECONDS}s" adb pull "/sdcard/Download/solarlab-validation/." "${in_app_screenshots_dir}" >/dev/null 2>&1 || true
  run_binary_capture "${screen_png}" adb exec-out screencap -p
  if [[ ! -s "${screen_png}" ]]; then
    rm -f "${screen_png}"
  elif [[ "${phase_label}" == "post" ]]; then
    cp "${screen_png}" "${class_dir}/screen.png"
  fi

  collect_host_emulator_logs "${class_dir}"
}

start_live_logcat() {
  local class_dir="$1"
  local output_file="${class_dir}/live-logcat.txt"

  echo "Streaming filtered logcat for $(basename "${class_dir}")"
  adb logcat -G 32M >/dev/null 2>&1 || true
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
  local run_dir="$1"
  echo "Failure summary for instrumentation batch:"
  tail -n 120 "${run_dir}/gradle-output.txt" || true
  if [[ -f "${run_dir}/live-logcat.txt" ]]; then
    tail -n 160 "${run_dir}/live-logcat.txt" || true
  fi
}

resolve_test_classes() {
  case "${VALIDATION_MODE}" in
    shell-v2)
      GRADLE_VALIDATION_PROPS=(
        "-Psolarlab.debugStageFirstClient=false"
        "-Psolarlab.stageFirstRuntimeMirror=false"
      )
      ;;
    stage-first-mirror-off)
      GRADLE_VALIDATION_PROPS=(
        "-Psolarlab.debugStageFirstClient=true"
        "-Psolarlab.stageFirstRuntimeMirror=false"
      )
      ;;
    stage-first-mirror-on)
      GRADLE_VALIDATION_PROPS=(
        "-Psolarlab.debugStageFirstClient=true"
        "-Psolarlab.stageFirstRuntimeMirror=true"
      )
      ;;
    *)
      echo "Unsupported ANDROID_VALIDATION_MODE='${VALIDATION_MODE}'" >&2
      exit 2
      ;;
  esac

  if [[ -n "${ANDROID_TEST_CLASSES:-}" ]]; then
    IFS=',' read -r -a TEST_CLASSES <<< "${ANDROID_TEST_CLASSES}"
    return
  fi

  case "${VALIDATION_MODE}" in
    shell-v2)
      case "${TEST_SCOPE}" in
        core)
          TEST_CLASSES=("${SHELL_CORE_TEST_CLASSES[@]}")
          ;;
        full)
          TEST_CLASSES=("${SHELL_FULL_TEST_CLASSES[@]}")
          ;;
        *)
          echo "Unsupported ANDROID_TEST_SCOPE='${TEST_SCOPE}' for mode '${VALIDATION_MODE}'" >&2
          exit 2
          ;;
      esac
      ;;
    stage-first-mirror-off)
      case "${TEST_SCOPE}" in
        core)
          TEST_CLASSES=("${STAGE_FIRST_MIRROR_OFF_CORE_TEST_CLASSES[@]}")
          ;;
        full)
          echo "ANDROID_TEST_SCOPE='full' is not yet supported for mode '${VALIDATION_MODE}'. Use 'core' or extend the stage-first class set explicitly first." >&2
          exit 2
          ;;
        *)
          echo "Unsupported ANDROID_TEST_SCOPE='${TEST_SCOPE}' for mode '${VALIDATION_MODE}'" >&2
          exit 2
          ;;
      esac
      ;;
    stage-first-mirror-on)
      case "${TEST_SCOPE}" in
        core)
          TEST_CLASSES=("${STAGE_FIRST_MIRROR_ON_CORE_TEST_CLASSES[@]}")
          ;;
        full)
          echo "ANDROID_TEST_SCOPE='full' is not yet supported for mode '${VALIDATION_MODE}'. Use 'core' or extend the stage-first class set explicitly first." >&2
          exit 2
          ;;
        *)
          echo "Unsupported ANDROID_TEST_SCOPE='${TEST_SCOPE}' for mode '${VALIDATION_MODE}'" >&2
          exit 2
          ;;
      esac
      ;;
  esac
}

run_test_batch() {
  local run_dir="${REPORT_ROOT}/instrumentation-batch"
  local command_status=0
  local run_timeout_seconds
  local class_arg

  mkdir -p "${run_dir}"
  adb logcat -c || true

  class_arg="$(IFS=,; echo "${TEST_CLASSES[*]}")"
  run_timeout_seconds="${ANDROID_TEST_RUN_TIMEOUT_SECONDS:-$((CLASS_TIMEOUT_SECONDS * ${#TEST_CLASSES[@]}))}"

  capture_device_state "${run_dir}" "pre"

  echo "::group::InstrumentationBatch"
  echo "Running ${#TEST_CLASSES[@]} instrumentation classes with timeout ${run_timeout_seconds}s"
  printf 'Classes:\n%s\n' "${class_arg//,/$'\n'}"
  start_live_logcat "${run_dir}"

  set +e
  timeout --foreground "${run_timeout_seconds}s" \
    ./gradlew -p clients/android --stacktrace \
      :app:connectedDebugAndroidTest \
      "${GRADLE_VALIDATION_PROPS[@]}" \
      "-Pandroid.testInstrumentationRunnerArguments.class=${class_arg}" \
      2>&1 | tee "${run_dir}/gradle-output.txt"
  command_status=${PIPESTATUS[0]}
  set -e
  stop_live_logcat "${LAST_LOGCAT_PID}"
  LAST_LOGCAT_PID=""

  if [[ "${ARTIFACT_MODE}" == "always" || "${command_status}" -ne 0 ]]; then
    capture_device_state "${run_dir}" "post"
  fi

  {
    printf 'test_classes=%s\n' "${class_arg}"
    printf 'test_class_count=%s\n' "${#TEST_CLASSES[@]}"
    printf 'scope=%s\n' "${TEST_SCOPE}"
    printf 'validation_mode=%s\n' "${VALIDATION_MODE}"
    printf 'gradle_validation_props=%s\n' "${GRADLE_VALIDATION_PROPS[*]}"
    printf 'artifact_mode=%s\n' "${ARTIFACT_MODE}"
    printf 'timeout_seconds=%s\n' "${run_timeout_seconds}"
    printf 'exit_code=%s\n' "${command_status}"
  } > "${run_dir}/status.txt"

  if [[ "${command_status}" -eq 124 ]]; then
    capture_device_state "${run_dir}" "fail"
    echo "Timed out while running instrumentation batch" >&2
    emit_failure_summary "${run_dir}"
  elif [[ "${command_status}" -ne 0 ]]; then
    capture_device_state "${run_dir}" "fail"
    echo "Instrumentation batch failed with exit code ${command_status}" >&2
    emit_failure_summary "${run_dir}"
  else
    echo "Instrumentation batch completed successfully"
  fi

  echo "::endgroup::"

  return "${command_status}"
}

resolve_test_classes
set +e
run_test_batch
overall_status=$?
set -e

exit "${overall_status}"
