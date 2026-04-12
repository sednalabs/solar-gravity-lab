#!/usr/bin/env bash
set -euo pipefail

output_path="${1:?usage: capture_sccache_stats.sh <output-path>}"
mkdir -p "$(dirname "${output_path}")"

sccache_path="${SCCACHE_PATH:-$(command -v sccache || true)}"
if [[ -z "${sccache_path}" ]]; then
  printf 'sccache binary unavailable\n' > "${output_path}"
  exit 0
fi

if ! "${sccache_path}" --show-stats > "${output_path}" 2>&1; then
  "${sccache_path}" --show-stats > "${output_path}" 2>&1 || true
fi

cat "${output_path}"
