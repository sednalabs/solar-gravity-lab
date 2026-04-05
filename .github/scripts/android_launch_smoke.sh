#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: android_launch_smoke.sh --package PACKAGE --activity ACTIVITY [--apk APK] --out-dir DIR

Install an APK if provided, launch the activity, wait for the app process to stay
alive through startup, and write diagnostic artifacts to the output directory.
EOF
}

package_id=""
activity_name=""
apk_path=""
out_dir=""
startup_grace_seconds="${STARTUP_GRACE_SECONDS:-10}"

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
  pid="$(adb shell pidof "$package_id" 2>/dev/null | tr -d '\r' | tr -d '\n')"
  if [[ -n "$pid" ]]; then
    break
  fi
  sleep 1
done

echo "pid_after_launch=${pid:-<none>}" | tee "$process_log"

if [[ -z "$pid" ]]; then
  adb logcat -d > "$logcat_log" || true
  adb shell dumpsys activity activities > "$dumpsys_log" || true
  adb exec-out screencap -p > "$screen_png" || true
  if [[ "$launch_status" -ne 0 ]]; then
    echo "Launch command returned ${launch_status}" >> "$process_log"
  fi
  echo "App process never became visible after launch" >&2
  exit 1
fi

sleep "$startup_grace_seconds"
post_grace_pid="$(adb shell pidof "$package_id" 2>/dev/null | tr -d '\r' | tr -d '\n')"
echo "pid_after_grace=${post_grace_pid:-<none>}" >> "$process_log"

adb logcat -d > "$logcat_log" || true
adb shell dumpsys activity activities > "$dumpsys_log" || true
adb exec-out screencap -p > "$screen_png" || true

if [[ -z "$post_grace_pid" ]]; then
  echo "App process died during startup grace window" >&2
  exit 1
fi

echo "Launch smoke passed for ${package_id}" | tee -a "$process_log"
