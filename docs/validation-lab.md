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

The `prerelease-apk` workflow now includes a second gate for prerelease builds:
- build and launch smoke on `prerelease` APK
- ARM64 ISA proof lane (`ubuntu-24.04-arm`) that runs
  `.github/scripts/run_arm64_isa_proof.sh` before publish

## Current lane families

1. `wrapper-bootstrap`
   Generates the root Gradle wrapper files remotely when wrapper bootstrap is the
   real question.
2. `rust-workspace`
   Runs `cargo test --workspace` for the canonical Rust crates.
3. `arm64-isa-proof`
   Runs the ARM64 capability and scalar-oracle equivalence gate on an ARM64
   runner.
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
- `arm64-isa-proof`
  Run only the ARM64 ISA capability + scalar-oracle gate.
- `ffi-abi`
  Run only the focused FFI ABI test slice.
- `android-shell`
  Run only the Android shell build path under `clients/android`.
- `full`
  Run every currently-defined canonical lane.

## Recommended rollout

1. Use `profile=targeted`, `lane_set=rust-workspace` for normal runtime changes.
2. Use `profile=targeted`, `lane_set=ffi-abi` when the active seam is the C ABI,
   JNI, or Android bridge contract.
3. Use `profile=frontier`, `lane_set=auto` when you want the Android shell lane
   alongside the Rust baseline.
4. Reserve `profile=broad` or `profile=full` for milestone checkpoints.
5. Use `prerelease-apk` when the real question is packaging an installable device
   build rather than simply proving the branch compiles.
6. For prerelease publication, confirm the new ARM64 ISA proof lane is green before
   treating the build as release-ready.

## Cheap path for docs-only changes

If a change only touches `README.md`, `docs/**`, or the docs-sanity workflow
itself, let `.github/workflows/docs-sanity.yml` answer the first question:

- do the repository markdown links still resolve?
- did we avoid accidentally widening documentation edits into code validation?

That keeps documentation maintenance fast while preserving `validation-lab` as
the deliberate remote compute path for canonical code and build changes.
