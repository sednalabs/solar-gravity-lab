# Solar Gravity Lab

Solar Gravity Lab now runs on the canonical Rust runtime mainline.

The long-term product shape is:

- a Rust-owned authoritative simulation/runtime core
- native client shells over that core
- offline-first scientific data with signed live updates
- a renderer-independent scene contract with backend adapters
- optional hardware fast paths behind open, portable interfaces

The existing Kotlin/Android/Vulkan code remains in this repository only as
legacy reference material while the Rust-native platform continues to replace
those seams.

## Status

What is real on this canonical main line today:

- an ADR-backed architecture record in [`docs/adr`](docs/adr)
- a versioned protobuf schema surface in [`proto/solarlab/v2`](proto/solarlab/v2)
- a Rust workspace in [`engine`](engine) with the canonical long-lived module
  boundaries
- an explicit FFI contract and JNI bridge for native client shells
- an operational Android shell in [`clients/android`](clients/android) that can
  start a Rust runtime session, refresh/apply commands, and host the exported
  Vulkan render packet surface
- a source-mass-aware runtime/FFI command path so probes, tracers, and teaching
  bodies can carry display mass without silently perturbing canonical bodies
- working Android native builds through `cargo-ndk` and the Android app Gradle
  pipeline

What is intentionally still transitional:

- the existing `app`, `core-*`, `feature-lab`, and `render-core` modules are
  still present as v1 reference code
- the runtime and scene export surface are still earlier in product maturity
  than the old line; this branch is structurally ahead of feature parity, but
  now owns the authoritative physics semantics for the canonical path
- the render backend adapter stack is only implemented far enough for the
  current Vulkan packet host seam and still needs broader scene-history and
  capability work
- the offline update services are designed but not implemented yet

## Current renderer migration / architectural gaps

The physics/data spine is already 3D. The main open migration problem is not a
physics rewrite; it is the render-side migration from older flat assumptions to
an honest 3D camera/render/interaction/compute stack.

The highest-value live gaps are:

- camera, projection, and picking are still split across older packet-host and
  newer immersive-render directions
- the authoritative rendering boundary needs to be read explicitly as
  `world -> RenderSceneFrame -> NativeScenePacket -> native streams -> Vulkan`
- medium/far compute-compaction is active only in the restored 3D
  camera-space renderer path; the older XY-native compaction notes are
  historical and must not be treated as current design guidance
- the repo now has a richer architecture corpus describing current rendering,
  camera, scene-contract, scaling, and frame-lifecycle seams under `docs/`

## Repository layout

- [`engine/`](engine)
  Rust workspace for the new core platform.
- [`proto/`](proto)
  Versioned cross-language schemas for packages, runtime, diagnostics, and
  render-scene contracts.
- [`clients/android/`](clients/android)
  The Android Compose shell over the Rust core.
- [`data/`](data)
  Data-pack, manifest, validation, and provenance model documentation.
- [`services/`](services)
  Optional update and content-distribution services.
- [`labs/`](labs)
  Conformance, data, render, hardware, and client validation harnesses.
- [`legacy/`](legacy)
  Documentation for the retained Kotlin/Android/Vulkan reference code still
  present at the repository root.

## Architecture reading order

1. [`docs/adr/0001-rust-owned-core.md`](docs/adr/0001-rust-owned-core.md)
2. [`docs/adr/0002-versioned-protobuf-contracts.md`](docs/adr/0002-versioned-protobuf-contracts.md)
3. [`docs/adr/0003-c-abi-and-opaque-handles.md`](docs/adr/0003-c-abi-and-opaque-handles.md)
4. [`docs/adr/0004-offline-first-data-and-updates.md`](docs/adr/0004-offline-first-data-and-updates.md)
5. [`docs/adr/0005-render-scene-and-backend-adapters.md`](docs/adr/0005-render-scene-and-backend-adapters.md)
6. [`docs/architecture.md`](docs/architecture.md)
7. [`docs/rendering-architecture-current-state.md`](docs/rendering-architecture-current-state.md)
8. [`docs/camera-and-interaction-model.md`](docs/camera-and-interaction-model.md)
9. [`docs/scene-world-model-contract.md`](docs/scene-world-model-contract.md)
10. [`docs/performance-and-scaling-strategy.md`](docs/performance-and-scaling-strategy.md)
11. [`docs/frame-lifecycle.md`](docs/frame-lifecycle.md)
12. [`docs/compute-compaction-reintroduction-plan.md`](docs/compute-compaction-reintroduction-plan.md)
13. [`docs/scenario-packs.md`](docs/scenario-packs.md)
14. [`docs/device-native-acceleration.md`](docs/device-native-acceleration.md)
15. [`docs/v2/architecture.md`](docs/v2/architecture.md)
16. [`docs/v2/roadmap.md`](docs/v2/roadmap.md)

The `docs/v2/*` paths remain named that way because they were written during
the reset, but they now describe the architecture that lives on `main`.

## Runtime/FFI seam contract

The Rust runtime + FFI integration is intentionally layered so each boundary has
one clear owner:

- `solarlab_runtime` owns authoritative simulation state, command application, and deterministic snapshots.
- `solarlab_ffi` owns the C ABI process boundary and is responsible for:
  - marshalling opaque handles and POD structs,
  - enforcing handle validity and status code returns,
  - extracting scene packets from `solarlab_runtime`,
  - and managing release lifetimes for export packets.
- Android/Kotlin callers consume the ABI (direct C layer today) and should treat every returned handle as a short-lived capability that must be explicitly invalidated (destroy/release).

Refresh and command semantics in this canonical Rust line:

- `sl_v2_session_apply_command` is the canonical mutable entrypoint. Every successful command mutates runtime state and returns a snapshot-style summary.
- `sl_v2_session_snapshot_summary` is a read-only observation of the current runtime state.
- `sl_v2_session_refresh` exists as an explicit read refresh point for shell callers that want a consistent control flow between “command + observe” turns.
- `sl_v2_session_export_vulkan_scene` is also read-only; it returns a separate packet handle whose backing buffer must be consumed via `sl_v2_vulkan_scene_packet_buffer` and must be released with `sl_v2_vulkan_scene_packet_release`.
- Spawn-body commands cross the current ABI with both `body_mass_kg` and
  `body_source_mass_kg`. `body_mass_kg` is the display/inertial teaching value;
  `body_source_mass_kg` is the mass that actually participates as a gravity
  source. Tracers, probes, spacecraft, and small-body markers should default to
  zero source mass unless the user explicitly creates them as massive bodies.

Scenario packs provide deterministic startup scenes for fast Android visual
iteration. They choose the starting catalog slice and presentation defaults, but
the Rust runtime remains authoritative after the pack is loaded. See
[`docs/scenario-packs.md`](docs/scenario-packs.md).

## Operational truth on `main`

Canonical `main` should be read operationally as:

- Rust workspace + FFI + `clients/android` are the live product path
- prerelease packaging comes from `clients/android`
- validation should prove the Rust workspace and the Android shell
- the root Kotlin modules are reference material and are not the active app on
  this branch

## Validation

The foundational validation target is the Rust workspace:

```bash
cargo test --workspace
```

The canonical Android proof surface is the forward shell under `clients/android`,
not the old root app:

```bash
./gradlew -p clients/android --no-daemon :app:assembleDebug
```

Use `.github/workflows/validation-lab.yml` for canonical validation slices and
`.github/workflows/prerelease-apk.yml` for installable release candidates.
`validation-lab` includes a hosted Arm64 capability census lane and an
ARM64-native ISA proof lane. `prerelease-apk` also runs the ISA proof gate
(`ubuntu-24.04-arm`) via `.github/scripts/run_arm64_isa_proof.sh` before
publish.

## Hosted Android development

This repository treats GitHub-hosted workflows as the primary heavy-compute
surface for Android proof, packaging, and bounded live investigation.

The current workflow split is:

- `.github/workflows/validation-lab.yml`
  canonical remote proof for the Rust platform and Android shell
- `.github/workflows/prerelease-apk.yml`
  installable internal Android previews from a proved head
- `.github/workflows/interactive-android-build.yml`
  reusable Android artifact bundles for interactive work
- `.github/workflows/interactive-android-session.yml`
  bounded live hosted emulator sessions for inspection and debugging
- `.github/workflows/codeql.yml`
  checked-in advanced CodeQL analysis for Actions, C/C++, Java/Kotlin,
  Python, and Rust security scanning

This model is especially useful for AI-assisted development because it
separates build production from interactive investigation and gives both humans
and models a shared remote proof surface instead of relying on one machine's
local environment.

Solar Lab consumes a maintainer-configured native Android/Codex computer-use
provider; it does not own the generic provider contract. Hosted sessions
preserve Codex bridge status, provider-manifest, active-build, and session-state
artifacts so a run can show which Android provider capabilities were active.
For the Solar-side boundary, see
[`Android Codex Computer-Use Harness`](docs/android-codex-computer-use.md).

For the public workflow overview, see
[`docs/hosted-ai-development.md`](docs/hosted-ai-development.md).
For the rapid Android inner loop that combines targeted validation,
reusable APK builds, live session install/relaunch, and native visual
observation, see
[`docs/rapid-android-iteration.md`](docs/rapid-android-iteration.md).

## License

This repository is licensed under the GNU Affero General Public License,
version 3 or any later version.

See [`LICENSE`](LICENSE) for the full text.
