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
The Vulkan stage turns those immutable facts into procedural surfaces,
atmosphere limbs, analytically occluded ring planes, and comet coma/dust/ion
tails. Visual scale can expand a billboard to contain those effects, but it
never feeds positions, masses, velocities, radii, forces, or integration state
back into the runtime. The native scene summary reports `FX=[R,At,C]` counts so
interactive acceptance can prove that ring, atmosphere, and comet facts reached
the GPU scene.

The forward Android build maps this directory to the Gradle project
`:android-vulkan-renderer`. Keep JNI package names, consumer R8 rules, shader
asset paths, and CMake inputs synchronized when moving bridge entry points.
