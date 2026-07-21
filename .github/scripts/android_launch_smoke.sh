#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: android_launch_smoke.sh --package PACKAGE --activity ACTIVITY [--apk APK] --out-dir DIR [--require-runtime-ready]

Install an APK if provided, launch the activity, wait for the app process to stay
alive through startup, and write diagnostic artifacts to the output directory.

--require-runtime-ready additionally waits for the minified app to report an
authoritative Rust session. It fails on an unavailable runtime rather than
accepting a process that merely stayed alive.
EOF
}

package_id=""
activity_name=""
apk_path=""
out_dir=""
startup_grace_seconds="${STARTUP_GRACE_SECONDS:-10}"
artifact_mode="${ANDROID_LAUNCH_ARTIFACT_MODE:-failures-only}"
require_runtime_ready=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package)
      package_id="$2"
      shift 2
      ;;
    --activity)
      activity_name="$2"
      shift 2
      ;;
    --apk)
      apk_path="$2"
      shift 2
      ;;
    --out-dir)
      out_dir="$2"
      shift 2
      ;;
    --require-runtime-ready)
      require_runtime_ready=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$package_id" || -z "$activity_name" || -z "$out_dir" ]]; then
  usage >&2
  exit 1
fi

mkdir -p "$out_dir"
launch_log="$out_dir/launch.txt"
logcat_log="$out_dir/logcat.txt"
process_log="$out_dir/process.txt"
dumpsys_log="$out_dir/dumpsys_activity.txt"
screen_png="$out_dir/screen.png"
runtime_ui_dump="$out_dir/runtime-ui.xml"
runtime_bridge_log="$out_dir/runtime-bridge.log"

capture_runtime_state() {
  adb shell uiautomator dump /sdcard/solar-launch-smoke-window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/solar-launch-smoke-window.xml "$runtime_ui_dump" >/dev/null 2>&1 || true
  # Some adb/logcat versions treat the trailing silent filter as overriding the
  # selected tag. Read the finite buffer and extract the authoritative bridge
  # records locally so a ready session cannot be mistaken for a timeout.
  adb logcat -d -v threadtime \
    | grep -F " SolarLabRuntimeBridge:" > "$runtime_bridge_log" || true
}

capture_smoke_artifacts() {
  adb logcat -d > "$logcat_log" || true
  adb shell dumpsys activity activities > "$dumpsys_log" || true
  adb exec-out screencap -p > "$screen_png" || true
  capture_runtime_state
}

pidof_or_empty() {
  adb shell pidof "$package_id" 2>/dev/null || true
}

trap 'status=$?; if [[ "$status" -ne 0 ]]; then capture_smoke_artifacts; fi' EXIT

adb wait-for-device
adb logcat -c || true
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
adb shell am force-stop "$package_id" >/dev/null 2>&1 || true

if [[ -n "$apk_path" ]]; then
  adb install -r "$apk_path" | tee "$out_dir/install.txt"
fi

set +e
adb shell am start -W -n "${package_id}/${activity_name}" | tee "$launch_log"
launch_status=${PIPESTATUS[0]}
set -e

echo "am_start_status=${launch_status}" >> "$process_log"

deadline=$((SECONDS + 20))
pid=""
while (( SECONDS < deadline )); do
  pid="$(pidof_or_empty | tr -d '\r' | tr -d '\n')"
  if [[ -n "$pid" ]]; then
    break
  fi
  sleep 1
done

echo "pid_after_launch=${pid:-<none>}" | tee "$process_log"

if [[ -z "$pid" ]]; then
  capture_smoke_artifacts
  if [[ "$launch_status" -ne 0 ]]; then
    echo "Launch command returned ${launch_status}" >> "$process_log"
  fi
  echo "App process never became visible after launch" >&2
  exit 1
fi

sleep "$startup_grace_seconds"
post_grace_pid="$(pidof_or_empty | tr -d '\r' | tr -d '\n')"
echo "pid_after_grace=${post_grace_pid:-<none>}" >> "$process_log"

if [[ "${artifact_mode}" == "always" ]]; then
  capture_smoke_artifacts
fi

if [[ -z "$post_grace_pid" ]]; then
  capture_smoke_artifacts
  echo "App process died during startup grace window" >&2
  exit 1
fi

if [[ "$require_runtime_ready" -eq 1 ]]; then
  runtime_deadline=$((SECONDS + ${RUNTIME_READY_TIMEOUT_SECONDS:-45}))
  runtime_ready=0
  while (( SECONDS < runtime_deadline )); do
    capture_runtime_state

    if [[ -f "$runtime_bridge_log" ]] && grep -Eq \
      'connect\.initial-refresh\.render\.refresh\.result .*lease=ready' \
      "$runtime_bridge_log"; then
      runtime_ready=1
      break
    fi

    if { [[ -f "$runtime_ui_dump" ]] && grep -Eq \
      "Runtime unavailable|Rust stage unavailable|Native runtime session adapter is unavailable|Render host cannot start" \
      "$runtime_ui_dump"; } || { [[ -f "$runtime_bridge_log" ]] && grep -Eq \
      'connect\.createSession\.failure|connect\.runtimeInfo\.failure|connect\.initial-refresh\.(refreshSession|render\.refresh)\.failure|connect\.initial-refresh\.render\.refresh\.result .*lease=missing' \
      "$runtime_bridge_log"; }; then
      capture_smoke_artifacts
      echo "Rust runtime reported an unavailable state during packaged APK startup" >&2
      exit 1
    fi
    sleep 1
  done

  if [[ "$runtime_ready" -ne 1 ]]; then
    capture_smoke_artifacts
    echo "Rust runtime did not report a connected session within ${RUNTIME_READY_TIMEOUT_SECONDS:-45}s" >&2
    exit 1
  fi
  echo "runtime_session=connected" | tee -a "$process_log"
fi

echo "Launch smoke passed for ${package_id}" | tee -a "$process_log"
