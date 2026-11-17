# Android Vulkan renderer

This Android library is the canonical native rendering module for the Solar
Gravity Lab Android client.

It owns:

- the lifecycle-aware Kotlin render host and interaction adapter;
- the JNI bridge to `libsolarlab_vulkan.so`;
- the C++ stage controller and Vulkan resource/rendering implementation; and
- the AOT-compiled GLSL shader assets packaged into the application.

It consumes scene and camera policy types but does not own simulation state,
force integration, history, or physical truth. The Rust runtime remains the
only authoritative world. C++ and GLSL operate only after the exported scene
boundary.

Authoritative bodies also carry a Rust-owned, render-only appearance contract.
The Vulkan stage turns those immutable facts into mapped or procedural surfaces,
atmosphere limbs, analytically occluded ring planes, and comet coma/dust/ion
tails. Visual scale can expand a billboard to contain those effects, but it
never feeds positions, masses, velocities, radii, forces, or integration state
back into the runtime. The native scene summary reports `FX=[R,At,C]` counts so
interactive acceptance can prove that ring, atmosphere, and comet facts reached
the GPU scene.

The forward Android build maps this directory to the Gradle project
`:android-vulkan-renderer`. Keep JNI package names, consumer R8 rules, shader
asset paths, and CMake inputs synchronized when moving bridge entry points.

## Earth–Moon presentation

Earth and Moon use bundled NASA colour maps; their original files, credits,
checksums and limits are recorded in [surface sources](src/main/assets/solarlab/surfaces/README.md).
The native decoder runs once per Vulkan device and uploads a 4 MiB read-only map
buffer. Other bodies retain their procedural materials. Surface orientation and
atmospheric effects are illustrative; none of these assets changes the simulation.
Mapped Earth uses pixel-scale silhouette coverage and a thin blue atmosphere weighted
by the actual Sun direction. The colour treatment and atmosphere are illustrative; the
solid disc radius, mapped geography, analytic depth and surface terminator are preserved.

The native stage shares its orthographic viewport scale with touch projection. Body picking
tests the front surface of the displayed sphere before considering padded touch targets;
the billboard fragment shader writes the same analytic sphere depth. The Earth–Moon
laboratory's Home frame includes Earth and Moon while keeping the Sun available for lighting.
Swapchain images stay in window coordinates and advertise an identity pre-transform;
Android performs display rotation. This matches the shader viewport and touch coordinates
without rotating and stretching a landscape buffer. Swapchain creation logs the requested,
surface and buffer extents plus both transforms so native rotation evidence can verify the
actual presentation contract, in addition to the pure projection formulas.
Android's `VK_SUBOPTIMAL_KHR` result is accepted as a completed present: it is expected
when the compositor handles a non-identity display rotation. A genuinely out-of-date
swapchain remains an error. This keeps focus and camera updates alive after rotation.
An initial laboratory follow of Earth or Moon uses the same close frame as an explicit
Frame request after the first native bodies arrive. This also handles focus requested
before surface creation; later camera updates preserve user zoom.
Free navigation in this laboratory travels with Earth's translation in non-rotating axes,
preserving the user's local pan, orientation and zoom during accelerated solar motion. The
Moon and probes retain their movement relative to Earth. Other scenarios keep their existing
free-camera behaviour.
Camera framing animations interpolate local offsets from the live native reference: the
subject for Frame/Focus, and Earth for laboratory Home. Their start offset is measured at
the displayed fraction and their target offset at its matching exported epoch. New gestures,
camera commands and lifecycle invalidations cancel pending frame queries as well as active
animations. A laboratory probe uses the nearest Earth/Moon surface as its framing context;
this does not assign a physical primary or change its trajectory.

Runtime snapshots remain authoritative. Forward playback interpolates only presentation
positions between matching snapshots, rebased to the current scene origin. Follow cameras
and the primary light use the same interpolation fraction. Pausing, changing history or
changing the set of bodies snaps to the current snapshot. Trails and tracer streams continue
to represent the current exported snapshot rather than interpolated physics. Laboratory
trails also receive Earth's common presentation translation, using the same submitted
fraction as the bodies, so a probe's orbit stays centred during each display interval.

Android requests display callbacks while an update or interpolation is pending. A frame gate
coalesces invalidations and permits one native render in flight; pause and surface teardown
stop scheduling. Display-only frames update uniforms and reuse uploaded scene buffers, without
exporting the Rust scene again. This is a scheduling policy, not a measured frame-rate claim.

## Medium and far compute contract

The medium/far compaction kernels consume the same camera basis, viewport and
metres-per-pixel uniform prefix as their graphics shaders. Source state retains all
three position and velocity components in a scalar-packed 40-byte record; compacted
output is the same 20-byte three-dimensional vertex consumed by direct drawing.
Compaction only selects and bins exported points. It does not integrate positions.
Far tiles use window coordinates, including Vulkan's downward window Y, and publish
both zero-filled slots and shader writes before the fixed-capacity draw. Tile counters
have explicit transfer-source usage and a compute/clear-to-readback dependency.

This corrects an older mismatch in which the compute kernels still interpreted a 2D
uniform/output layout after graphics had moved to 3D. The lightweight
`src/test/python/test_compute_contract.py` regression checks the actual shader/C++
layouts and camera transform, including nonzero Z and portrait/landscape tile placement.
Native stress qualification must additionally verify projected visible points and
readbacks on a Vulkan device; successful shader compilation or nonzero dispatch alone
does not establish correct rendering.

## GPU resource reuse

The existing graphics fence still protects all scene writes. New packets retain compatible
buffer allocations, with capacity growing geometrically as trails grow. Empty streams release
their buffers; teardown releases the full scene. Actual memory properties are checked before
reusing a host-visible or device-local allocation. Failed compute setup retains any in-flight
upload targets until a later fenced update or cleanup, while direct rendering remains available.

Recorded scene commands survive updates that only change payload bytes or camera uniforms.
Buffer allocation generations, draw counts, compute dispatch parameters and trail strip layout
determine when recording is necessary. Stable compute descriptors are left untouched; changed
descriptors force recording. The command pool's allocations are retained when recording does
become necessary. Resize and failed partial uploads also invalidate recorded commands.

The raw scene summary exposes `resources=[alloc,upload,record,reuse,cpuBytes]`: cumulative
successful GPU-buffer allocations, completed scene uploads, scene command recording batches,
uploads reusing recorded commands, and bytes copied into mapped upload memory. `bytes` is the
latest scene's actual copied payload; `resident` is its allocated buffer capacity. These counters
are structural evidence, not GPU timings or a device speedup claim. Native qualification should
check steady updates, growing/removing probes, rotation and medium/far compute fallback.

## Optional Android performance hints

On API 31 and later, the render executor requests a public `PerformanceHintManager`
session for its own OS thread. Session creation, target updates, work reports and closure
all run on that executor. The target is the current display period; actual work is measured
with a monotonic clock around each native render cycle and displayed-viewport extraction.
This is elapsed render-thread work, including driver waits inside the native call. It is
not GPU timestamp timing, presentation latency or the Rust solver's duration. Executor-queue
idle time and hint-session setup are excluded. No clock frequency or affinity is forced.

A missing service, unsupported session or runtime API error falls back to ordinary rendering.
An error is not retried on every frame. Pause, surface/session replacement, teardown, a failed
frame or one second without rendered work closes the session; subsequent work can create a
new one. A replacement executor thread also gets a fresh session. Hint sessions never cause
an otherwise idle stage to render extra frames.

The raw scene summary and `SolarLabPerformance` state logs distinguish `requested`,
`implemented`, and `state=active`, with accepted report count, target nanoseconds and the
last measured work duration. Active means a hint session exists and is being used; it does
not claim that the OS raised performance, selected a particular core, or improved frame rate.
Sustained benefit, power and thermal behaviour require device measurements.

The wrapper follows the public Android contracts for
[PerformanceHintManager](https://developer.android.com/reference/android/os/PerformanceHintManager),
[its session](https://developer.android.com/reference/android/os/PerformanceHintManager.Session),
and the [native-game integration guide](https://developer.android.com/codelabs/adaptability-codelab).
`RenderPerformanceHintsTest` checks measured durations, display-target changes, unsupported
fallback, API failures, closure/resume and executor-thread replacement with a fake backend.

The pure native geometry test can run without an Android SDK:

```sh
g++ -std=c++20 -Wall -Wextra -Werror render/android-vulkan/src/test/cpp/SolarLabPresentationTest.cpp -o /tmp/solarlab-presentation-test
/tmp/solarlab-presentation-test
python render/android-vulkan/src/test/python/test_surface_frame.py
```

The surface-frame regression reads the billboard shaders' axis signs and checks known
world-space surface points and lighting through camera rotations, including tilted poles.
`PresentationFrameGateTest` covers coalescing, pending interpolation, and inactive surfaces.
The Android build must also compile the shaders and JNI changes. Native screenshots and a
device walkthrough remain necessary to accept appearance, touch, rotation, and playback.
