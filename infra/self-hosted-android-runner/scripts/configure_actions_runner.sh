#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${GITHUB_RUNNER_ENV_FILE:-${ROOT_DIR}/env/runner.env}"

if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

: "${GITHUB_RUNNER_REPO_URL:?Set GITHUB_RUNNER_REPO_URL in ${ENV_FILE} or env}"
: "${GITHUB_RUNNER_NAME:?Set GITHUB_RUNNER_NAME in ${ENV_FILE} or env}"
: "${GITHUB_RUNNER_LABELS:?Set GITHUB_RUNNER_LABELS in ${ENV_FILE} or env}"
: "${GITHUB_RUNNER_WORKDIR:?Set GITHUB_RUNNER_WORKDIR in ${ENV_FILE} or env}"
: "${GITHUB_RUNNER_ROOT:?Set GITHUB_RUNNER_ROOT in ${ENV_FILE} or env}"
: "${GITHUB_RUNNER_REG_TOKEN:?Export a fresh repository registration token into GITHUB_RUNNER_REG_TOKEN before running this script}"

RUNNER_DIR="${GITHUB_RUNNER_ROOT/#\%h/${HOME}}"
RUNNER_EPHEMERAL="${GITHUB_RUNNER_EPHEMERAL:-false}"

if [[ ! -x "${RUNNER_DIR}/config.sh" ]]; then
  echo "Runner is not installed in ${RUNNER_DIR}. Run install_actions_runner.sh first." >&2
  exit 1
fi

cd "${RUNNER_DIR}"

args=(
  --url "${GITHUB_RUNNER_REPO_URL}"
  --token "${GITHUB_RUNNER_REG_TOKEN}"
  --name "${GITHUB_RUNNER_NAME}"
  --labels "${GITHUB_RUNNER_LABELS}"
  --work "${GITHUB_RUNNER_WORKDIR}"
  --unattended
  --replace
)

if [[ "${RUNNER_EPHEMERAL}" == "true" ]]; then
  args+=(--ephemeral)
fi

./config.sh "${args[@]}"

echo "Configured runner ${GITHUB_RUNNER_NAME} in ${RUNNER_DIR}"
