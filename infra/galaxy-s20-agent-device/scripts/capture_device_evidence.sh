#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: capture_device_evidence.sh --package PACKAGE --out-dir DIR [--serial SERIAL] [--perfetto-seconds N]

Collect a first-pass rooted evidence bundle from the Galaxy developer device.
EOF
}

serial=""
package_id=""
out_dir=""
perfetto_seconds="0"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      serial="$2"
      shift 2
      ;;
    --package)
      package_id="$2"
      shift 2
      ;;
    --out-dir)
      out_dir="$2"
      shift 2
      ;;
    --perfetto-seconds)
      perfetto_seconds="$2"
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

if [[ -z "${package_id}" || -z "${out_dir}" ]]; then
  usage >&2
  exit 1
fi

adb_args=()
if [[ -n "${serial}" ]]; then
  adb_args=(-s "${serial}")
fi

mkdir -p "${out_dir}"

adb "${adb_args[@]}" wait-for-device
adb "${adb_args[@]}" devices -l > "${out_dir}/adb-devices.txt"
adb "${adb_args[@]}" shell getprop > "${out_dir}/getprop.txt"
adb "${adb_args[@]}" logcat -d > "${out_dir}/logcat.txt" || true
adb "${adb_args[@]}" shell dumpsys activity activities > "${out_dir}/dumpsys-activity.txt" || true
adb "${adb_args[@]}" shell dumpsys window windows > "${out_dir}/dumpsys-window.txt" || true
adb "${adb_args[@]}" shell dumpsys gfxinfo "${package_id}" > "${out_dir}/gfxinfo.txt" || true
adb "${adb_args[@]}" exec-out screencap -p > "${out_dir}/screen.png" || true

perfetto_status="skipped"
if [[ "${perfetto_seconds}" != "0" ]]; then
  perfetto_status="failed"
  trace_remote="/data/local/tmp/solarlab-interactive-trace.pftrace"
  if adb "${adb_args[@]}" shell su -c "perfetto -t ${perfetto_seconds}s -o ${trace_remote} -c - <<'EOF'
buffers: {
  size_kb: 8192
  fill_policy: RING_BUFFER
}
data_sources: {
  config {
    name: \"linux.ftrace\"
    ftrace_config {
      ftrace_events: \"sched/sched_switch\"
      ftrace_events: \"sched/sched_wakeup\"
      atrace_apps: \"${package_id}\"
      atrace_categories: \"am\"
      atrace_categories: \"wm\"
      atrace_categories: \"gfx\"
      atrace_categories: \"view\"
    }
  }
}
duration_ms: ${perfetto_seconds}000
EOF" >/dev/null 2>&1; then
    if adb "${adb_args[@]}" pull "${trace_remote}" "${out_dir}/trace.pftrace" >/dev/null 2>&1; then
      perfetto_status="captured"
    fi
  fi
fi

python3 - <<'PY' "${out_dir}/summary.json" "${package_id}" "${perfetto_status}"
import json
import pathlib
import sys

output_path = pathlib.Path(sys.argv[1])
payload = {
    "schema_version": 1,
    "package_id": sys.argv[2],
    "perfetto_status": sys.argv[3],
    "artifacts": {
        "adb_devices": "adb-devices.txt",
        "getprop": "getprop.txt",
        "logcat": "logcat.txt",
        "dumpsys_activity": "dumpsys-activity.txt",
        "dumpsys_window": "dumpsys-window.txt",
        "gfxinfo": "gfxinfo.txt",
        "screen": "screen.png",
        "trace": "trace.pftrace" if sys.argv[3] == "captured" else None,
    },
}
output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
PY
