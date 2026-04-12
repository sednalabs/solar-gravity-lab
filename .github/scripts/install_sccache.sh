#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: install_sccache.sh <version> <install-dir>}"
install_dir="${2:?usage: install_sccache.sh <version> <install-dir>}"

platform="$(uname -s)"
architecture="$(uname -m)"

case "${platform}" in
  Linux)
    case "${architecture}" in
      x86_64)
        target="x86_64-unknown-linux-musl"
        ;;
      aarch64|arm64)
        target="aarch64-unknown-linux-musl"
        ;;
      *)
        echo "Unsupported Linux architecture for sccache: ${architecture}" >&2
        exit 1
        ;;
    esac
    ;;
  *)
    echo "Unsupported platform for sccache installer: ${platform}" >&2
    exit 1
    ;;
esac

asset_name="sccache-${version}-${target}.tar.gz"
asset_url="https://github.com/mozilla/sccache/releases/download/${version}/${asset_name}"
checksum_url="${asset_url}.sha256"

temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

archive_path="${temp_dir}/${asset_name}"
checksum_path="${archive_path}.sha256"

curl --fail --location --silent --show-error "${asset_url}" --output "${archive_path}"
curl --fail --location --silent --show-error "${checksum_url}" --output "${checksum_path}"

expected_checksum="$(tr -d '[:space:]' < "${checksum_path}")"
actual_checksum="$(sha256sum "${archive_path}" | awk '{print $1}')"
if [[ "${actual_checksum}" != "${expected_checksum}" ]]; then
  echo "sccache archive checksum mismatch for ${asset_name}" >&2
  echo "expected: ${expected_checksum}" >&2
  echo "actual:   ${actual_checksum}" >&2
  exit 1
fi

mkdir -p "${install_dir}"
tar -xzf "${archive_path}" -C "${temp_dir}"

binary_path="$(find "${temp_dir}" -type f -name sccache | head -n 1)"
if [[ -z "${binary_path}" ]]; then
  echo "Unable to locate extracted sccache binary inside ${asset_name}" >&2
  exit 1
fi

install -m 0755 "${binary_path}" "${install_dir}/sccache"
