# Device-Native Acceleration Program

Solar Gravity Lab intentionally treats device acceleration as an experiment in
squeezing the best available runtime behavior from modern Android hardware while
keeping the simulation contract honest and reproducible.

The target shape is broad, not minimalist:

- use the Rust runtime as the authoritative simulation core;
- use native Android shells and the native Vulkan stage for immersive rendering;
- expose detected CPU and GPU capabilities as telemetry;
- add as many Arm64 ISA-specific kernels and assist paths as the device can
  justify; and
- validate active paths through hosted proof, real-device artifacts, and native
  Android computer-use sessions.

## Runtime Truth Model

Capability state must move through explicit stages:

1. `detected`: the device or hosted Arm64 runner reports the feature.
2. `cataloged`: Solar Gravity Lab knows the feature and preserves it in runtime
   telemetry.
3. `candidate`: a named kernel or assist path exists as an intended workload.
4. `implemented`: code exists behind a concrete dispatch path.
5. `parity-proven`: the path has scalar-oracle or documented error-budget proof.
6. `eligible`: runtime selection may choose it on compatible devices.
7. `active`: runtime info reports that this exact path was selected.

Detection alone is never an activation claim. This matters most for SVE, SVE2,
SME, SME2, packed integer extensions, and lower-precision assists: they are
exactly the sort of capabilities the project wants to exploit, but each one
must earn its way into active runtime selection.

## Current Arm64 Kernel Registry

The current active authoritative Arm64 solver path is:

- `simd.arm64.neon-f64-pairwise`

The registry also names experimental candidate lanes so the project does not
forget the intended breadth:

- `simd.arm64.sve-f64-batch-candidate`
- `simd.arm64.sve2-f64-batch-candidate`
- `simd.arm64.sve-i8mm-packed-assist-candidate`
- `simd.arm64.sme-tiled-f64-candidate`
- `simd.arm64.sme2-tiled-f64-candidate`
- `simd.arm64.dotprod-packed-assist-candidate`
- `simd.arm64.i8mm-packed-assist-candidate`
- `simd.arm64.bf16-forecast-assist-candidate`
- `simd.arm64.fp16-visual-assist-candidate`
- `simd.arm64.fhm-visual-assist-candidate`
- `simd.arm64.rdm-vector-assist-candidate`
- `simd.arm64.fcma-vector-assist-candidate`

Candidate paths are product direction and engineering backlog, not active
claims. They should become active only after implementation, parity proof,
runtime dispatch, and device-visible telemetry land together.

## Scheduler Direction

Runtime info now separates active workers from candidate worker budget. That is
deliberate: the present authoritative solver remains single-worker unless a
parallel/tiled implementation is actually selected, while telemetry can show
when a workload is large enough to justify an adaptive tiled scheduler.

This gives the project a safe way to plan the next solver stage:

- introduce target-tiled or pairwise-tiled kernels behind explicit path IDs;
- compare against the scalar oracle over representative scenario packs;
- measure on hosted Arm64 first when useful;
- prove on the Galaxy-class Android device before making device-specific
  performance claims; and
- surface the selected scheduler in Android runtime telemetry.

## Validation Contract

Use `validation-lab` as the canonical proof surface. For acceleration work:

- `lane_set=arm64-isa-proof` proves active ISA dispatch and scalar-oracle parity
  on hosted Arm64.
- `lane_set=arm64-capability-census` proves capability inventory and catalog
  drift handling without claiming Galaxy-specific behavior.
- `lane_set=runtime-cpu-truth` is the focused bundle for CPU truth, FFI ABI,
  and Android runtime-info surfacing.
- `android_validation_mode=stage-first-mirror-on` proves that the Android shell
  binds the native runtime and reports backend truth through the stage-first
  mirror.

Real-device claims still need real-device evidence. Hosted Arm64 runners are
valuable for fast, auditable proof, but an S25-class artifact remains the
source of truth for exact device-visible CPU feature tokens, Vulkan/OpenGL ES
surface, OpenCL provider state, thermal behavior, and native UI interaction.

## Native Android Computer-Use Pillar

The native Android computer-use tool is part of the acceleration program, not a
side quest. It should be used to inspect the running app, verify runtime-info
presentation, interact with scenario packs, and help tune the stage visually and
ergonomically while keeping screenshots and tool events in the Codex-native
transcript path.

The long-term loop is:

- implement or expose a device-native capability;
- validate compile/runtime truth remotely;
- launch an interactive Android session for visual and ergonomic inspection;
- use native computer-use calls to operate the app like a user; and
- feed the observations back into solver, renderer, and scenario-pack work.
