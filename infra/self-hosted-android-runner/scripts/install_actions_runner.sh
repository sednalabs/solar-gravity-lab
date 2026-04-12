#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${GITHUB_RUNNER_ENV_FILE:-${ROOT_DIR}/env/runner.env}"

if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

: "${GITHUB_RUNNER_VERSION:?Set GITHUB_RUNNER_VERSION in ${ENV_FILE} or env}"
: "${GITHUB_RUNNER_ARCH:?Set GITHUB_RUNNER_ARCH in ${ENV_FILE} or env}"
: "${GITHUB_RUNNER_ROOT:?Set GITHUB_RUNNER_ROOT in ${ENV_FILE} or env}"

RUNNER_OS="linux"
RUNNER_DIR="${GITHUB_RUNNER_ROOT/#\%h/${HOME}}"
RUNNER_TARBALL="actions-runner-${RUNNER_OS}-${GITHUB_RUNNER_ARCH}-${GITHUB_RUNNER_VERSION}.tar.gz"
RUNNER_URL="https://github.com/actions/runner/releases/download/v${GITHUB_RUNNER_VERSION}/${RUNNER_TARBALL}"

mkdir -p "${RUNNER_DIR}"
cd "${RUNNER_DIR}"

if [[ -x "./run.sh" && -x "./config.sh" ]]; then
  echo "GitHub Actions runner already installed in ${RUNNER_DIR}"
  exit 0
fi

tmp_tarball="$(mktemp "${TMPDIR:-/tmp}/actions-runner.XXXXXX.tar.gz")"
trap 'rm -f "${tmp_tarball}"' EXIT

echo "Downloading ${RUNNER_URL}"
curl -fsSL "${RUNNER_URL}" -o "${tmp_tarball}"
tar xzf "${tmp_tarball}" -C "${RUNNER_DIR}"

echo "Installed runner ${GITHUB_RUNNER_VERSION} into ${RUNNER_DIR}"
