# Self-Hosted Android Runner Evaluation

This document is retained as deferred evaluation material.

It does not describe the current recommended path on `main`. The current
public recommendation is to use GitHub-hosted workflows, reusable interactive
Android artifacts, and bounded live hosted sessions first. Read this document
only if a future tranche needs to revisit self-hosted runners after the
GitHub-hosted-first model has been measured honestly.

This document captures the next-step evaluation for Solar Gravity Lab after the hosted-runner cache acceleration slice proved out.

## Why this is the next frontier

The hosted-runner cache work is now real and measured:

- Gradle remote cache is live at `https://cache.sednalabs.io/cache/`
- AVD snapshot cache is warm on `validation-lab` and `prerelease-apk`
- Android SDK emulator + API 35 system-image packages are cached across both workflows
- Rust/native work is warm through the in-repo rust shared cache plus R2-backed `sccache`

The remaining Android wall time is now dominated more by:

- runner bootstrap
- cache restore bandwidth
- emulator execution

than by repeated package installation or repeated native compilation.

## Current hosted-runner baseline

Representative warm-path measurements from `validation/cache-acceleration-20260412`:

- `validation-lab` targeted Android shell run `24301670464`
  - shell job total: about `4m01s`
  - AVD restore: about `19s`
  - SDK component restore: about `25s`
  - SDK verification: about `8s`
  - assemble debug shell + androidTest: about `53s`
  - connected shell smoke + continuity: about `1m24s`

- `prerelease-apk` warm run `24301554955`
  - build lane total: about `2m58s`
  - AVD restore: about `13s`
  - SDK component restore: about `18s`
  - SDK verification: about `11s`
  - assemble prerelease APK: about `52s`
  - emulator launch smoke: about `45s`

Interpretation:

1. the heavy repeated compile/setup waste is already mostly harvested
2. the remaining warm-path penalty is increasingly the hosted-runner environment itself
3. a prewarmed self-hosted runner is now the cleanest next place to look for another large gain

## Recommended smallest safe adoption path

Phase 1 should stay intentionally small:

- one dedicated Linux x64 self-hosted runner
- one dedicated label set:
  - `self-hosted`
  - `linux`
  - `x64`
  - `sgl-android`
- one repository-scoped use case:
  - `validation-lab` Android shell
  - `prerelease-apk` build / launch-smoke lane

Keep everything else on GitHub-hosted runners at first.

This limits operational blast radius while testing whether the warm-path speedup is worth broader adoption.

## Migration lever already in place

The heavy Android jobs are now runner-selectable through one repository variable:

- `SGL_ANDROID_HEAVY_RUNS_ON_JSON`

Current behavior:

- if unset, the workflows use:
  - `"ubuntu-24.04"`
- if the workflow is running on `pull_request`, the heavy Android jobs stay on `"ubuntu-24.04"` even if the self-hosted variable is set
- only non-PR runs are allowed to switch to a self-hosted label set through the repo variable

Self-hosted pilot value:

- `["self-hosted","linux","x64","sgl-android"]`

This means the pilot migration and rollback are both one repo-variable edit, not another workflow refactor.

## Public-repo safety rule

Solar Gravity Lab is a public repository, so the self-hosted pilot should not run untrusted pull-request code from forks on the self-hosted runner.

The workflow seam is already hardened for that:

- `pull_request` keeps the heavy Android jobs on GitHub-hosted runners
- only non-PR runs can switch to a self-hosted runner through `SGL_ANDROID_HEAVY_RUNS_ON_JSON`

Operationally, that means the pilot should start with:

- `workflow_dispatch`
- validation branches
- trusted maintainer-triggered runs

## What the self-hosted runner should preload

The point of the runner is to remove repeated bootstrap tax, so the host should already have:

- Java 17
- Android SDK root with:
  - `platform-tools`
  - `platforms;android-36`
  - `build-tools;36.0.0`
  - `emulator`
  - `system-images;android-35;google_apis;x86_64`
  - `cmake;3.31.5`
  - `ndk;27.3.13750724`
- KVM available and working
- Rust stable
- Rust Android targets:
  - `aarch64-linux-android`
  - `x86_64-linux-android`
- `cargo-ndk`
- `sccache`
- a precreated AVD matching:
  - `pixel_7_api35_google_apis_x86_64`

The remote Gradle cache and R2-backed `sccache` should stay in place even on self-hosted runners. The goal is to combine:

1. prewarmed local toolchain state
2. shared remote cache reuse
3. identical workflow semantics

## Operational expectations

The runner should be treated as:

- single-tenant for this repository during the pilot
- single-concurrency for the Android-heavy jobs at first
- aggressively cleaned between jobs at the workspace level

Recommended hygiene:

- dedicated runner service account
- dedicated workspace root
- wipe workspace contents between jobs
- keep caches outside the ephemeral workspace
- separate long-lived toolchain/cache directories from checkout directories

## Success criteria

The pilot is worthwhile if it can materially beat the current warm hosted baseline without making operations brittle.

Suggested targets:

- `validation-lab` Android shell warm path:
  - from about `4m01s`
  - to at most `3m00s`

- `prerelease-apk` build lane warm path:
  - from about `2m58s`
  - to at most `2m15s`

- preserve the same validation outputs:
  - artifacts
  - screenshots
  - logcat / dumpsys capture
  - validation summary payloads

- preserve the same cache behavior:
  - Gradle remote cache still live
  - `sccache` still emits usable stats
  - AVD reuse still measurable

## First jobs to move

Move only these first:

1. `validation-lab` `android-shell`
2. `prerelease-apk` `build-prerelease-apk`

Leave these on GitHub-hosted runners initially:

- `validation-lab` Rust workspace lanes
- `validation-lab` ARM64 ISA proof
- `validation-lab` FFI ABI
- Android unit and lint

Reason:

- the Android shell and prerelease build lanes carry the heaviest warm-path bootstrap cost
- the other lanes are already getting good value from the current hosted-runner caches

## Rollback

Rollback should be immediate and boring:

1. unset `SGL_ANDROID_HEAVY_RUNS_ON_JSON`
2. workflows fall back to `"ubuntu-24.04"`
3. keep the self-hosted runner idle while investigating

No workflow revert should be required for that first rollback step.

## Deferred recommendation

If a future tranche decides the GitHub-hosted-first model is no longer
sufficient, this document describes the smallest self-hosted pilot worth
trying.

Until then, prefer the current hosted path:

- GitHub-hosted canonical proof
- reusable interactive Android build artifacts
- bounded live hosted emulator sessions
- cache improvements on the standard hosted runner surface

For the concrete operator sequence, see:

- `docs/self-hosted-android-runner-operator-checklist.md`
