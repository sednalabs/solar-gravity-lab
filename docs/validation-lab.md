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
- `.github/workflows/interactive-android-build.yml` for reusable hosted Android
  build artifacts
- `.github/workflows/interactive-android-session.yml` for bounded live emulator debugging on a hosted runner
- `.github/workflows/codeql.yml` for checked-in advanced CodeQL analysis across
  Actions, C/C++, Java/Kotlin, Python, and Rust

## Current lane families

1. `wrapper-bootstrap`
   Generates the root Gradle wrapper files remotely when wrapper bootstrap is the
   real question.
2. `rust-workspace`
   Runs `cargo test --workspace` for the canonical Rust crates.
3. `rust-workspace-arm64`
   Runs `cargo test --workspace` on an Arm64 runner to prove ISA-sensitive
   behavior on target architecture class.
4. `arm64-capability-census`
   Runs `.github/scripts/collect_android_capability_census.py` on an Arm64
   runner. Use this when the question is capability inventory, schema shape, or
   hosted Arm64 normalization rather than solver execution.
5. `arm64-isa-proof`
   Runs `.github/scripts/run_arm64_isa_proof.sh` on an Arm64 runner. Use this
   focused lane when the question is CPU feature normalization, solver-path
   activation truth, or scalar-oracle equivalence for Arm64.
6. `ffi-abi`
   Runs a narrower `cargo test -p solarlab-ffi` proof slice when the active seam
   is runtime/ABI/JNI-facing rather than the whole workspace.
7. `android-shell`
   Prepares the Android toolchain plus the Rust Android targets, then builds the
   real app under `clients/android` with `:app:assembleDebug`.
   It supports fast-path controls:
   - `android_test_scope=core`: startup + shell layout smoke classes (stable and fast)
   - `android_test_scope=full`: adds rotation continuity + playback continuity
   - `android_artifact_mode=failures-only|always`: controls heavy artifact capture
   - `emulator_boot_strategy=cold|snapshot-cache`: reliable cold boot default with opt-in AVD snapshot cache

For stage-first runtime work, prefer
`android_validation_mode=stage-first-mirror-on`. That mode builds the app with
`solarlab.preferredGpuBackend=vulkan` and the core scope enters the runtime
mirror, binds the native runtime session, and checks backend truth instead of
only proving the local sandbox. See
[`Android Acceleration Truth`](android-acceleration-truth.md).

## Android cache observability

The Android validation lanes and `prerelease-apk` now surface the main cache
signals directly in the public step summaries instead of forcing deep log
scrapes:

- whether the remote Gradle cache was configured and whether the lane was in
  `read-only` or `write-enabled` mode
- how many Gradle tasks reported `FROM-CACHE` for the captured lane logs
- whether Gradle configuration cache was disabled or enabled for the run
- whether each captured Android Gradle invocation stored or reused a
  configuration-cache entry
- whether configuration-cache persistence was `job-local-only` or eligible for
  encrypted GitHub cache persistence
- whether the Android Rust target cache hit exactly
- whether the required Rust Android targets were restored, already available, or
  had to be installed
- whether `cargo-ndk` was reused from the shared Rust cache surface or freshly
  installed

This is intentionally measurement-first. The current policy is to keep the
existing remote Gradle cache write/read rules and use these new signals before
widening cache policy further.

## Gradle configuration cache

`validation-lab` now exposes an opt-in `workflow_dispatch` input:

- `gradle_configuration_cache=disabled|enabled`

This defaults to `disabled` so routine validation keeps the stable baseline
unless the run is intentionally measuring configuration-cache compatibility.

When enabled:

- Android unit, lint, and shell lanes pass `--configuration-cache` to Gradle
- public summaries report whether each Gradle invocation stored or reused a
  configuration-cache entry
- summaries also report whether persistence is `job-local-only` or eligible for
  encrypted GitHub cache persistence
- the configuration-cache mode is part of the Android Gradle job matrix used by
  `gradle/actions/setup-gradle`, keeping enabled runs from exact-hitting older
  disabled-mode Gradle User Home cache entries for the same commit

Current recommendation:

- leave the default disabled for routine runs until measurement specifically
  calls for it
- if you want cross-run configuration-cache reuse rather than compatibility
  proof only, configure the repository secret
  `GRADLE_CONFIGURATION_CACHE_KEY` so `gradle/actions/setup-gradle` can persist
  encrypted configuration-cache state between runs

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
- `arm64-capability-census`
  Run only the hosted Arm64 capability census collector and artifact upload.
- `arm64-isa-proof`
  Run only the focused Arm64 CPU ISA proof script on an Arm64 hosted runner.
- `runtime-cpu-truth`
  Run the focused CPU truth bundle: Arm64 ISA proof, FFI ABI tests, and Android
  unit tests. Use this when runtime info or JNI telemetry changes cross the
  physics/FFI/Android boundary.
- `ffi-abi`
  Run only the focused FFI ABI test slice.
- `android-shell`
  Run only the Android shell build path under `clients/android`.
- `full`
  Run every currently-defined canonical lane.

## Recommended rollout

1. Use `profile=targeted`, `lane_set=rust-workspace` for fast normal runtime changes.
2. Add `profile=targeted`, `lane_set=rust-workspace-arm64` when the seam touches
   broad architecture-sensitive physics or release gating for Arm64 devices.
3. Prefer `profile=targeted`, `lane_set=arm64-isa-proof` when the active seam is
   specifically CPU feature reporting, Arm64 solver dispatch, or backend
   activation truth. This is the lower-carbon, fail-small path before widening.
4. Use `profile=targeted`, `lane_set=arm64-capability-census` when the active
   seam is capability inventory or census schema only. This is cheaper and more
   targeted than running solver parity tests.
5. Use `profile=targeted`, `lane_set=runtime-cpu-truth` when the active seam
   crosses physics dispatch, FFI runtime info, and Android telemetry. This avoids
   canceling separate same-branch workflow dispatches while staying narrower
   than `full`.
6. Use `profile=targeted`, `lane_set=ffi-abi` when the active seam is the C ABI,
   JNI, or Android bridge contract.
7. Use `profile=frontier`, `lane_set=auto` when you want the Android shell lane
   alongside the Rust baseline.
8. Reserve `profile=broad` or `profile=full` for milestone checkpoints; these
   now include Arm64 Rust workspace proof, capability census, and the focused
   Arm64 ISA proof in `auto` mode.
9. Use `prerelease-apk` when the real question is packaging an installable device
   build rather than simply proving the branch compiles.

## Hosted interactive Android development

`validation-lab` is the canonical proof lane, but it is not the only hosted
development surface in the current implementation.

The repository also supports a build-first interactive path:

1. build a reusable Android artifact with
   `.github/workflows/interactive-android-build.yml`
2. start a bounded live emulator session with
   `.github/workflows/interactive-android-session.yml`
3. use that live session for targeted UI and interaction investigation

This is useful for human debugging, and it is also useful for AI-assisted
development because it lets interactive investigation happen against a real
hosted Android environment without paying full rebuild-and-boot cost for every
inspection turn.

Use that interactive path, or the native Android computer-use connector, for
visual acceptance. `validation-lab` proves that code builds, tests pass, Android
shell flows execute, and instrumentation contracts hold. It does not prove that
an immersive or stage-first screen has the right composition on a device-sized
viewport.

For Android UI changes, especially stage-first, runtime mirror, scenario-pack,
or visual-polish work, include a visual acceptance note alongside the hosted CI
run. The note should identify the observed surface and confirm the relevant
collapsed/expanded or controls-open states. If live Android observation is not
available, record that visual proof is blocked rather than treating the
validation run as a substitute.

When the selected Android provider ref supports the native Codex Android
provider contract, the interactive session also publishes Codex bridge status
and a provider manifest under `dist/interactive-session/codex-bridge/`. Those
artifacts prove provider availability for the hosted session, while
`validation-lab` remains the canonical compile, test, and build proof lane. For
the Solar-side boundary, see
[`Android Codex Computer-Use Harness`](android-codex-computer-use.md).

## Cheap path for docs-only changes

If a change only touches `README.md`, `docs/**`, or the docs-sanity workflow
itself, let `.github/workflows/docs-sanity.yml` answer the first question:

- do the repository markdown links still resolve?
- did we avoid accidentally widening documentation edits into code validation?

That keeps documentation maintenance fast while preserving `validation-lab` as
the deliberate remote compute path for canonical code and build changes.
