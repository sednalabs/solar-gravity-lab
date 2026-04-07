# Validation Lab

This repository uses a dispatch-only remote validation workflow so heavy proof
work can run on GitHub-hosted runners instead of consuming Orchard host compute.

On canonical `main`, `validation-lab` validates the Rust-owned platform line and
the forward Android shell under `clients/android`. It is no longer the old
Kotlin root-app validation surface.

Documentation-only updates should normally rely on `docs-sanity` instead of
manually dispatching `validation-lab` unless the docs change is coupled to real
build or workflow behavior.

## Workflow files

- `.github/workflows/validation-lab.yml`
- `.github/workflows/docs-sanity.yml` for documentation-only link sanity
- `.github/workflows/prerelease-apk.yml` for installable internal dev previews
- `.github/workflows/repo-drift-audit.yml` for canonical release-baseline drift evidence

## Drift audit lane

`repo-drift-audit` compares `origin/main` (local canonical head) to the latest reachable `v*` tag by default. It is the lane for authoritative "how far are we from latest release baseline?" evidence and should be used before broad milestone release gates.

- For normal diffing, call the lane with default inputs.
- For production gate, run with `strict=true`; drift then hard-fails.
- For fast triage, set `code_only=true` and optionally `ignore_paths`.
- The action writes:
  - `drift_audit.json` for machine checks and changelog automation.
  - `drift_audit.md` for human-readable diff review.
- The default fallback path remains `origin/main` for repositories that do not expose upstream release tags yet.

## Current lane families

1. `wrapper-bootstrap`
   Generates the root Gradle wrapper files remotely when wrapper bootstrap is the
   real question.
2. `rust-workspace`
   Runs `cargo test --workspace` for the canonical Rust crates.
3. `rust-workspace-arm64`
   Runs `cargo test --workspace` on an Arm64 runner to prove ISA-sensitive
   behavior on target architecture class.
4. `ffi-abi`
   Runs a narrower `cargo test -p solarlab-ffi` proof slice when the active seam
   is runtime/ABI/JNI-facing rather than the whole workspace.
5. `android-shell`
   Installs the Android toolchain plus Rust Android targets, then builds the real
   app under `clients/android` with `:app:assembleDebug`.
   It supports fast-path controls:
   - `android_test_scope=core`: startup + shell layout smoke classes (stable and fast)
   - `android_test_scope=full`: adds rotation continuity + playback continuity
   - `android_artifact_mode=failures-only|always`: controls heavy artifact capture
   - `emulator_boot_strategy=cold|snapshot-cache`: reliable cold boot default with opt-in AVD snapshot cache

## Profiles

- `targeted`
  Default fail-small slice. In `auto` mode this runs only `rust-workspace`.
- `frontier`
  Bounded wider pass. In `auto` mode this keeps `rust-workspace` and adds
  `android-shell` so the next blocker family appears without widening further.
- `broad` / `full`
  Explicit checkpoint modes. These widen into the full currently-defined
  canonical lane set and should be used for milestone proof, not routine turns.

## Lane sets

- `auto`
  Choose lanes from the selected profile.
- `bootstrap`
  Run only wrapper generation.
- `rust-workspace`
  Run only the canonical Rust workspace tests.
- `rust-workspace-arm64`
  Run only the canonical Rust workspace tests on Arm64.
- `ffi-abi`
  Run only the focused FFI ABI test slice.
- `android-shell`
  Run only the Android shell build path under `clients/android`.
- `full`
  Run every currently-defined canonical lane.

## Recommended rollout

1. Use `profile=targeted`, `lane_set=rust-workspace` for fast normal runtime changes.
2. Add `profile=targeted`, `lane_set=rust-workspace-arm64` when the seam touches
   architecture-sensitive physics, SIMD/ISA behavior, or release gating for Arm64 devices.
3. Use `profile=targeted`, `lane_set=ffi-abi` when the active seam is the C ABI,
   JNI, or Android bridge contract.
4. Use `profile=frontier`, `lane_set=auto` when you want the Android shell lane
   alongside the Rust baseline.
5. Reserve `profile=broad` or `profile=full` for milestone checkpoints; these now
   include Arm64 Rust workspace proof in `auto` mode.
6. Use `prerelease-apk` when the real question is packaging an installable device
   build rather than simply proving the branch compiles.

## Cheap path for docs-only changes

If a change only touches `README.md`, `docs/**`, or the docs-sanity workflow
itself, let `.github/workflows/docs-sanity.yml` answer the first question:

- do the repository markdown links still resolve?
- did we avoid accidentally widening documentation edits into code validation?

That keeps documentation maintenance fast while preserving `validation-lab` as
the deliberate remote compute path for canonical code and build changes.
