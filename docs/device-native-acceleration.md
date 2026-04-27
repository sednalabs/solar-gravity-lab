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

Detection should use every trustworthy local surface available. The Rust
runtime now unions stable Arm64 `std::arch` probes with normalized
`/proc/cpuinfo` evidence, while the capability census keeps preserving auxv and
raw token evidence. That lets a Galaxy-class device advertise the richest
available capability picture without confusing "visible to the runtime" with
"executed by an SGL kernel".

## Current Arm64 Kernel Registry

The current active authoritative Arm64 solver paths are:

- `simd.arm64.neon-f64-pairwise`
- `simd.arm64.neon-f64-tiled-pairwise`
- `simd.arm64.neon-f64-parallel-tiled-pairwise`

All active paths use NEON double-precision gravity math. The tiled path is
selected only for larger body sets, and the parallel tiled path is selected
only when the runtime also has more than one worker available. This uses the
large-scene kernel shape and available CPU parallelism without overclaiming SVE,
SME, or packed-integer extensions.

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

Runtime telemetry also reports how many cataloged kernel paths are active,
eligible candidates, or blocked candidates for the detected CPU feature set,
and carries compact path masks so Android can name the exact active, eligible,
and blocked lanes in the runtime readout. That lets a Galaxy-class device say
"these SVE/SVE2/SME/I8MM/BF16-style lanes are worth trying here" without
turning feature detection into an execution claim.

## Scheduler Direction

Runtime info separates active workers from candidate worker budget. That is
deliberate: small scenes remain single-worker, large Arm64 NEON scenes may
select the active parallel tiled kernel when worker budget is available, and
telemetry can still show candidate worker budget when a workload is large
enough but a parallel path is not selected.

Runtime info also exposes the tile plan used by the large-scene scheduler:
tile size, tile count, and parallel tile-worker slots. This is intentionally
practical telemetry for the S25 Ultra optimization loop: when a scenario pack
is meant to stress the device, the Android UI and validation artifacts should
show not just that the parallel path is selected, but how much tile work the
runtime planned to distribute.

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

The stage-first runtime mirror should therefore present acceleration telemetry
as a device cockpit, not only as raw debug text. The live Android readout should
name the active solver/kernel lane, the eligible future lanes, blocked lanes,
scheduler tile plan, GPU backend, and workload split in a way that can be read
from screenshots and native computer-use observations. The dense backend summary
should remain available underneath as audit text, but it should not be the only
surface a human or agent can use during the S25 Ultra optimization loop.

The cockpit should stay visually expressive without weakening the truth model:
active lanes can glow, eligible lanes can look reserved or charged, blocked
lanes can be visibly distinct, and fallback lanes can look cautionary. Those
colors are presentation, not proof. The labels and audit string remain the
source of truth, and they must continue to separate active execution from
candidate capability.

Runtime status text should be bounded for the cockpit surface. Large packet or
backend payload summaries are useful as artifacts and diagnostics, but the
device-visible readout should compact them before they crowd out the lane
visualization that the native computer-use loop is trying to inspect.
Revision identifiers should be summarized into scenario, branch, mission time,
and payload size when the raw packet key is too large for a readable HUD.
The always-visible HUD should go one step further: keep packet hashes, byte
counts, path counters, and large payload sizes in debug/audit surfaces, while
the flight status line stays focused on connection state, render readiness,
scenario time, and the active graphics/compute capability.

The long-term loop is:

- implement or expose a device-native capability;
- validate compile/runtime truth remotely;
- launch an interactive Android session for visual and ergonomic inspection;
- use native computer-use calls to operate the app like a user; and
- feed the observations back into solver, renderer, and scenario-pack work.
