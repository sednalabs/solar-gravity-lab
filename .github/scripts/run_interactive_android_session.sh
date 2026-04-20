#!/usr/bin/env bash
set -euo pipefail

json_write() {
  local output_path="$1"
  shift
  python3 - "$output_path" "$@" <<'PY'
import json
import pathlib
import sys

output_path = pathlib.Path(sys.argv[1])
payload = json.loads(sys.argv[2])
output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
PY
}

session_root="${INTERACTIVE_SESSION_ROOT:-dist/interactive-session}"
startup_log_dir="${session_root}/startup-log"
preflight_dir="${session_root}/preflight"
live_access_dir="${session_root}/live-access"
emulator_logcat_dir="${session_root}/emulator-logcat"
ui_dump_dir="${session_root}/ui-dumps"
screenshots_dir="${session_root}/screenshots"
build_cache_dir="${session_root}/build-cache"
install_history_dir="${session_root}/install-history"
session_state_path="${session_root}/session-state.json"
active_build_path="${session_root}/active-build.json"
finish_sentinel="${INTERACTIVE_SESSION_END_SENTINEL:-${session_root}/finish-session}"
mcp_health_url="${INTERACTIVE_MCP_HEALTH_URL:-http://127.0.0.1:9526/health}"
mcp_bind_addr="${INTERACTIVE_MCP_BIND_ADDR:-127.0.0.1:9526}"
mcp_allowed_hosts="${INTERACTIVE_MCP_ALLOWED_HOSTS:-localhost,127.0.0.1,::1}"
ttyd_port="${INTERACTIVE_DEBUG_TTYD_PORT:-7681}"
session_timeout_minutes="${INTERACTIVE_SESSION_TIMEOUT_MINUTES:-90}"
keep_session_on_failure="${INTERACTIVE_KEEP_SESSION_ON_FAILURE:-true}"
app_apk="${INTERACTIVE_APP_APK:?INTERACTIVE_APP_APK is required}"
build_manifest_path="${INTERACTIVE_BUILD_MANIFEST:?INTERACTIVE_BUILD_MANIFEST is required}"
app_package="${INTERACTIVE_APP_PACKAGE:?INTERACTIVE_APP_PACKAGE is required}"
app_activity="${INTERACTIVE_APP_ACTIVITY:?INTERACTIVE_APP_ACTIVITY is required}"
mcp_workspace_dir="${INTERACTIVE_MCP_WORKSPACE_DIR:?INTERACTIVE_MCP_WORKSPACE_DIR is required}"
cloudflared_bin="${INTERACTIVE_CLOUDFLARED_BIN:?INTERACTIVE_CLOUDFLARED_BIN is required}"
debug_hostname="${INTERACTIVE_DEBUG_HOSTNAME:-}"
debug_tunnel_token="${INTERACTIVE_DEBUG_TUNNEL_TOKEN:-}"
mcp_public_hostname="${INTERACTIVE_MCP_PUBLIC_HOSTNAME:-}"
ttyd_bin="${INTERACTIVE_TTYD_BIN:-ttyd}"
session_start_iso="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

mkdir -p \
  "${startup_log_dir}" \
  "${preflight_dir}" \
  "${live_access_dir}" \
  "${emulator_logcat_dir}" \
  "${ui_dump_dir}" \
  "${screenshots_dir}" \
  "${build_cache_dir}" \
  "${install_history_dir}" \
  "${session_root}/android-emulator-mcp-artifacts"

touch "${startup_log_dir}/session.log"

log() {
  local message="$1"
  printf '[interactive-session] %s\n' "${message}" | tee -a "${startup_log_dir}/session.log"
}

port_is_available() {
  local port="$1"
  python3 - "${port}" <<'PY'
import socket
import sys

port = int(sys.argv[1])
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    sock.bind(("127.0.0.1", port))
except OSError:
    sys.exit(1)
finally:
    sock.close()
PY
}

pick_loopback_port() {
  local preferred_port="$1"
  if port_is_available "${preferred_port}"; then
    printf '%s\n' "${preferred_port}"
    return 0
  fi

  python3 <<'PY'
import socket

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.bind(("127.0.0.1", 0))
print(sock.getsockname()[1])
sock.close()
PY
}

write_live_status() {
  local payload="$1"
  json_write "${live_access_dir}/status.json" "${payload}"
}

write_session_state() {
  local payload="$1"
  json_write "${session_state_path}" "${payload}"
}

write_active_build_state() {
  local status="$1"
  local preflight_json_path="$2"
  python3 - "${active_build_path}" "${build_manifest_path}" "${status}" "${preflight_json_path}" <<'PY'
import json
import pathlib
import sys
from datetime import datetime, timezone

output_path = pathlib.Path(sys.argv[1])
manifest_path = pathlib.Path(sys.argv[2])
status = sys.argv[3]
preflight_path = pathlib.Path(sys.argv[4])

manifest = json.loads(manifest_path.read_text())
preflight = json.loads(preflight_path.read_text()) if preflight_path.exists() else None
payload = {
    "schema_version": 1,
    "status": status,
    "activated_at_iso": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "manifest": manifest,
    "proof": {
        "preflight_json": str(preflight_path),
        "launch_smoke_dir": str(preflight_path.parent / "startup-smoke"),
        "mcp_health": str(preflight_path.parent / "mcp-health.json"),
    },
    "preflight": preflight,
}
output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
PY
}

append_install_history() {
  local install_status="$1"
  local preflight_json_path="$2"
  local output_path
  output_path="${install_history_dir}/$(date -u +%Y%m%dT%H%M%SZ)-startup-preflight.json"
  python3 - "${output_path}" "${build_manifest_path}" "${install_status}" "${preflight_json_path}" <<'PY'
import json
import pathlib
import sys
from datetime import datetime, timezone

output_path = pathlib.Path(sys.argv[1])
manifest_path = pathlib.Path(sys.argv[2])
install_status = sys.argv[3]
preflight_path = pathlib.Path(sys.argv[4])

manifest = json.loads(manifest_path.read_text())
preflight = json.loads(preflight_path.read_text()) if preflight_path.exists() else None
payload = {
    "schema_version": 1,
    "recorded_at_iso": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "status": install_status,
    "manifest": manifest,
    "preflight": preflight,
}
output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
PY
}

capture_final_artifacts() {
  adb logcat -d > "${emulator_logcat_dir}/final-logcat.txt" 2>&1 || true
  adb shell dumpsys activity activities > "${ui_dump_dir}/dumpsys-activity.txt" 2>&1 || true
  adb shell dumpsys window windows > "${ui_dump_dir}/dumpsys-window.txt" 2>&1 || true
  adb shell uiautomator dump /sdcard/interactive-session-dump.xml >/dev/null 2>&1 || true
  adb pull /sdcard/interactive-session-dump.xml "${ui_dump_dir}/final-window-dump.xml" >/dev/null 2>&1 || true
  adb exec-out screencap -p > "${screenshots_dir}/final-screen.png" 2>/dev/null || true
  curl -fsSL "${mcp_health_url}" -o "${session_root}/mcp-health.json" 2>/dev/null || true
}

mcp_pid=""
ttyd_pid=""
cloudflared_pid=""
logcat_pid=""
final_status="failure"
final_reason="session_not_started"

cleanup() {
  capture_final_artifacts

  for pid in "${cloudflared_pid}" "${ttyd_pid}" "${logcat_pid}" "${mcp_pid}"; do
    if [[ -n "${pid}" ]]; then
      kill "${pid}" >/dev/null 2>&1 || true
      wait "${pid}" >/dev/null 2>&1 || true
    fi
  done

  write_session_state "$(python3 - <<'PY' "${session_start_iso}" "${final_status}" "${final_reason}" "${session_timeout_minutes}" "${finish_sentinel}"
import json
import sys

payload = {
    "schema_version": 1,
    "session_start_iso": sys.argv[1],
    "session_end_iso": __import__("datetime").datetime.utcnow().replace(microsecond=0).isoformat() + "Z",
    "status": sys.argv[2],
    "reason": sys.argv[3],
    "timeout_minutes": int(sys.argv[4]),
    "finish_sentinel": sys.argv[5],
}
print(json.dumps(payload))
PY
)"
}

trap cleanup EXIT

adb wait-for-device
adb logcat -c || true
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true

export ANDROID_EMULATOR_MCP_SDK_ROOT="${ANDROID_SDK_ROOT_DEFAULT:-${ANDROID_SDK_ROOT:-}}"
export ANDROID_EMULATOR_MCP_ARTIFACT_DIR="${session_root}/android-emulator-mcp-artifacts"
export ANDROID_EMULATOR_MCP_BIND_ADDR="${mcp_bind_addr}"
if [[ -n "${mcp_public_hostname}" ]]; then
  mcp_allowed_hosts="${mcp_allowed_hosts},${mcp_public_hostname}"
fi

export ANDROID_EMULATOR_MCP_ALLOWED_HOSTS="${mcp_allowed_hosts}"
export ANDROID_EMULATOR_MCP_HTTP_ALLOW_RESUME=0
export ANDROID_EMULATOR_MCP_INTERACTIVE_SESSION_ROOT="${session_root}"
export ANDROID_EMULATOR_MCP_INTERACTIVE_SESSION_APP_PACKAGE="${app_package}"
export ANDROID_EMULATOR_MCP_INTERACTIVE_SESSION_APP_ACTIVITY="${app_activity}"
export ANDROID_EMULATOR_MCP_INTERACTIVE_SESSION_GITHUB_REPOSITORY="${INTERACTIVE_SESSION_GITHUB_REPOSITORY:-${GITHUB_REPOSITORY:-}}"
export ANDROID_EMULATOR_MCP_INTERACTIVE_SESSION_GITHUB_TOKEN="${INTERACTIVE_SESSION_GITHUB_TOKEN:-}"

log "Starting android-emulator-mcp on ${mcp_bind_addr}"
"${mcp_workspace_dir}/target/release/android-emulator-mcp" \
  > "${startup_log_dir}/android-emulator-mcp.stdout.log" \
  2> "${startup_log_dir}/android-emulator-mcp.stderr.log" &
mcp_pid=$!

health_ready="false"
for _ in $(seq 1 60); do
  if curl -fsSL "${mcp_health_url}" -o "${startup_log_dir}/initial-health.json" >/dev/null 2>&1; then
    health_ready="true"
    break
  fi
  sleep 1
done

if [[ "${health_ready}" != "true" ]]; then
  final_status="failure"
  final_reason="mcp_health_unavailable"
  write_live_status '{"schema_version":1,"status":"failed","reason":"mcp_health_unavailable"}'
  log "android-emulator-mcp never reported healthy"
  exit 1
fi

log "Running install-and-launch preflight"
preflight_ok="true"
if ! bash .github/scripts/run_interactive_android_preflight.sh \
  --mcp-health-url "${mcp_health_url}" \
  --apk "${app_apk}" \
  --package "${app_package}" \
  --activity "${app_activity}" \
  --out-dir "${preflight_dir}"; then
  preflight_ok="false"
fi

if [[ "${preflight_ok}" == "true" ]]; then
  append_install_history "ready" "${preflight_dir}/preflight.json"
  write_active_build_state "ready" "${preflight_dir}/preflight.json"
else
  append_install_history "action_required" "${preflight_dir}/preflight.json"
fi

adb logcat -v threadtime > "${emulator_logcat_dir}/live-logcat.txt" 2>&1 &
logcat_pid=$!

if [[ -z "${debug_hostname}" || -z "${debug_tunnel_token}" ]]; then
  write_live_status '{"schema_version":1,"status":"failed","reason":"missing_live_access_secret"}'
  final_status="failure"
  final_reason="missing_live_access_secret"
  log "Missing Cloudflare live-access configuration"
  exit 1
fi

shell_wrapper="${live_access_dir}/interactive-shell.sh"
cat > "${shell_wrapper}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
cd "${GITHUB_WORKSPACE}"
export INTERACTIVE_SESSION_ROOT="${session_root}"
export INTERACTIVE_MCP_HEALTH_URL="${mcp_health_url}"
export ANDROID_SERIAL="\${ANDROID_SERIAL:-emulator-5554}"
echo "Interactive Android session ready"
echo "Workspace: ${GITHUB_WORKSPACE}"
echo "Artifacts: ${session_root}"
echo "MCP health: ${mcp_health_url}"
echo "Finish early with: touch ${finish_sentinel}"
exec bash -li
EOF
chmod 0755 "${shell_wrapper}"

requested_ttyd_port="${ttyd_port}"
ttyd_port="$(pick_loopback_port "${requested_ttyd_port}")"
if [[ "${ttyd_port}" != "${requested_ttyd_port}" ]]; then
  log "Loopback port ${requested_ttyd_port} was busy; selected ttyd fallback port ${ttyd_port}"
fi

log "Starting ttyd on loopback port ${ttyd_port}"
"${ttyd_bin}" --interface 127.0.0.1 --port "${ttyd_port}" --writable "${shell_wrapper}" \
  > "${live_access_dir}/ttyd.log" 2>&1 &
ttyd_pid=$!

cloudflared_config="${live_access_dir}/cloudflared.yml"
cat > "${cloudflared_config}" <<EOF
ingress:
  - hostname: ${debug_hostname}
    service: http://127.0.0.1:${ttyd_port}
EOF

if [[ -n "${mcp_public_hostname}" ]]; then
  cat >> "${cloudflared_config}" <<EOF
  - hostname: ${mcp_public_hostname}
    service: http://${mcp_bind_addr}
EOF
fi

cat >> "${cloudflared_config}" <<EOF
  - service: http_status:404
EOF

log "Starting cloudflared tunnel for ${debug_hostname}"
"${cloudflared_bin}" --no-autoupdate tunnel --config "${cloudflared_config}" run --token "${debug_tunnel_token}" \
  > "${live_access_dir}/cloudflared.log" 2>&1 &
cloudflared_pid=$!

sleep 5

if ! kill -0 "${ttyd_pid}" >/dev/null 2>&1; then
  write_live_status '{"schema_version":1,"status":"failed","reason":"ttyd_exited_early"}'
  final_status="failure"
  final_reason="ttyd_exited_early"
  log "ttyd exited before the session opened"
  exit 1
fi

if ! kill -0 "${cloudflared_pid}" >/dev/null 2>&1; then
  write_live_status '{"schema_version":1,"status":"failed","reason":"cloudflared_exited_early"}'
  final_status="failure"
  final_reason="cloudflared_exited_early"
  log "cloudflared exited before the session opened"
  exit 1
fi

write_live_status "$(python3 - <<'PY' "${debug_hostname}" "${mcp_public_hostname}" "${ttyd_port}" "${mcp_bind_addr}" "${session_timeout_minutes}" "${finish_sentinel}"
import json
import sys

payload = {
    "schema_version": 1,
    "status": "ready",
    "human_terminal": {
        "status": "ready",
        "hostname": sys.argv[1],
        "loopback_port": int(sys.argv[3]),
        "auth_mode": "cloudflare_access_human_identity",
    },
    "agent_mcp": {
        "status": "ready" if sys.argv[2] else "disabled",
        "hostname": sys.argv[2] or None,
        "loopback_bind_addr": sys.argv[4],
        "healthcheck_url": f"https://{sys.argv[2]}/health" if sys.argv[2] else None,
        "auth_mode": "cloudflare_access_service_token" if sys.argv[2] else None,
    },
    "session_timeout_minutes": int(sys.argv[5]),
    "finish_sentinel": sys.argv[6],
}
print(json.dumps(payload))
PY
)"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "## interactive-android-session"
    echo "- browser terminal hostname: \`${debug_hostname}\`"
    if [[ -n "${mcp_public_hostname}" ]]; then
      echo "- agent MCP hostname: \`${mcp_public_hostname}\`"
      echo "- agent auth: \`Cloudflare Access service token\`"
    else
      echo "- agent MCP hostname: \`disabled\`"
    fi
    echo "- timeout minutes: \`${session_timeout_minutes}\`"
    echo "- finish early inside the session with: \`touch ${finish_sentinel}\`"
    echo "- artifacts root: \`${session_root}\`"
  } >> "${GITHUB_STEP_SUMMARY}"
fi

if [[ "${preflight_ok}" != "true" ]]; then
  log "Preflight failed but live access is available for debugging"
  if [[ "${keep_session_on_failure}" != "true" ]]; then
    final_status="failure"
    final_reason="preflight_failed"
    exit 1
  fi
fi

deadline=$((SECONDS + (session_timeout_minutes * 60)))

while (( SECONDS < deadline )); do
  if [[ -f "${finish_sentinel}" ]]; then
    final_status="success"
    final_reason="ended_by_operator"
    log "Session ended by operator sentinel"
    exit 0
  fi

  if ! kill -0 "${mcp_pid}" >/dev/null 2>&1; then
    final_status="failure"
    final_reason="mcp_exited_early"
    log "android-emulator-mcp exited before timeout"
    exit 1
  fi

  if ! kill -0 "${ttyd_pid}" >/dev/null 2>&1; then
    final_status="failure"
    final_reason="ttyd_exited_during_session"
    log "ttyd exited during the session"
    exit 1
  fi

  if ! kill -0 "${cloudflared_pid}" >/dev/null 2>&1; then
    final_status="failure"
    final_reason="cloudflared_exited_during_session"
    log "cloudflared exited during the session"
    exit 1
  fi

  sleep 10
done

if [[ "${preflight_ok}" == "true" ]]; then
  final_status="success"
  final_reason="timeout_reached"
else
  final_status="failure"
  final_reason="preflight_failed_after_debug_window"
fi

log "Session window completed"
