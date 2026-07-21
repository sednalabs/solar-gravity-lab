# Render adapters

This directory holds renderer-specific adapters for Solar Gravity Lab v2.

Each adapter consumes the shared `solarlab-scene::RenderScene` contract and
produces backend-facing packets or streams for one graphics API. Backends stay
out of the authoritative runtime and scene-extraction crates so scientific
truth, scene extraction, and GPU implementation can evolve independently.

- `vulkan-adapter/` converts the Rust scene into the stable Vulkan-facing packet
  contract.
- `android-vulkan/` owns the Android library, Kotlin host, JNI bridge, C++/NDK
  renderer, and packaged GLSL shaders used by the production Android client.

The Android application includes the latter as `:android-vulkan-renderer`.
The retained root `feature-lab` module is not a renderer dependency and does
not own a second native stage.
