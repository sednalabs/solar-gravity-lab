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
openai_loop_dir="${session_root}/openai-loop"
openai_loop_run_root="${session_root}/openai-loop-runs"
codex_bridge_dir="${session_root}/codex-bridge"
codex_bridge_run_root="${session_root}/codex-bridge-runs"
codex_provider_manifest_path="${codex_bridge_dir}/provider-manifest.json"
codex_provider_manifest_validation_path="${codex_bridge_dir}/provider-manifest-validation.json"
codex_provider_manifest_validation_error_path="${codex_bridge_dir}/provider-manifest-validation.err"
session_state_path="${session_root}/session-state.json"
active_build_path="${session_root}/active-build.json"
openai_loop_status_path="${openai_loop_dir}/status.json"
codex_bridge_status_path="${codex_bridge_dir}/status.json"
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
openai_default_model="${INTERACTIVE_OPENAI_DEFAULT_MODEL:-gpt-5.4}"
openai_default_serial="${INTERACTIVE_OPENAI_DEFAULT_SERIAL:-emulator-5554}"
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
  "${openai_loop_dir}" \
  "${openai_loop_run_root}" \
  "${codex_bridge_dir}" \
  "${codex_bridge_run_root}" \
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

write_openai_loop_status() {
  local payload="$1"
  json_write "${openai_loop_status_path}" "${payload}"
}

write_codex_bridge_status() {
  local payload="$1"
  json_write "${codex_bridge_status_path}" "${payload}"
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

stage_interactive_model_helpers() {
  local openai_adapter_bin="${mcp_workspace_dir}/adapters/openai/bin/openai-android-loop.mjs"
  local codex_adapter_bin="${mcp_workspace_dir}/adapters/codex/bin/codex-android-observe.mjs"
  local codex_dynamic_tools_bin="${mcp_workspace_dir}/adapters/codex/bin/codex-android-tools.mjs"
  local config_path="${openai_loop_dir}/config.json"
  local openai_helper_path="${live_access_dir}/openai-android-loop.sh"
  local codex_helper_path="${live_access_dir}/codex-android-observe.sh"
  local codex_dynamic_tool_helper_path="${live_access_dir}/codex-android-tools.sh"

  if [[ -f "${openai_adapter_bin}" || -f "${codex_adapter_bin}" || -f "${codex_dynamic_tools_bin}" ]]; then
    python3 .github/scripts/write_interactive_openai_loop_config.py \
      --mcp-url "http://${mcp_bind_addr}/mcp" \
      --mcp-health-url "${mcp_health_url}" \
      --session-root "${session_root}" \
      --build-manifest "${build_manifest_path}" \
      --mcp-workspace-dir "${mcp_workspace_dir}" \
      --default-model "${openai_default_model}" \
      --default-serial "${openai_default_serial}" \
      --default-package-name "${app_package}" \
      --default-activity "${app_activity}" \
      --output-root "${openai_loop_run_root}" \
      --output-json "${config_path}"
  fi

  if [[ ! -f "${openai_adapter_bin}" ]]; then
    write_openai_loop_status "$(python3 - <<'PY' "${openai_adapter_bin}"
import json
import sys

print(json.dumps({
    "schema_version": 1,
    "status": "unavailable",
    "reason": "adapter_cli_missing",
    "adapter_bin": sys.argv[1],
}))
PY
)"
  else
    cat > "${openai_helper_path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

if [[ -z "\${OPENAI_API_KEY:-}" ]]; then
  echo "OPENAI_API_KEY is only required for this optional standalone OpenAI Responses helper." >&2
  echo "Normal Codex-driven use of the hosted Android session does not require a separate OpenAI API key." >&2
  exit 1
fi

exec node "${openai_adapter_bin}" --config "${config_path}" "\$@"
EOF
    chmod 0755 "${openai_helper_path}"

    write_openai_loop_status "$(python3 - <<'PY' "${config_path}" "${openai_helper_path}" "${openai_loop_run_root}" "${openai_default_model}"
import json
import sys

print(json.dumps({
    "schema_version": 1,
    "status": "ready",
    "mode": "standalone_openai_api",
    "config_path": sys.argv[1],
    "helper_path": sys.argv[2],
    "output_root": sys.argv[3],
    "default_model": sys.argv[4],
}))
PY
)"
  fi

  if [[ ! -f "${codex_dynamic_tools_bin}" ]]; then
    rm -f "${codex_dynamic_tool_helper_path}"
    rm -f "${codex_helper_path}"
    write_codex_bridge_status "$(python3 - <<'PY' "${codex_dynamic_tools_bin}" "${codex_adapter_bin}"
import json
import sys

print(json.dumps({
    "schema_version": 1,
    "status": "unavailable",
    "reason": "adapter_cli_missing",
    "dynamic_tool_adapter_bin": sys.argv[1],
    "observe_adapter_bin": sys.argv[2],
}))
PY
)"
  else
    cat > "${codex_dynamic_tool_helper_path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

exec node "${codex_dynamic_tools_bin}" --config "${config_path}" "\$@"
EOF
    chmod 0755 "${codex_dynamic_tool_helper_path}"

    if node "${codex_dynamic_tools_bin}" \
      --config "${config_path}" \
      --session-root "${session_root}" \
      --artifact-root "${codex_bridge_run_root}" \
      --build-manifest "${build_manifest_path}" \
      manifest > "${codex_provider_manifest_path}"; then
      if node "${codex_dynamic_tools_bin}" \
        validate-manifest \
        --manifest "${codex_provider_manifest_path}" \
        > "${codex_provider_manifest_validation_path}" \
        2> "${codex_provider_manifest_validation_error_path}"; then
        provider_manifest_status="ready"
        rm -f "${codex_provider_manifest_validation_error_path}"
      else
        if grep -Eq "Unknown argument: validate-manifest|Unknown command: validate-manifest" "${codex_provider_manifest_validation_error_path}"; then
          provider_manifest_status="validation_unavailable"
        else
          provider_manifest_status="invalid"
        fi
        python3 - <<'PY' "${codex_provider_manifest_validation_path}" "${codex_provider_manifest_validation_error_path}" "${provider_manifest_status}"
import json
import pathlib
import sys

output_path = pathlib.Path(sys.argv[1])
error_path = pathlib.Path(sys.argv[2])
status = sys.argv[3]
output_path.write_text(json.dumps({
    "ok": False if status == "invalid" else None,
    "status": status,
    "error": (error_path.read_text(errors="replace").strip() if error_path.exists() else "") or "provider manifest validation failed",
}, indent=2, sort_keys=True) + "\n")
PY
      fi
    else
      provider_manifest_status="unavailable"
      rm -f "${codex_provider_manifest_path}"
      rm -f "${codex_provider_manifest_validation_path}"
      rm -f "${codex_provider_manifest_validation_error_path}"
    fi

    if [[ -f "${codex_adapter_bin}" ]]; then
    cat > "${codex_helper_path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

exec node "${codex_adapter_bin}" --config "${config_path}" "\$@"
EOF
      chmod 0755 "${codex_helper_path}"
    else
      rm -f "${codex_helper_path}"
    fi

    write_codex_bridge_status "$(python3 - <<'PY' "${config_path}" "${codex_dynamic_tool_helper_path}" "${codex_helper_path}" "${codex_bridge_run_root}" "${codex_provider_manifest_path}" "${codex_provider_manifest_validation_path}" "${provider_manifest_status}"
import json
import sys

provider_manifest_ready = sys.argv[7] == "ready"
provider_manifest_available = sys.argv[7] in {"ready", "invalid", "validation_unavailable"}
print(json.dumps({
    "schema_version": 1,
    "status": "ready",
    "mode": "native_dynamic_tools",
    "config_path": sys.argv[1],
    "dynamic_tool_helper_path": sys.argv[2],
    "dynamic_tool_command": sys.argv[2],
    "observe_helper_path": sys.argv[3],
    "output_root": sys.argv[4],
    "provider_manifest_path": sys.argv[5] if provider_manifest_available else None,
    "provider_manifest_validation_path": sys.argv[6] if provider_manifest_available else None,
    "provider_manifest_status": sys.argv[7],
    "provider_manifest_validated": provider_manifest_ready,
    "tool_names": ["android_observe", "android_step"],
}))
PY
)"
  fi
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

stage_interactive_model_helpers

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
export INTERACTIVE_OPENAI_LOOP_BIN="${live_access_dir}/openai-android-loop.sh"
export INTERACTIVE_OPENAI_LOOP_CONFIG="${openai_loop_dir}/config.json"
export INTERACTIVE_OPENAI_LOOP_OUTPUT_ROOT="${openai_loop_run_root}"
export INTERACTIVE_CODEX_OBSERVE_BIN="${live_access_dir}/codex-android-observe.sh"
export INTERACTIVE_CODEX_DYNAMIC_TOOL_BIN="${live_access_dir}/codex-android-tools.sh"
export INTERACTIVE_CODEX_BRIDGE_OUTPUT_ROOT="${codex_bridge_run_root}"
export INTERACTIVE_CODEX_PROVIDER_MANIFEST="${codex_provider_manifest_path}"
if [[ -x "${live_access_dir}/codex-android-tools.sh" ]]; then
  export CODEX_DYNAMIC_TOOL_COMMAND="${live_access_dir}/codex-android-tools.sh"
else
  unset CODEX_DYNAMIC_TOOL_COMMAND || true
fi
echo "Interactive Android session ready"
echo "Workspace: ${GITHUB_WORKSPACE}"
echo "Artifacts: ${session_root}"
echo "MCP health: ${mcp_health_url}"
if [[ -x "${live_access_dir}/codex-android-tools.sh" ]]; then
  echo "Codex native dynamic-tool helper: ${live_access_dir}/codex-android-tools.sh"
  echo "Codex dynamic-tool command: \${CODEX_DYNAMIC_TOOL_COMMAND}"
  if [[ -f "${codex_provider_manifest_path}" ]]; then
    echo "Codex Android provider manifest: ${codex_provider_manifest_path}"
  fi
fi
if [[ -x "${live_access_dir}/codex-android-observe.sh" ]]; then
  echo "Codex bridge observe helper: ${live_access_dir}/codex-android-observe.sh"
  echo "Codex bridge output root: ${codex_bridge_run_root}"
fi
if [[ -x "${live_access_dir}/openai-android-loop.sh" ]]; then
  echo "Standalone OpenAI helper: ${live_access_dir}/openai-android-loop.sh"
  echo "Standalone OpenAI output root: ${openai_loop_run_root}"
fi
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
    echo "- finish early inside the session with: \`touch dist/interactive-session/finish-session\`"
    echo "- artifacts root: \`${session_root}\`"
    if [[ -x "${live_access_dir}/codex-android-tools.sh" ]]; then
      echo "- Codex native dynamic tools: \`available\`"
      if [[ "${provider_manifest_status:-unavailable}" == "ready" ]]; then
        echo "- Codex Android provider manifest: \`available and validated\`"
      elif [[ "${provider_manifest_status:-unavailable}" == "invalid" ]]; then
        echo "- Codex Android provider manifest: \`invalid\`"
      elif [[ "${provider_manifest_status:-unavailable}" == "validation_unavailable" ]]; then
        echo "- Codex Android provider manifest: \`available; validation unsupported by selected provider ref\`"
      else
        echo "- Codex Android provider manifest: \`unavailable for the selected android-emulator-mcp ref\`"
      fi
    else
      echo "- Codex native dynamic tools: \`unavailable for the selected android-emulator-mcp ref\`"
    fi
    if [[ -x "${live_access_dir}/codex-android-observe.sh" ]]; then
      echo "- Codex bridge observe helper: \`available\`"
    else
      echo "- Codex bridge observe helper: \`unavailable for the selected android-emulator-mcp ref\`"
    fi
    if [[ -x "${live_access_dir}/openai-android-loop.sh" ]]; then
      echo "- standalone OpenAI helper: \`available (optional API mode)\`"
    else
      echo "- standalone OpenAI helper: \`unavailable for the selected android-emulator-mcp ref\`"
    fi
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
