# Solar Gravity Lab

Solar Gravity Lab is an Android/Kotlin project for a physically grounded solar-system sandbox.

The architecture is split on purpose:

- CPU simulation is the source of truth for physics.
- A backend-neutral render packet is built from simulation snapshots.
- Rendering is Vulkan-first, with an OpenGL ES fallback so the app still runs on devices without stable Vulkan support.

The public name is **Solar Gravity Lab**. Some internal package/module names still use `solarlab` for historical reasons.

## Current status (snapshot: April 3, 2026)

This repository is an active engineering scaffold, not a finished app release.

What is implemented and usable today:

- Multi-module Android Studio project structure.
- Pure Kotlin/JVM core modules for math, domain model, simulation, and render-scene assembly.
- N-body simulation with double-precision state.
- Massive-body vs tracer-body dynamics.
- Merge-collision handling with linear momentum conservation.
- Barycentric recentering for seeded systems.
- Epoch-tagged seeded major-planet states with bundle/fallback layering.
- Renderer host that prefers Vulkan and falls back to OpenGL ES when needed.
- Native Vulkan pipeline with swapchain/render-pass/framebuffer/command recording, scene-packet ingestion, SPIR-V graphics pipelines, and compute-based medium/far tracer compaction.

What is intentionally incomplete:

- No bundled, authoritative DE/Horizons seed file in this repo yet (seed-bundle path exists; fallback catalogue is used when no bundle is present).
- GPU-side tracer integration is not implemented yet (simulation remains authoritative on CPU).
- Synthetic asteroid/Oort populations are generated approximations, not catalogue-complete object sets.
- Collision model is merge-only (no fragmentation/elastic models).
- Android app UX is still minimal and engineering-focused.

## Project modules

- `app`
  Android entry point and activity wiring.
- `core-math`
  Vector math and low-level numerics (pure Kotlin/JVM).
- `core-model`
  Domain entities, constants, configuration, snapshot contracts (pure Kotlin/JVM).
- `core-simulation`
  Integrator, scenarios, diagnostics, seed catalogue layering, collision logic (pure Kotlin/JVM).
- `render-core`
  Backend-neutral render scene assembly and `NativeScenePacket` packing (pure Kotlin/JVM).
- `feature-lab`
  Android render host, Vulkan bridge/native renderer path, OpenGL ES fallback path, and session orchestration.

## Physics model at a glance

- Integrator: kick-drift-kick (leapfrog/velocity-Verlet style).
- Units: SI (`m`, `kg`, `s`).
- Body categories:
  - Massive bodies mutually interact.
  - Tracer bodies feel massive bodies but do not perturb others.
- Collision handling: merge, conserve total mass + linear momentum, derive merged radius from combined volume.

This model is meant to be physically credible while remaining scalable on mobile hardware.

## Rendering architecture (real vs planned)

Current architecture:

1. `core-simulation` generates authoritative simulation snapshots.
2. `render-core` converts snapshots into backend-neutral scene data and a JNI-friendly packet.
3. `feature-lab` host selects Vulkan or OpenGL ES.
4. Native Vulkan and OpenGL backends consume the same logical scene contract.

Current Vulkan reality:

- Real initialization path (instance/device/surface/swapchain/render pass/framebuffers/command buffers/sync).
- Real SPIR-V shader pipeline creation from bundled shader assets.
- Real scene stream upload with revision-based caching.
- Real compute pass for medium/far tracer compaction into indirect draw buffers.

Still planned for Vulkan:

- GPU-side tracer integration.
- Further GPU-local/staged buffer strategy refinements for heavier scenes.
- Additional precision/performance work for very large camera spans.

## Seed data strategy

Seed state loading is layered so simulation/rendering are not coupled to one data source:

1. Optional `CartesianSeedBundle` asset (authoritative when present and valid).
2. `JplApproximateSeedCatalog` fallback for major planets.
3. Representative orbital-element fallback for currently unbundled dwarf bodies.

This keeps the pipeline stable while making it easy to drop in improved ephemeris bundles later.

## Validation and build expectations

Remote-first validation is now intended to happen through the dispatch-only
GitHub Actions workflow documented in `docs/validation-lab.md`, so Orchard
branches can offload bootstrap work, targeted JVM slices, frontier-style next
blocker harvests, and broader Android checkpoints without burning local host
compute.

For phone testing, use `.github/workflows/prerelease-apk.yml` to build an
installable `prerelease` APK artifact on GitHub Actions. That path is meant for
internal dev preview sideloading only: it uses the application id
`com.graciousgazelles.solarlab.internal` and is signed with the default debug
key, so it is appropriate for device testing but not for a public production
release.

For contributors, the most reliable near-term validation target is the pure JVM core:

- `core-math`
- `core-model`
- `core-simulation`
- `render-core`

This repo currently relies on a remote-first validation workflow documented in [`docs/validation-lab.md`](docs/validation-lab.md).

Important current notes:

- `gradle/wrapper/gradle-wrapper.jar` is now tracked on `main`, and the validation workflow can regenerate it remotely whenever the wrapper version needs to change.
- The first installable preview line uses version `0.1.0-alpha.1`. That is intentionally conservative semver-style prerelease numbering wrapped in an internal dev preview channel rather than a public “1.0” style launch signal.
- Documentation-only changes now have a cheap automatic path through `.github/workflows/docs-sanity.yml`, so routine README/docs updates do not need to spend the full remote validation budget.

If you want broader implementation context, see [`docs/architecture.md`](docs/architecture.md) and [`docs/release-channels.md`](docs/release-channels.md).

## Getting started

1. Clone the repository.
2. Open the project root in Android Studio.
3. Let Gradle sync resolve the multi-module structure.
4. Use the validation workflow lanes (see `docs/validation-lab.md`) for heavier/remote checks.

## Near-term milestones

1. Add and validate a first authoritative Horizons/DE-style seed bundle asset.
2. Complete the next Vulkan compute milestones (including GPU-side tracer integration).
3. Keep the pure JVM core green as the baseline while expanding Android validation coverage.
4. Improve interaction UX (camera controls, object targeting/inspection, time controls) without weakening simulation correctness.

## Non-goals for this phase

- Shipping a polished consumer app UI.
- Claiming full-ephemeris scientific completeness for every small body.
- Replacing CPU-authoritative simulation with an all-GPU simulation model.

The focus right now is a robust, testable physics/rendering foundation that can be extended safely.
