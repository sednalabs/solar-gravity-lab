# Solar Gravity Lab Self-Hosted Android Runner

This directory contains the first-pass scaffolding for a prewarmed self-hosted Android runner pilot.

The goal is to move only the heaviest Android jobs onto a dedicated runner while preserving the same workflow semantics and rollback path already wired into:

- `.github/workflows/validation-lab.yml`
- `.github/workflows/prerelease-apk.yml`

## Scope

This runner pilot is intentionally narrow:

- repository-level runner for `sednalabs/solar-gravity-lab`
- Linux x64
- dedicated custom label: `sgl-android`
- first target jobs:
  - `validation-lab` `android-shell`
  - `prerelease-apk` `build-prerelease-apk`

## Files

- `env/runner.env.example`
  - non-secret host configuration
- `scripts/install_actions_runner.sh`
  - download and extract a pinned GitHub Actions runner release
- `scripts/configure_actions_runner.sh`
  - configure the runner against the repository using a time-limited registration token
- `scripts/prewarm_android_toolchain.sh`
  - install the Android, Rust, and emulator toolchain surface that the heavy jobs need
- `deploy/systemd/solarlab-actions-runner.service`
  - custom `systemd` service template that uses `runsvc.sh`

## Expected host layout

Recommended runner root:

- `$HOME/actions-runner/solar-gravity-lab-android`

The scripts assume the runner installation lives there unless overridden by env.

## Usage outline

1. Create a local env file from `env/runner.env.example`.
2. Set the desired runner version and runner metadata there.
3. Run `scripts/install_actions_runner.sh`.
4. Run `scripts/prewarm_android_toolchain.sh`.
5. Obtain a repository self-hosted runner registration token from GitHub.
6. Export that token as `GITHUB_RUNNER_REG_TOKEN` for one shell only.
7. Run `scripts/configure_actions_runner.sh`.
8. Install and start the `systemd` service from `deploy/systemd/solarlab-actions-runner.service`.
9. Set repository variable `SGL_ANDROID_HEAVY_RUNS_ON_JSON` to:
   - `["self-hosted","linux","x64","sgl-android"]`

## Important safety rules

- Do not persist `GITHUB_RUNNER_REG_TOKEN` in a long-lived env file. GitHub registration tokens are time-limited.
- For this public repository, keep `pull_request` workflows on GitHub-hosted runners. The workflows already enforce that.
- Keep the pilot single-purpose at first. Avoid moving non-Android lanes onto the runner until the Android pilot has clear timing wins and operational stability.

## Related docs

- `docs/self-hosted-android-runner-evaluation.md`
- `docs/self-hosted-android-runner-operator-checklist.md`
- `docs/ci-cache-rollout.md`
