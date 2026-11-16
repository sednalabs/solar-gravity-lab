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

The native stage shares its orthographic viewport scale with touch projection. Body picking
tests the front surface of the displayed sphere before considering padded touch targets;
the billboard fragment shader writes the same analytic sphere depth. The Earth–Moon
laboratory's Home frame includes Earth and Moon while keeping the Sun available for lighting.
Swapchain images stay in window coordinates and advertise an identity pre-transform;
Android performs display rotation. This matches the shader viewport and touch coordinates
without rotating and stretching a landscape buffer. Swapchain creation logs the requested,
surface and buffer extents plus both transforms so native rotation evidence can verify the
actual presentation contract, in addition to the pure projection formulas.
Free navigation in this laboratory travels with Earth's translation in non-rotating axes,
preserving the user's local pan, orientation and zoom during accelerated solar motion. The
Moon and probes retain their movement relative to Earth. Other scenarios keep their existing
free-camera behaviour.

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
