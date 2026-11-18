# Solar Gravity Lab

Solar Gravity Lab's canonical `main` branch runs the Rust-owned simulation with
the native Vulkan Android stage. **Source on `main` is newer than the currently
published installable APK**: the latest published preview is
[`v0.2.0-alpha.2`](https://github.com/sednalabs/solar-gravity-lab/releases/tag/v0.2.0-alpha.2).
The later Earth–Moon interaction, probe-mass and portable-experiment changes
merged in [PR #237](https://github.com/sednalabs/solar-gravity-lab/pull/237),
the Arm64 FP64 two-target batching in [PR #242](https://github.com/sednalabs/solar-gravity-lab/pull/242),
and the cache/topology/Vulkan programme in [PR #245](https://github.com/sednalabs/solar-gravity-lab/pull/245)
are **implemented on `main`**, with hosted validation, but have **not** been
published in another APK. Physical Galaxy S25 Ultra acceptance is outstanding. Hosted emulator
validation is not physical Galaxy S25 acceptance.

Start with the [documentation guide](docs/README.md) for the current/historical
split, test evidence and known delivery limitations. The
[16–18 November mainline integration record](docs/mainline-integration-week-20261116.md)
reconciles all 51 commits on `main` during the current week, including the
hardware and CI changes.

## Earth–Moon laboratory

The published Android preview opens a paused Earth–Moon laboratory with a
close view of Earth, offline NASA surface maps and portrait/landscape controls.
The implementation on `main` builds on that qualified preview without replacing
the Rust simulation or native Vulkan renderer.

- Explore Earth, the Moon, or the whole system, with persistent playback controls.
- Inspect position and velocity; add probes that **by default** feel gravity
  without exerting it.
- On the newer `main` source, set/edit probe mass in kilograms and independently
  enable **Attracts other bodies** to give a probe gravitational source mass.
  The application does not silently turn display mass into backreaction.
- Run an experiment, save it locally, replace the scene, and reopen the saved
  world paused with its branch history and source metadata intact.
- Read the model, epoch, source credits and limitations through the app's
  science/model information.
- The newer `main` source also supports portable `.sglexp` document
  export/import, saved-copy removal and retained mass/kinematic edits; do not
  assume these are present in the older published alpha.2 APK.

Installable Android previews are published on the
[Releases page](https://github.com/sednalabs/solar-gravity-lab/releases).
They require Android 12 or newer and use the separate
`com.sednalabs.solarlab.internal` package. Read the
[release-channel guide](docs/release-channels.md) before replacing an older
preview: stable signing and upgrade continuity are still separate work, and
uninstalling the app removes its local experiments.

The initial Sun/Earth/Moon state is a recorded JPL Horizons DE441 snapshot at
J2000, advanced by the documented Newtonian model. The
[reference data and qualification notes](data/earth-moon-lab/README.md) distinguish
external ephemeris differences from integration convergence. Surface orientation,
atmosphere and display colour are illustrative; this is not a live ephemeris or
weather feed.

## Platform

The long-term product shape is:

- a Rust-owned authoritative simulation/runtime core
- native client shells over that core
- offline-first scientific data with signed live updates
- a renderer-independent scene contract with backend adapters
- optional hardware fast paths behind open, portable interfaces

The root `app`, `core-*` and `feature-lab` modules remain as legacy reference
material. The canonical Kotlin/Compose shell in `clients/android` and native
Vulkan renderer in `render/android-vulkan` are active parts of the product.

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
- a canonical Android renderer in
  [`render/android-vulkan`](render/android-vulkan) containing the Kotlin host,
  JNI bridge, C++ Vulkan stage, and packaged shaders
- a source-mass-aware runtime/FFI command path so probes, tracers, and teaching
  bodies can carry display mass without silently perturbing canonical bodies
- working Android native builds through `cargo-ndk` and the Android app Gradle
  pipeline
- the merged cache-aware FP64 NEON kernel, OS-qualified Arm capability/topology
  probes and optional Vulkan/QCOM acceleration paths; portable fallbacks and
  hosted correctness tests are in place, but vendor execution and physical
  speedups are not established

What is intentionally still transitional:

- the existing `app`, `core-*`, and `feature-lab` modules remain as v1 reference
  code; `feature-lab` is no longer on the production Android dependency path
- the canonical Android renderer still consumes selected `render-core` and
  core model/math policy types while those contracts migrate toward Rust
  ownership
- the native 3D camera, depth-aware picking, map/3D navigation, placement
  previews, label recovery and near/medium/far tracer streams now run through
  the canonical renderer; they still need installed-device usability and
  sustained-performance acceptance
- broader renderer adapter portability, deeper scene-history use and hardware
  assist paths beyond the tested CPU/Vulkan subset remain future work
- the offline update services are designed but not implemented yet

## Rendering and scientific maturity

The Rust physics and data state are three-dimensional, and the Android camera,
native ray/sphere picking, depth-tested Vulkan scene, camera-space near/medium/far
tracer streams and compute-compaction path are **implemented**, not a proposed
migration. The live boundary is:

```text
world -> RenderSceneFrame -> NativeScenePacket -> native streams -> Vulkan
```

[PR #237](https://github.com/sednalabs/solar-gravity-lab/pull/237) passed
[exact-source hosted validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943)
and merged into `main` at `bd79d5de`. That includes native Android journeys
for probe placement, mass editing, saved documents, camera movement, selection
and computed tracer pixels. It does **not** demonstrate physical S25 frame
times, battery use, thermals, or all close-encounter numerical regimes.

The remaining important gaps are stable preview signing and data-preserving
upgrades, physical-device UX/performance measurement, long-running saved-world
capacity, and unimplemented advanced physical effects (e.g. relativistic,
tidal and resolved collision models). Experimental OpenCL/NPU paths are not
active authoritative physics backends. Older XY-native compaction handoffs are
historical, not current operating instructions.

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

Use [the documentation guide](docs/README.md) for the current contract,
operational proof and historical handoffs before following the ADR sequence.

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

The `docs/v2/*` paths retain their original reset-era names. The
stage-first **runtime/world-ownership contracts** remain useful descriptions of
`main`, while the numbered stage-first **implementation slices** and earlier
roadmap stages are historical. The [documentation guide](docs/README.md)
classifies which references should direct new work.

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

The foundational local validation target is the Rust workspace. For an
installable or device-facing claim, require exact-source remote validation,
actual Android UI evidence and a separately verified packaged APK:

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
ARM64-native ISA proof lane. Exact-head same-PR evidence can be reused when
eligible; CI-only or other-consumer changes no longer force unrelated runtime
lanes on PRs or post-merge main pushes. Product, unknown and shared-validation
changes retain full post-merge qualification, and cross-squash per-lane proof
reuse is **not yet implemented** (Ops w18993). The large
`.github/workflows/hardware-optimisation.yml` proof is **manual-dispatch only**
after PR #247, not a normal PR gate. See [Validation Lab](docs/validation-lab.md).
`prerelease-apk` also runs the ISA proof gate
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
