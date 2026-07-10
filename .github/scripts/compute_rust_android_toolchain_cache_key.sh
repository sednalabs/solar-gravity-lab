#!/usr/bin/env bash
set -euo pipefail

cache_version="${RUST_ANDROID_TOOLCHAIN_CACHE_VERSION:?RUST_ANDROID_TOOLCHAIN_CACHE_VERSION is required}"
targets_raw="${RUST_ANDROID_TARGETS:?RUST_ANDROID_TARGETS is required}"
runner_os="${RUNNER_OS:-$(uname -s)}"
runner_arch="${RUNNER_ARCH:-$(uname -m)}"
rustup_home="${RUSTUP_HOME:-$HOME/.rustup}"

case "${runner_arch}" in
  X64|x86_64|amd64)
    normalized_arch="x64"
    ;;
  ARM64|aarch64|arm64)
    normalized_arch="arm64"
    ;;
  *)
    normalized_arch="$(printf '%s' "${runner_arch}" | tr '[:upper:]' '[:lower:]')"
    ;;
esac

mapfile -t targets < <(
  python3 - <<'PY'
import os
raw = os.environ["RUST_ANDROID_TARGETS"]
targets = sorted(list(set(token.strip() for token in raw.replace(",", " ").split() if token.strip())))
for t in targets: print(t)
PY
)

if [[ "${#targets[@]}" -eq 0 ]]; then
  echo "No Android Rust targets were provided." >&2
  exit 1
fi

active_toolchain="$(rustup show active-toolchain | awk 'NR==1 {print $1}')"
if [[ -z "${active_toolchain}" ]]; then
  echo "Unable to resolve the active Rust toolchain." >&2
  exit 1
fi

targets_csv="$(IFS=,; echo "${targets[*]:-}")"

hash_cmd=()
if command -v sha256sum >/dev/null 2>&1; then
  hash_cmd=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  hash_cmd=(shasum -a 256)
else
  echo "Unable to locate sha256sum or shasum for Android toolchain cache hashing." >&2
  exit 1
fi

compiler_identity="$(rustc -Vv 2>/dev/null || rustc -V 2>/dev/null || true)"
if [[ -z "${compiler_identity}" ]]; then
  echo "Unable to resolve the effective Rust compiler identity." >&2
  exit 1
fi
compiler_identity_hash="$(
  printf '%s\n' "${compiler_identity}" | "${hash_cmd[@]}" | awk '{print substr($1, 1, 8)}'
)"

toolchain_hash="$(
  {
    rustup show active-toolchain 2>/dev/null || true
    rustc -Vv 2>/dev/null || rustc -V 2>/dev/null || true
    cargo -Vv 2>/dev/null || cargo -V 2>/dev/null || true
    printf 'RUSTUP_HOME=%s\n' "${rustup_home}"
    printf 'RUST_ANDROID_TARGETS=%s\n' "${targets_csv}"
    printf 'RUST_ANDROID_TOOLCHAIN_CACHE_VERSION=%s\n' "${cache_version}"
  } | "${hash_cmd[@]}" | awk '{print substr($1, 1, 8)}'
)"

prefix="v0-rust-android-${cache_version}-${runner_os}-${normalized_arch}-${active_toolchain}-${compiler_identity_hash}"
primary_key="${prefix}-${toolchain_hash}"
restore_key_1="${prefix}-"

cache_paths=(
  "${rustup_home}/toolchains/${active_toolchain}/lib/rustlib/components"
  "${rustup_home}/toolchains/${active_toolchain}/lib/rustlib/multirust-channel-manifest.toml"
  "${rustup_home}/toolchains/${active_toolchain}/lib/rustlib/multirust-config.toml"
  "${rustup_home}/toolchains/${active_toolchain}/lib/rustlib/rust-installer-version"
)

for target in "${targets[@]}"; do
  cache_paths+=(
    "${rustup_home}/toolchains/${active_toolchain}/lib/rustlib/${target}"
    "${rustup_home}/toolchains/${active_toolchain}/lib/rustlib/manifest-rust-std-${target}"
  )
done

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "primary-key=${primary_key}"
    echo "restore-key-1=${restore_key_1}"
    echo "toolchain-name=${active_toolchain}"
    echo "target-list=${targets_csv}"
    echo "cache-paths<<EOF"
    printf '%s\n' "${cache_paths[@]}"
    echo "EOF"
  } >> "${GITHUB_OUTPUT}"
else
  {
    echo "primary-key=${primary_key}"
    echo "restore-key-1=${restore_key_1}"
    echo "toolchain-name=${active_toolchain}"
    echo "target-list=${targets_csv}"
    echo "cache-paths:"
    printf '%s\n' "${cache_paths[@]}"
  }
fi
