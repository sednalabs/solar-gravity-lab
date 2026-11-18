# Documentation guide

This index distinguishes the **implemented source**, **tested behaviour**,
**published Android APK** and **historical design work**. Use it before old
stage-first or compaction handoffs. Audited against canonical `main`
through `b2e3dbed9e73e98fd6d749d71ee7f2c7a29b6d74` (18 November 2026).
See the [16–18 November integration record](mainline-integration-week-20261116.md)
for the commit-by-commit scope and which checkpoints remain historical.

## Delivery truth

- **Source:** PR [#237](https://github.com/sednalabs/solar-gravity-lab/pull/237)
  merged the native Earth–Moon lab, probe mass, portable documents,
  camera/selection, renderer and physics work at `bd79d5de`.
  The 17 November documentation/security reconciliation followed, then
  [#242](https://github.com/sednalabs/solar-gravity-lab/pull/242) delivered
  NEON FP64 two-target passive batching and
  [#245](https://github.com/sednalabs/solar-gravity-lab/pull/245) delivered
  cache-aware physics, topology, optional Vulkan and Qualcomm paths.
  [#247](https://github.com/sednalabs/solar-gravity-lab/pull/247) made
  the hardware proof manual-only and
  [#248](https://github.com/sednalabs/solar-gravity-lab/pull/248)
  narrowed CI routing for unrelated changes. All were merged by
  `b2e3dbed` on 18 November.
- **Hosted proof:** the [#237 exact-source validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943)
  passed Rust x64/Arm64, FFI, Android JVM/lint, native Android journeys,
  camera/compute pixel checks and parity. The
  [#245 final-head validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44279625030)
  and [hardware proof](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44279624931)
  passed hosted CPU, Vulkan portability and Android qualifications; a later
  source-identical replay also passed
  ([validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44283854151),
  [hardware](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44283854331)).
  These are **not** Qualcomm vendor-hardware runs or physical-phone acceptance.
  The [17 November main security run](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44014438874)
  passed its OSV scan.
- **Published APK:** [`v0.2.0-alpha.2`](https://github.com/sednalabs/solar-gravity-lab/releases/tag/v0.2.0-alpha.2)
  is still the most recent installable release. **The later `main` changes
  are not yet a released APK.**
- **Not proven:** no installed Galaxy S25 Ultra user/thermal/battery validation
  for the newer code, no stable signing or guaranteed upgrade between different
  preview certificates, no general relativistic, drag or collision simulation.

## Canonical contracts and implementation

- [Architecture](architecture.md): Rust world, FFI, Android, native Vulkan
  and hardware ownership.
- [Renderer current state](rendering-architecture-current-state.md),
  [camera and interactions](camera-and-interaction-model.md),
  [scene/world contract](scene-world-model-contract.md) and
  [frame lifecycle](frame-lifecycle.md).
- [Native viewport and placement contract](../render/android-vulkan/LAB_NAVIGATION.md),
  [Android renderer](../render/android-vulkan/README.md) and
  [Android shell](../clients/android/README.md).
- [Probe mass and atomic edits](probe-mass-authoring.md),
  [Earth–Moon JPL reference](../data/earth-moon-lab/README.md),
  [scenario packs](scenario-packs.md), and
  [laboratory timestep profiles](../engine/physics/benchmarks/lab-step-policy.md).
- [CPU performance evidence](../engine/physics/benchmarks/README.md),
  [CPU/GPU hardware programme after PR242](hardware-maximisation-20261118.md),
  [Cache engine, topology and Arm qualification](cpu-cache-topology-20261118.md),
  [new passive FP64 NEON batch and measured improvements](aarch64-extension-assessment-20261118.md),
  [Arm64 activation truth](android-acceleration-truth.md),
  [device capability programme](device-native-acceleration.md) and
  [compaction decision record](compute-compaction-reintroduction-plan.md).

## Build, validation and operational reference

- [Physical-device research build](device-research-benchmark.md) documents the
  separate benchmark app, the 61-opportunity coverage ledger, recording/export
  controls, comparison rules and remaining implementation boundaries. Its
  publication and phone acceptance are separate from the historical delivery
  snapshot above.
- [Vulkan runtime acceleration qualification](vulkan-runtime-qualification-20261118.md)
  records optional workload gates, profiling switches and the separate device
  acceptance requirements for the post-#242 implementation.
- [Weekly mainline integration and remaining gates](mainline-integration-week-20261116.md),
  [Validation Lab](validation-lab.md),
  [rapid Android iteration](rapid-android-iteration.md),
  [hosted AI development](hosted-ai-development.md) and
  [live interactive sessions](interactive-android-session.md).
- [Release channels](release-channels.md) documents version codes,
  debug-signed preview certificates, preservation of existing private saves
  and proof required for the next APK.
- [CI cache](ci-cache-rollout.md); the scoped OSV scanner advisory on
  `pin-utils` is tracked in
  [#238](https://github.com/sednalabs/solar-gravity-lab/issues/238)
  and expires on 17 February 2027.

## Dated delivery evidence, not current specifications

The [16 November refinement record](earth-moon-refinement-20261116.md),
[17 November recovery](lab-recovery-20261117.md) and
[repair checkpoint](repair-checkpoint-20261117.md) capture intermediate
commits, failures and decisions. Read their final acceptance addenda for the
result, not the earlier "pending" statements as if they remain outstanding.

The ADRs under [`adr/`](adr/) are historical **accepted decisions** and
remain valuable; an accepted architectural direction is not proof that every
optional service or backend exists.

The current Rust/native world-ownership contracts in
[`v2/stage-first-runtime.md`](v2/stage-first-runtime.md) and
[`v2/stage-first-native-simulation-world-unification.md`](v2/stage-first-native-simulation-world-unification.md)
remain applicable. Other reset-era `v2/` **numbered implementation
slices** and `v6`, `v7`, `v8` handoffs, catalog TSV instructions,
old Vulkan/XY compaction notes and self-hosted runner evaluations are
**historical or deferred**. Do not use superseded flat packet, old camera,
or earlier scheduler instructions as current build contracts.

## Documentation validation convention

A claim should identify whether it means **implemented source**, **hosted
tested**, **published artifact** or **real device proven**. Link relevant tests,
commits or assets. Do not quietly change a dated benchmark or intermediate
handoff to make it look current: append a status note and link the replacement.
Validate in-repository links and run `docs-sanity` before merging documentation.
