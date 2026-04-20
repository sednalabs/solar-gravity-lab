#!/usr/bin/env bash
set -euo pipefail

emit_output() {
  local key="$1"
  local value="$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$key" "$value" >> "${GITHUB_OUTPUT}"
  fi
}

emit_summary() {
  local enabled="$1"
  local reason="$2"
  local missing="${3:-}"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      echo "## sccache configuration"
      echo "- enabled: \`${enabled}\`"
      echo "- reason: \`${reason}\`"
      if [[ -n "${missing}" ]]; then
        echo "- missing vars: \`${missing}\`"
      fi
      if [[ "${enabled}" == "true" ]]; then
        echo "- path: \`${sccache_path}\`"
        echo "- bucket: \`${SCCACHE_BUCKET}\`"
        echo "- endpoint: \`${SCCACHE_ENDPOINT}\`"
        echo "- key prefix: \`${SCCACHE_S3_KEY_PREFIX:-}\`"
      fi
    } >> "${GITHUB_STEP_SUMMARY}"
  fi
}

sccache_path="${SCCACHE_PATH:-$(command -v sccache || true)}"
if [[ -z "${sccache_path}" ]]; then
  emit_output "enabled" "false"
  emit_output "reason" "missing-binary"
  emit_summary "false" "missing-binary"
  exit 0
fi

resolved_aws_access_key_id="${AWS_ACCESS_KEY_ID:-${SCCACHE_AWS_ACCESS_KEY_ID:-}}"
resolved_aws_secret_access_key="${AWS_SECRET_ACCESS_KEY:-${SCCACHE_AWS_SECRET_ACCESS_KEY:-}}"
credential_source="${SCCACHE_CREDENTIAL_SOURCE:-}"
if [[ -z "${credential_source}" ]]; then
  if [[ -n "${AWS_ACCESS_KEY_ID:-}" || -n "${AWS_SECRET_ACCESS_KEY:-}" ]]; then
    credential_source="aws-env"
  elif [[ -n "${SCCACHE_AWS_ACCESS_KEY_ID:-}" || -n "${SCCACHE_AWS_SECRET_ACCESS_KEY:-}" ]]; then
    credential_source="dedicated-sccache-env"
  else
    credential_source="unset"
  fi
fi

required_vars=(
  SCCACHE_BUCKET
  SCCACHE_ENDPOINT
  SCCACHE_REGION
)
missing_vars=()
for var_name in "${required_vars[@]}"; do
  if [[ -z "${!var_name:-}" ]]; then
    missing_vars+=("${var_name}")
  fi
done
if [[ -z "${resolved_aws_access_key_id}" ]]; then
  missing_vars+=("AWS_ACCESS_KEY_ID|SCCACHE_AWS_ACCESS_KEY_ID")
fi
if [[ -z "${resolved_aws_secret_access_key}" ]]; then
  missing_vars+=("AWS_SECRET_ACCESS_KEY|SCCACHE_AWS_SECRET_ACCESS_KEY")
fi

if [[ "${#missing_vars[@]}" -gt 0 ]]; then
  missing_csv="$(IFS=,; echo "${missing_vars[*]}")"
  emit_output "enabled" "false"
  emit_output "reason" "missing-config"
  emit_output "missing" "${missing_csv}"
  emit_summary "false" "missing-config" "${missing_csv}"
  exit 0
fi

export RUSTC_WRAPPER="${sccache_path}"
export CARGO_INCREMENTAL=0

if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "SCCACHE_PATH=${sccache_path}"
    echo "RUSTC_WRAPPER=${sccache_path}"
    echo "CARGO_INCREMENTAL=0"
    echo "AWS_ACCESS_KEY_ID=${resolved_aws_access_key_id}"
    echo "AWS_SECRET_ACCESS_KEY=${resolved_aws_secret_access_key}"
    echo "SCCACHE_REGION=${SCCACHE_REGION}"
    echo "SCCACHE_S3_USE_SSL=${SCCACHE_S3_USE_SSL:-true}"
    echo "CMAKE_C_COMPILER_LAUNCHER=${sccache_path}"
    echo "CMAKE_CXX_COMPILER_LAUNCHER=${sccache_path}"
  } >> "${GITHUB_ENV}"
fi

export AWS_ACCESS_KEY_ID="${resolved_aws_access_key_id}"
export AWS_SECRET_ACCESS_KEY="${resolved_aws_secret_access_key}"

"${sccache_path}" --stop-server >/dev/null 2>&1 || true
"${sccache_path}" --zero-stats >/dev/null 2>&1 || true

emit_output "enabled" "true"
emit_output "reason" "configured"
emit_output "path" "${sccache_path}"
emit_output "bucket" "${SCCACHE_BUCKET}"
emit_output "endpoint" "${SCCACHE_ENDPOINT}"
emit_output "key_prefix" "${SCCACHE_S3_KEY_PREFIX:-}"
emit_output "credential_source" "${credential_source}"
emit_summary "true" "configured"
