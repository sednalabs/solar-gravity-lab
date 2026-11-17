# Architecture

Solar Gravity Lab `main` is the Rust-owned platform line. The canonical
Android Earth–Moon laboratory, native camera/picking, preview/mass authoring,
portable experiment documents and 3D Vulkan compute streams are implemented
and passed exact-source hosted validation in
[PR #237](https://github.com/sednalabs/solar-gravity-lab/pull/237).
Their merged code is newer than the published alpha.2 APK; physical S25
acceptance and signing continuity are separate.

The canonical product path on this branch is:

- `engine/` for authoritative runtime, history, scene extraction, data, and hardware crates
- `proto/solarlab/v2/` for versioned cross-language contracts
- `engine/ffi/` for the C ABI and opaque-handle boundary
- `clients/android/` for the Android shell over the Rust runtime
- `render/vulkan-adapter/` for the first backend adapter crate
- `render/android-vulkan/` for the Android host, JNI bridge, native Vulkan
  renderer, and packaged shaders

The root-level Kotlin modules (`app`, `core-*`, `feature-lab`, `render-core`) are
retained as legacy/reference or transitional policy material. `feature-lab` is
not a production dependency. The canonical Android renderer currently consumes
selected core/render policy types while those contracts migrate toward Rust
ownership.

## Canonical boundaries

### Runtime ownership

`engine/runtime` owns:

- session and branch state
- command application
- the authoritative distinction between display/inertial mass and gravitational
  source mass
- checkpoints and history records
- snapshot publication
- scene extraction inputs

All external integrations should treat the runtime as the single source of truth
for mutable world semantics.

Spawned bodies carry two mass values across the Rust/FFI/Android boundary:

- `mass_kg` is the body's display/inertial mass for labels, density,
  authoring, and teaching affordances.
- `source_mass_kg` is the mass that participates as a gravitational source in
  the authoritative solver.

This is not a renderer or class-name convention. Tracers, probes, spacecraft,
and small-body catalog markers can be visible and massive for teaching purposes
without perturbing planets unless their source mass is explicitly nonzero.

### Contract ownership

`proto/solarlab/v2` defines the versioned schema surface for:

- runtime/session contracts
- diagnostics and hardware reporting
- render-scene export
- data/update package contracts

`engine/ffi` maps those runtime concepts into a stable C ABI using:

- opaque session handles
- explicit render-packet handles
- borrowed buffer views
- explicit release calls for exported packet memory

### Dual-GPU policy ownership

OpenCL is a **reserved architecture/reporting seam**, not an implemented
long-horizon integration backend. If a future provider is implemented, the
intended split is:

- `Vulkan` owns realtime rendering and in-frame packet compaction work.
- `OpenCL` owns long-horizon tracer integration and forecast/path sampling assists.
- CPU remains authoritative for checkpoint publication and canonical world state.

Interop is policy-driven, not implicit. The runtime/hardware surface should expose
the active workload map plus an explicit error-budget policy so clients and
telemetry can report what is actually active.

### Client ownership

`clients/android` is a shell, not an authority layer. It owns:

- Android lifecycle and Compose shell state
- JNI transport over the FFI surface
- host rendering of exported scene packets
- control dispatch into the runtime command surface

The Android client must not grow its own simulation rules.

The shell depends directly on `:android-vulkan-renderer`, sourced from
`render/android-vulkan/`. The renderer owns lifecycle-bound Vulkan/NDK concerns
after scene export; it does not own world integration or history.

## Implemented rendering boundary

The production stage implements the 3D camera-space path:

```text
world -> RenderSceneFrame -> NativeScenePacket -> native streams -> Vulkan
```

Rust owns the authoritative bodies, source-mass semantics, integration and
history. Scene extraction produces renderer-neutral appearance, traces and
lighting facts; packets use a native camera basis, origin-relative coordinates
and distinct authoritative/near/medium/far/trail streams. The C++/GLSL path
renders those streams with depth and camera-space medium/far compute
compaction. It does not make the GPU authoritative for physics.

The native surface now owns orbit navigation, explicit map-plane navigation,
ray/sphere-aware picking and presentation-aligned viewport anchors. Compose
owns labels, 48dp interaction targets, accessible controls, staged probe editing
and the document UI. Exact-source [hosted Android validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943)
exercised mass changes, search, placement, 3D computed tracer pixels,
resizing, saving and reopening.

## Current maturity and remaining work

Already implemented and hosted-tested on `main`:

- Rust world state, source-mass-aware solver and explicit atomic body-property commands;
- checkpoint/history and V1/V2 model-aware saved-world import/export;
- Android-native 3D camera, map interaction, picking and placement previews;
- Vulkan graphics and 3D medium/far compute-compaction, with native pixel checks;
- Arm64 NEON and x64 AVX2 paths with scalar-oracle and scheduling tests.

Not yet a claim of shipped or device-proven behaviour:

- the published APK remains `v0.2.0-alpha.2`, predating later merged work;
- changing Android signing identities can prevent in-place upgrades and access
  to private saved data; stable signing is still outstanding;
- no installed physical S25 throughput, frame-pacing, thermal or battery proof
  establishes a device performance improvement;
- OpenCL/NPU long-horizon assists, signed update services, other graphics
  backends, relativistic effects, resolved collisions, atmospheric drag and
  general close-encounter accuracy remain unimplemented or unqualified.

The code and proof status is tracked in [the documentation guide](README.md),
[lab delivery record](earth-moon-refinement-20261116.md) and
[release-channel guide](release-channels.md). Older XY-native and early
stage-first handoffs are historical implementation snapshots.

## Detailed architecture reading order

Read the durable architecture in this order:

1. `docs/adr/0001-rust-owned-core.md`
2. `docs/adr/0002-versioned-protobuf-contracts.md`
3. `docs/adr/0003-c-abi-and-opaque-handles.md`
4. `docs/adr/0004-offline-first-data-and-updates.md`
5. `docs/adr/0005-render-scene-and-backend-adapters.md`
6. this file (`architecture.md`)
7. `rendering-architecture-current-state.md`
8. `camera-and-interaction-model.md`
9. `scene-world-model-contract.md`
10. `performance-and-scaling-strategy.md`
11. `frame-lifecycle.md`
12. `compute-compaction-reintroduction-plan.md`
13. `android-acceleration-truth.md`
14. `scenario-packs.md`
15. `v2/architecture.md`
16. `v2/roadmap.md`

## Operational repo truth

On canonical `main`:

- prerelease packaging targets `clients/android`
- validation should prove the Rust workspace and the Android shell
- the root Gradle settings should not imply that the legacy Kotlin app is the
  shipping or validated app on this branch

If you need the design rationale behind this layout, read the ADR chain first and
then the current-state docs above before going further into the historical
handoff material.
