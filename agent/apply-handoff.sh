#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./agent/apply-handoff.sh <handoff.zip> [--check] [--keep-extracted]

Applies the patch from a handoff zip into the current Git checkout.
If the patch does not apply, the extracted handoff directory is left on disk so
its overlay can be inspected manually.
USAGE
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
CHECK_ONLY="false"
KEEP_EXTRACTED="false"
ZIP_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)
      CHECK_ONLY="true"
      shift
      ;;
    --keep-extracted)
      KEEP_EXTRACTED="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      if [[ -z "$ZIP_PATH" ]]; then
        ZIP_PATH="$1"
        shift
      else
        echo "Unexpected argument: $1" >&2
        usage >&2
        exit 1
      fi
      ;;
  esac
 done

if [[ -z "$ZIP_PATH" ]]; then
  usage >&2
  exit 1
fi

cd "$REPO_ROOT"
if ! git rev-parse --show-toplevel >/dev/null 2>&1; then
  echo "This apply script requires a real Git checkout." >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
  if [[ "$KEEP_EXTRACTED" == "true" ]]; then
    echo "Extracted handoff kept at ${TMP_DIR}"
  else
    rm -rf "$TMP_DIR"
  fi
}
trap cleanup EXIT

unzip -q "$ZIP_PATH" -d "$TMP_DIR"
PATCH_PATH="$(find "$TMP_DIR" -name changes.patch -print -quit)"
if [[ -z "$PATCH_PATH" ]]; then
  echo "No changes.patch found in ${ZIP_PATH}" >&2
  exit 1
fi

if [[ "$CHECK_ONLY" == "true" ]]; then
  git apply --check --3way "$PATCH_PATH"
  echo "Patch check passed."
  exit 0
fi

if git apply --3way "$PATCH_PATH"; then
  echo "Patch applied successfully."
  exit 0
fi

echo "Patch apply failed. Extracted handoff left at ${TMP_DIR} for manual inspection." >&2
KEEP_EXTRACTED="true"
exit 1
