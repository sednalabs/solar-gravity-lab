#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./agent/prepare-upload-bundle.sh [options]

Creates a clean upload bundle from a real Git checkout so a remote coding
session has authoritative commit metadata.

Options:
  --head <ref-or-sha>     Commit/ref to package. Default: HEAD
  --base <ref-or-sha>     Optional base ref recorded into metadata
  --task-file <path>      Optional task markdown copied into agent/input/TASK.md
  --output <path>         Optional destination zip path
USAGE
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
HEAD_REF="HEAD"
BASE_REF=""
TASK_FILE=""
OUTPUT_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --head)
      HEAD_REF="${2:-}"
      shift 2
      ;;
    --base)
      BASE_REF="${2:-}"
      shift 2
      ;;
    --task-file)
      TASK_FILE="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT_PATH="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
 done

cd "$REPO_ROOT"
if ! git rev-parse --show-toplevel >/dev/null 2>&1; then
  echo "This packager requires a real Git checkout." >&2
  exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet || [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
  cat >&2 <<'EOF_DIRTY'
The working tree is dirty.

This packager is intentionally commit-first so the upload bundle matches an
exact Git state. Commit or stash local changes first.
EOF_DIRTY
  exit 1
fi

HEAD_SHA="$(git rev-parse "${HEAD_REF}^{commit}")"
BASE_SHA=""
if [[ -n "$BASE_REF" ]]; then
  BASE_SHA="$(git rev-parse "${BASE_REF}^{commit}")"
fi
SHORT_HEAD="$(git rev-parse --short "$HEAD_SHA")"
REPO_NAME="$(basename "$REPO_ROOT")"
OUTPUT_PATH="${OUTPUT_PATH:-$REPO_ROOT/out/agent/${REPO_NAME}-agent-input-${SHORT_HEAD}.zip}"
OUTPUT_PATH_ABS="$(python3 - <<'PY' "$OUTPUT_PATH"
import os, sys
print(os.path.abspath(sys.argv[1]))
PY
)"

if [[ -n "$TASK_FILE" && ! -f "$TASK_FILE" ]]; then
  echo "Task file not found: ${TASK_FILE}" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

CLONE_DIR="${TMP_DIR}/${REPO_NAME}"
git clone --no-hardlinks "$REPO_ROOT" "$CLONE_DIR" >/dev/null 2>&1

git -C "$CLONE_DIR" checkout --detach "$HEAD_SHA" >/dev/null 2>&1
git -C "$CLONE_DIR" remote remove origin >/dev/null 2>&1 || true
mkdir -p "$CLONE_DIR/agent/input"

python3 - <<'PY' "$CLONE_DIR/agent/input/upload-metadata.json" "$HEAD_SHA" "$BASE_SHA" "$REPO_NAME"
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
head_sha = sys.argv[2]
base_sha = sys.argv[3]
repo_name = sys.argv[4]

payload = {
    "schema_version": 1,
    "repo_name": repo_name,
    "head_sha": head_sha,
    "base_sha": base_sha or None,
    "prepared_by": "agent/prepare-upload-bundle.sh"
}
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

if [[ -n "$TASK_FILE" ]]; then
  cp -a -- "$TASK_FILE" "$CLONE_DIR/agent/input/TASK.md"
fi

mkdir -p "$(dirname "$OUTPUT_PATH_ABS")"
(
  cd "$TMP_DIR"
  zip -qry "$OUTPUT_PATH_ABS" "$REPO_NAME"
)

echo "upload_bundle=${OUTPUT_PATH_ABS}"
