# Android Acceleration Truth

Solar Gravity Lab has two separate Android truths that must not be mixed up:

- The stage is a native Vulkan renderer. The stage-first runtime mirror should
  bind the Rust runtime session to `SolarSystemRenderHostView`, which hosts the
  native Vulkan surface.
- The Rust runtime remains the authority for world state. GPU paths may assist
  rendering, packet compaction, or future long-horizon workloads, but they must
  not silently become authoritative simulation truth.

## Current Contract

The current Android build supports these runtime backend requests through
`solarlab.preferredGpuBackend` or `SOLARLAB_PREFERRED_GPU_BACKEND`:

- `none`: portable default for host and emulator compatibility.
- `vulkan`: runtime reports Vulkan as the requested in-frame GPU path.
- `vulkan+opencl` / `opencl`: reserved for the dual-backend profile where
  Vulkan owns realtime rendering and OpenCL owns future long-horizon assist
  workloads.

Only report a backend as active when the runtime or renderer actually selected
that path. If a requested path falls back, telemetry and UI must show both the
requested backend and the effective backend.

The Android bridge also requests the Arm64 SIMD CPU backend for device builds.
That request is not itself an activation claim. Runtime info must carry all of
these fields separately:

- requested CPU backend;
- effective CPU backend;
- active solver path;
- normalized CPU feature flags; and
- fallback code when the requested backend is not active; and
- CPU scheduler mode and worker budget.

## What Is Real Today

- Native Vulkan stage hosting is real in the stage-first client.
- Runtime scene export through the Rust FFI is real.
- Vulkan medium/far packet compaction and far tile-bin rendering telemetry are
  real renderer-side acceleration paths.
- CPU feature detection and solver dispatch reporting are real.
- The Arm64 capability census artifact is real. It records detected CPU
  features separately from active SGL workload claims, and preserves uncataloged
  feature tokens for follow-up instead of silently dropping them.
- The implemented Arm64 solver path is `simd.arm64.neon-f64-pairwise`: a
  double-precision NEON pairwise gravity acceleration kernel guarded by runtime
  feature detection and scalar-oracle parity tests.
- The Arm64 kernel registry also names candidate lanes for SVE, SVE2, SME,
  SME2, SVE-I8MM, DotProd, I8MM, BF16, FP16, FHM, RDM, and FCMA so future
  acceleration work has stable path IDs before those paths become active.
- Runtime info reports scheduler truth separately from kernel truth. Adaptive
  tiled scheduling may be reported as a candidate worker budget, but the active
  worker count remains single-worker until a tiled solver is actually selected.

## What Must Stay Honest

- OpenCL is currently an architecture and reporting seam until a provider,
  kernel/workload execution path, parity proof, and fallback report are present.
- Arm64 SIMD reporting is not enough by itself. A specialized solver path must
  be backed by measurable device behavior and parity with CPU scalar truth.
- SVE, SVE2, SVE-I8MM, SME, SME2, FP16, FHM, DotProd, I8MM, LSE, CRC, and MOPS
  may be detected and reported as device capabilities, but they are not active
  solver claims unless the runtime reports a concrete solver path that uses
  them.
- BF16, RDM, FCMA, crypto, hardening, and memory-operation features may also be
  captured by the census. They must remain utility or reserved capabilities
  until an SGL workload explicitly consumes them.
- Lower-precision and matrix/vector extensions are reserved for future
  visualization, tracer-assist, or explicitly bounded compute slices until a
  precision policy and scalar-oracle equivalence gate exists.
- Candidate scheduler or kernel registry entries are not active acceleration
  claims. They are engineering handles for future implementation, proof, and
  device measurement.
- Hosted emulator proof can validate Vulkan/runtime wiring, but real device
  claims need a Galaxy-class Android device artifact.

## Validation Expectations

`validation-lab` should use `android_validation_mode=stage-first-mirror-on` as
the canonical hosted Android proof for the runtime mirror. That lane must:

- build with `solarlab.preferredGpuBackend=vulkan`;
- enter the stage-first runtime mirror, not only the local sandbox;
- bind a nonzero native runtime session handle;
- assert that backend summary telemetry includes CPU and GPU truth; and
- preserve logs/screenshots as the shared audit trail.

For CPU ISA work, dispatch `validation-lab` with `lane_set=arm64-isa-proof`.
That lane runs on GitHub-hosted Arm64 hardware, records normalized CPU
capabilities, and proves the active solver path without waiting for a broader
workspace or APK build.

For capability inventory work that does not need solver tests, dispatch
`validation-lab` with `lane_set=arm64-capability-census`. That lane emits the
same schema used by real-device census runs while making clear that a hosted
runner is not Galaxy S25 Ultra proof.

Use the local sandbox lane only for local authoring surface checks. Do not use a
local sandbox smoke as proof that the accelerated runtime mirror is healthy.

See [`Android Arm64 Capability Census`](android-arm64-capability-census.md) for
the census schema and S25-specific proof contract, and
[`Device-Native Acceleration Program`](device-native-acceleration.md) for the
broader ISA-kernel and native computer-use direction.
