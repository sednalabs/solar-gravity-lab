#!/usr/bin/env bash
set -euo pipefail

shared_key="${RUST_CACHE_SHARED_KEY:?RUST_CACHE_SHARED_KEY is required}"
runner_os="${RUNNER_OS:-$(uname -s)}"
runner_arch="${RUNNER_ARCH:-$(uname -m)}"

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

toolchain_hash="$(
  {
    rustc -Vv 2>/dev/null || rustc -V 2>/dev/null || true
    cargo -Vv 2>/dev/null || cargo -V 2>/dev/null || true
    printf 'CARGO_HOME=%s\n' "${CARGO_HOME:-$HOME/.cargo}"
    printf 'CARGO_INCREMENTAL=%s\n' "${CARGO_INCREMENTAL:-}"
    printf 'CARGO_TERM_COLOR=%s\n' "${CARGO_TERM_COLOR:-}"
    printf 'RUSTC_WRAPPER=%s\n' "${RUSTC_WRAPPER:-}"
    printf 'SCCACHE_BUCKET=%s\n' "${SCCACHE_BUCKET:-}"
    printf 'SCCACHE_ENDPOINT=%s\n' "${SCCACHE_ENDPOINT:-}"
    printf 'SCCACHE_S3_KEY_PREFIX=%s\n' "${SCCACHE_S3_KEY_PREFIX:-}"
  } | sha256sum | awk '{print substr($1, 1, 8)}'
)"

workspace_hash="$(
  python3 - <<'PY'
import hashlib
import subprocess

tracked = subprocess.check_output(["git", "ls-files", "-z"])
files = []
for raw in tracked.split(b"\0"):
    if not raw:
        continue
    rel = raw.decode("utf-8")
    if rel.rsplit("/", 1)[-1] in {"Cargo.toml", "Cargo.lock", "rust-toolchain", "rust-toolchain.toml"}:
        files.append(rel)
        continue
    if rel in {".cargo/config", ".cargo/config.toml"}:
        files.append(rel)

digest = hashlib.sha256()
for rel in sorted(files):
    digest.update(rel.encode("utf-8"))
    digest.update(b"\0")
    with open(rel, "rb") as fh:
        digest.update(fh.read())
    digest.update(b"\0")

print(digest.hexdigest()[:8])
PY
)"

prefix="v0-rust-${shared_key}-${runner_os}-${normalized_arch}"
primary_key="${prefix}-${toolchain_hash}-${workspace_hash}"
restore_key_1="${prefix}-${toolchain_hash}-"
restore_key_2="${prefix}-"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "primary-key=${primary_key}"
    echo "restore-key-1=${restore_key_1}"
    echo "restore-key-2=${restore_key_2}"
  } >> "${GITHUB_OUTPUT}"
else
  cat <<EOF
primary-key=${primary_key}
restore-key-1=${restore_key_1}
restore-key-2=${restore_key_2}
EOF
fi
