# Android Arm64 Capability Census

Solar Gravity Lab treats hardware capability reporting as a proof artifact, not
as marketing copy. The Samsung Galaxy S25 Ultra target is a Snapdragon 8 Elite
for Galaxy device, but the app must still prove which CPU, GPU, and runtime
capabilities are actually visible to the running build.

Useful public platform references:

- Qualcomm device listing for Samsung Galaxy S25 Ultra:
  <https://www.qualcomm.com/snapdragon/device-finder/samsung-galaxy-s25-ultra>
- Qualcomm and Samsung Snapdragon 8 Elite for Galaxy release note:
  <https://www.qualcomm.com/news/releases/2025/01/qualcomm-and-samsung-redefine-premium-performance-by-bringing-th>
- Qualcomm Snapdragon 8 Elite product brief:
  <https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Elite-Platform-Product-Brief.pdf>
- Samsung Galaxy S25 Ultra processor notes:
  <https://www.samsung.com/us/smartphones/galaxy-s25-ultra/>

Those sources establish the device/platform family and the public GPU API
surface, including Vulkan, OpenGL ES, and OpenCL support. They do not replace a
runtime census. A real-device artifact remains the authoritative source for the
exact CPU feature tokens, Android build identity, Vulkan physical-device
features, OpenCL provider state, and active Solar Gravity Lab runtime paths.

The Rust runtime also performs stable `std::arch` feature probes for the Arm64
extensions Rust can currently detect directly, then unions that with
`/proc/cpuinfo` normalization. That keeps the app truthful on Android and other
Arm64 hosts where one evidence source is partial. SME, SME2, SVE-I8MM, MOPS,
and other extensions that are not yet stable Rust runtime probes remain visible
through the census and `/proc` path rather than being promoted to active solver
claims.

## Census Contract

The canonical collector is
`.github/scripts/collect_android_capability_census.py`.

`.github/scripts/check_arm64_capability_catalog_sync.py` is the drift guard for
this contract. It verifies that the Python collector feature list, the Rust
runtime feature catalog, and the Android Kotlin runtime summary expose the same
feature names and matching Rust/Kotlin bit assignments.

It emits JSON with these top-level sections:

- `device`: host or Android device identity with sensitive node names redacted.
- `cpu`: raw CPU feature-token count, preserved raw evidence tokens,
  normalized tokens, uncataloged detected tokens, auxv values when available,
  and a feature matrix.
- `runtime_truth`: active solver paths, active solver feature claims, baseline
  feature claims, candidate kernel paths, reserved feature claims, and utility
  feature claims.
- `gpu_truth`: expected public GPU APIs for the S25 platform plus a reminder
  that hosted Arm64 runners do not prove OEM GPU behavior.

Each feature row has an explicit state:

- `not_detected`: no evidence in the current census.
- `active_solver_capability_when_simd_arm64_is_selected`: currently only
  `neon`, guarded by runtime dispatch and scalar-oracle parity.
- `baseline_floating_point_capability`: currently `fp`.
- `detected_reserved_until_kernel_exists`: visible to the device, but not an
  active Solar Gravity Lab workload claim.
- `detected_no_current_sgl_hot_path`: useful platform capability with no named
  Solar Gravity Lab hot path today.

## Hosted Versus Real-Device Proof

`validation-lab` can run `lane_set=arm64-capability-census` on a
GitHub-hosted Arm64 runner. That proves the collector, schema, and generic
aarch64 normalization path without burning local Orchard compute.

That lane does not prove Galaxy S25 Ultra hardware. For S25-specific proof,
run the same collector against an attached device or self-hosted runner and
publish the resulting artifact with the validation run. A valid S25 artifact
should include:

- Android product/build identity.
- `/proc/cpuinfo` feature-derived normalized CPU matrix.
- Vulkan physical-device features and extensions.
- OpenCL platform/device/provider details.
- Solar Gravity Lab runtime info: requested/effective CPU backend, solver path,
  CPU feature flags, CPU scheduler truth, requested/effective GPU backend, and
  fallback reason.

## Activation Rules

Detection does not imply activation.

The current active Arm64 solver claims are
`simd.arm64.neon-f64-pairwise` and
`simd.arm64.neon-f64-tiled-pairwise` and
`simd.arm64.neon-f64-parallel-tiled-pairwise`. All are double-precision NEON
gravity paths; runtime dispatch selects tiled paths only for larger body sets
and selects the parallel tiled path only when worker budget is available. SVE,
SVE2, SVE-I8MM, SME, SME2, FP16, FHM, DotProd, I8MM, BF16, RDM, and FCMA remain
reserved until a concrete workload lands with runtime dispatch, scalar-oracle
or error-budget parity, and measured device behavior.

The runtime also keeps named candidate kernel lanes for those extensions. A
candidate lane is useful backlog and telemetry shape, not an active claim. It
becomes an active path only after implementation, parity proof, runtime
eligibility, and device-visible selection all land.

The census summary separates all candidate paths from the feature-qualified
subset that is eligible on the captured device or runner. This is the bridge
between "cataloged ambition" and "worth compiling or benchmarking on this
hardware" while still keeping active solver claims limited to selected paths.

Utility features such as LSE/LSE2, MOPS, CRC, AES, SHA, BTI, MTE, and RNG may
matter to compiler output, memory throughput, hardening, checksums, or future
asset/runtime work, but they are not solver paths.

The census must also preserve tokens that Solar Gravity Lab does not yet
catalog. That uncataloged-token list is intentional: new kernel, SoC, or Android
build capabilities should show up as evidence to review rather than disappear
because the current runtime has no workload for them.
