# Android client shell

The Android client is a native shell over the Rust runtime.

Design constraints:

- package namespace: `com.sednalabs.solarlab`
- Jetpack Compose UI
- unidirectional app state
- no authoritative simulation logic in the Android layer
- Rust runtime accessed through the `engine/ffi` ABI
- render host implemented as an Android adapter over the shared `RenderScene`
  / exported packet surface

## Current responsibilities

The Android shell owns:

- lifecycle and process integration
- session creation / refresh / command application
- presentation of runtime summaries and diagnostics
- orchestration of the native Rust-bound Vulkan stage and camera
- direct selection, map/3D navigation, staged orbit and velocity placement,
  probe mass/gravity editing, saved experiment export/import, and shell state

The Android shell must not become a second runtime or a shadow physics layer.

## Runtime seam

The shell talks to Rust through the v2 FFI layer. In practice this means:

- session handles are opaque capabilities
- refresh / command / snapshot / export are explicit calls
- exported Vulkan packets are borrowed native resources and must be released
- spawn-body transport carries both display/inertial mass and gravitational
  source mass so staged probes can remain non-perturbing unless the user makes
  them massive
- the shell should prefer thin transport / presentation logic over reshaping
  runtime semantics in Kotlin

Temporary authoring previews may calculate local trajectory affordances, but
they must use the runtime's source-mass semantics and must not become a second
production physics authority.

## Render host seam

The live rendering path is a packet-host seam over the Rust runtime. The
production host, JNI bridge, C++ Vulkan implementation, and shaders live in the
canonical `render/android-vulkan/` library, mapped into this build as
`:android-vulkan-renderer`.

The current architecture should be read as:

- **Kotlin** = shell / control plane
- **render-core policy** = transitional camera / projection / packet shaping
- **render/android-vulkan** = Android host, JNI, native Vulkan, and shaders
- **Rust** = authoritative runtime truth

## Implemented laboratory and current limits

The installed client uses the native Vulkan stage with a 3D camera, an
Earth-relative orbit map, reliable zoom/pan and visible selected-object
markers. Placement is staged against an explicit plane, with validated
preview and separate Create/Cancel; the UI supports initial velocity and
probe mass. **Attracts other bodies** switches gravitational source mass
on intentionally; display mass alone does not change planetary motion.

Saved experiments retain native world identity and history. On the current
`main` source, `.sglexp` documents can be exported through Android's
Storage Access Framework or imported as a validated independent copy.
The live experiment is not replaced by an import. The user may remove a
saved copy with confirmation.

Evidence: [PR #237 native validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943).
That is hosted Android acceptance of source, **not** the public alpha.2 APK
and not real Galaxy S25 thermal, power or gesture acceptance. Signed
live-data distribution, preview signing continuity and unimplemented
physics effects are still separate work. See
[documentation index](../../docs/README.md) and
[release channels](../../docs/release-channels.md).

## Native ownership direction

The strongest long-term split for this client is:

- Kotlin keeps shell/UI/lifecycle/search/forms/debug surfaces
- Rust owns the authoritative world, commands, checkpoints, and scene extraction
- C++ stays thin and owns Vulkan/NDK concerns: swapchain, buffers, shader
  pipelines, compute compaction, and frame pacing

That is the direction that should guide renderer and performance work on this
client.
