#!/usr/bin/env bash
set -euo pipefail

log_path="${1:?usage: gradle_configuration_cache_status.sh <log-path> [mode]}"
mode="${2:-disabled}"

if [[ "${mode}" != "enabled" ]]; then
  printf '%s\n' "disabled"
  exit 0
fi

if [[ ! -f "${log_path}" ]]; then
  printf '%s\n' "enabled-no-log"
  exit 0
fi

if grep -Fq "Reusing configuration cache." "${log_path}" || \
   grep -Fq "Configuration cache entry reused." "${log_path}"; then
  printf '%s\n' "reused"
elif grep -Fq "Configuration cache entry stored." "${log_path}"; then
  printf '%s\n' "stored"
elif grep -Fiq "configuration cache" "${log_path}"; then
  printf '%s\n' "enabled-no-store"
else
  printf '%s\n' "enabled-no-signal"
fi
