#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: install_cloudflared.sh <destination-dir> [version]

Download a pinned cloudflared binary into the destination directory.
The installed binary path will be:

  <destination-dir>/cloudflared
EOF
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage >&2
  exit 1
fi

destination_dir="$1"
version="${2:-2026.4.1}"

machine="$(uname -m)"
asset_arch=""
case "$machine" in
  x86_64|amd64)
    asset_arch="amd64"
    ;;
  aarch64|arm64)
    asset_arch="arm64"
    ;;
  *)
    echo "Unsupported architecture for cloudflared install: ${machine}" >&2
    exit 2
    ;;
esac

mkdir -p "${destination_dir}"
tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

asset_url="https://github.com/cloudflare/cloudflared/releases/download/${version}/cloudflared-linux-${asset_arch}"
target_path="${destination_dir}/cloudflared"

curl -fsSL "${asset_url}" -o "${tmpdir}/cloudflared"
chmod 0755 "${tmpdir}/cloudflared"
mv "${tmpdir}/cloudflared" "${target_path}"

echo "Installed cloudflared ${version} to ${target_path}"
