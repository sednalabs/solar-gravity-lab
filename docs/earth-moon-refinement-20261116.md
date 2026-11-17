# Earth–Moon laboratory refinement, 16 November 2026

> **Dated implementation record, with 17 November acceptance addendum below.**
> This file documents the work as it developed; intermediate gates and
> benchmarks below must not be mistaken for the final source status.
>
> [PR #237](https://github.com/sednalabs/solar-gravity-lab/pull/237)
> **passed** [exact-head hosted CI](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943)
> and merged to `main` at `bd79d5de`. The published alpha.2 APK remains
> the last distributed build, so these later features are **source-delivered
> but not yet published** as another APK. Physical S25 acceptance and
> signing continuity remain outstanding.

This delivery kept the existing Rust world, FP64 state, history, JNI
boundary, native Vulkan stage and offline NASA assets.

## Complete experiences

### Explore and author

Compact controls reclaim 76dp in portrait and 56dp in landscape relative to
alpha.2. Position and Motion sections keep exact numeric values reachable while
Create/Cancel and preview state remain visible. Scene labels use a small visual
pill inside a 48dp hit region. Labels avoid planets and the zoom rail, and the
velocity handle reflects the actual projected vector. Landscape typing gives the
placement panel the full height available above the keyboard.

Camera Focus and System transitions resolve start and target under one native
lock and animate in the appropriate moving reference frame. A generation token
invalidates both a running animation and an outstanding query when a newer
interaction begins. Lunar probe framing uses nearby lunar context. Visual context
selection does not change the probe's physical reference or force law.

Mapped Earth has a derivative-based narrow silhouette and Sun-weighted blue limb.
The geography, physical radius, actual light direction and analytic depth remain
in the existing renderer. Atmospheric colour and surface orientation are
illustrative, as stated in the app's science information.

The denser scenario paths also receive a correctness repair: medium/far GPU
compaction had retained an older 2D uniform and vertex layout while graphics
consumed 3D vertices. The shared contract now preserves Z/VZ, camera-plane culling,
window tile coordinates and packed strides. Far-buffer fills and counter readbacks
have their required Vulkan usage flags and transfer/compute/graphics dependencies.
Native acceptance exercises those paths and checks visible projected output.

### Simulate with a qualified numerical policy

The exact-work optimisation, conserved diagnostics, AVX2 path and local benchmark
provenance are documented in [physics benchmarks](../engine/physics/benchmarks/README.md).
The reconstructed ARM64 scheduling gate requires 8,128 *useful* gravitational
pairs per selected worker, informed by hosted four-core dense-192 versus
dense-256 measurements. It avoids the measured startup regression at dense-192
while allowing dense-256 to use four workers; affinity-dependent results remain
platform-specific and require another exact-source hosted proof. This is a
dispatch change only, not an altered force law or claimed S25 gain.
The [V2 lab step policy](../engine/physics/benchmarks/lab-step-policy.md) adds
osculating periapsis refinement for exterior motion. It is a fixed-operation
step ceiling, not an adaptive integrator or a general close-encounter guarantee.

New worlds use `newtonian-kdk-f64-lab-step-v2`. Import accepts V1 and V2 and keeps
the model attached to the runtime; export writes that same model. Probe previews
use the saved world's policy as well. An old experiment is not silently migrated
to a new step schedule. Native tests compare a legacy world's future before and
after reopening, and verify the new policy's round trip and preview selection.
Floating-point reproducibility across different hardware kernels remains subject
to their documented scalar-equivalence tolerances.

Invalid, nonpositive or nonfinite step ceilings are rejected rather than treated
as permission for one large step. Radial releases remain usable, and the existing
preview stops at surface intersection. Below-surface propagation, a long radial
fall through a body, strongly perturbed multi-body encounters, atmospheric drag,
tides and resolved collisions remain outside this qualification.

### Keep and move experiments

The existing version-1 `.sglexp` envelope remains readable. Its native simulation
payload stays byte-for-byte intact. Imports use the Storage Access Framework and
validate actual bounded stream content, envelope version, metadata, declared
length, checksum, trailing bytes and the native simulation model. Provider MIME,
filename, reported file size and `available()` are not integrity evidence.

Native validation creates and destroys a separate session without binding the
renderer or replacing the live experiment. Only a validated document with matching
scenario identity is committed as a new local UUID. Import does not open the copy;
Open is a separate action. Existing records cannot be overwritten by a document's
UUID. The active world, including its epoch and bodies, survives failed imports.

Exports read an immutable saved copy, validate its envelope and write through the
user-selected provider. Success is reported only after stream closure succeeds.
The retained runtime owns the transfer, so Activity recreation does not cancel a
write that is already underway. Provider operations do not promise atomic remote
replacement or completed cloud synchronisation; an actual write/close failure is
reported as incomplete. Picker cancellation does not claim a successful transfer.

Transfer progress and results have their own retained status, so routine render
updates cannot erase them. The catalogue can be dismissed during a document
transfer, and reopening it refreshes availability without losing the operation's
result. A committed file operation followed by a listing failure reports both
facts instead of claiming the catalogue is up to date.

The catalogue shows count and byte size, keeps damaged metadata entries visible,
and offers explicit removal of a saved copy with confirmation. Removal preserves
the live session and independently exported documents. The 256-copy/16MiB limits
remain bounded; no history is silently pruned. Long-history compaction is still
Ops w18940.

Export in this version does not retroactively recover private saves from an older
installation signed by another certificate. Stable signing and upgrade continuity
remain Ops w18941. Do not remove an old installation holding the only copies.

Android's official [document access contract](https://developer.android.com/training/data-storage/shared/documents-files)
and [PerformanceHintManager API](https://developer.android.com/reference/android/os/PerformanceHintManager)
provide the platform interfaces used here.

## Verification and delivery

The first intermediate source `b58bbc85dae585368af5c6459b46a55186c1b2c6` passed
[validation 43862730176](https://github.com/sednalabs/solar-gravity-lab/actions/runs/43862730176).
Its actual portrait, placement and landscape images were reviewed. That evidence
predates the lighter labels, shader, transfer and V2 policy integrations and does
not qualify their final behaviour.

The integrated source must pass Rust/FFI, x64/Arm64 scalar-oracle checks, hosted
release throughput measurements, Kotlin/renderer unit suites, Android lint and
native journeys. Native acceptance includes real pointer gestures, moving Focus,
lunar context, actual landscape IME, document-picker round trips and transfer
failure isolation. Screenshots and recordings are evidence from the native app.

A prerelease must use the exact commit that passed the complete lane. Record the
APK's source, manifest, signatures, hashes and actual packaged launch separately.
Hosted runners and an emulator establish their own observed behaviour; physical
S25 frame time, achieved simulation rate, thermal headroom and sustained battery
cost still require a real-device trace, tied to that APK.

Ops: w18950 UX, w18951 physics/CPU, w18953 portability, w18954 integration/release,
w18955 renderer. Related hardware programmes w3454/w3455/w4362 and sustained-device
measurement w10289 remain open until their own acceptance evidence exists.

## Final qualification addendum (17 November 2026)

The intermediate source and its failing Search test were not the final
qualification. The subsequent `887003245d4a2f01928ff0036a3d0f2635e2289f`
head passed [validation 44008781943](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943),
including Rust x64/Arm64, FFI, scalar and ISA checks, Android JVM/lint,
and the full connected native camera, probe mass/edit/reopen, system document
picker, computation/pixel/resize, Search and lifecycle suite. CodeQL,
Security SARIF, coverage and docs checks passed on the same PR head.
GitHub merged source-equivalent tree `621aed5b5f6c7e3ad27f7ed4e71927e0353defa0`
at `bd79d5de`. The post-merge main full OSV scan later
[passed](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44014438874).

This acceptance is for **hosted source and emulator behaviour**. No
`v0.2.0-alpha.3` has been released, no persistent preview signer has been
qualified, and physical S25 performance, energy and real-device gestures
still need separately tied evidence. The published alpha.2's private
saved data must not be discarded to test another signing identity.
