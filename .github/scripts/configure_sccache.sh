#!/usr/bin/env bash
set -euo pipefail

emit_output() {
  local key="$1"
  local value="$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$key" "$value" >> "${GITHUB_OUTPUT}"
  fi
}

sccache_path="${SCCACHE_PATH:-$(command -v sccache || true)}"
if [[ -z "${sccache_path}" ]]; then
  emit_output "enabled" "false"
  emit_output "reason" "missing-binary"
  exit 0
fi

required_vars=(
  SCCACHE_BUCKET
  SCCACHE_ENDPOINT
  SCCACHE_REGION
  AWS_ACCESS_KEY_ID
  AWS_SECRET_ACCESS_KEY
)
missing_vars=()
for var_name in "${required_vars[@]}"; do
  if [[ -z "${!var_name:-}" ]]; then
    missing_vars+=("${var_name}")
  fi
done

if [[ "${#missing_vars[@]}" -gt 0 ]]; then
  emit_output "enabled" "false"
  emit_output "reason" "missing-config"
  emit_output "missing" "$(IFS=,; echo "${missing_vars[*]}")"
  exit 0
fi

export RUSTC_WRAPPER="${sccache_path}"
export CARGO_INCREMENTAL=0

if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "SCCACHE_PATH=${sccache_path}"
    echo "RUSTC_WRAPPER=${sccache_path}"
    echo "CARGO_INCREMENTAL=0"
    echo "SCCACHE_REGION=${SCCACHE_REGION}"
    echo "SCCACHE_S3_USE_SSL=${SCCACHE_S3_USE_SSL:-true}"
    echo "CMAKE_C_COMPILER_LAUNCHER=${sccache_path}"
    echo "CMAKE_CXX_COMPILER_LAUNCHER=${sccache_path}"
  } >> "${GITHUB_ENV}"
fi

"${sccache_path}" --stop-server >/dev/null 2>&1 || true
"${sccache_path}" --zero-stats >/dev/null 2>&1 || true

emit_output "enabled" "true"
emit_output "reason" "configured"
emit_output "path" "${sccache_path}"
emit_output "bucket" "${SCCACHE_BUCKET}"
emit_output "endpoint" "${SCCACHE_ENDPOINT}"
emit_output "key_prefix" "${SCCACHE_S3_KEY_PREFIX:-}"
