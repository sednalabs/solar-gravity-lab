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
- orchestration of the current packet-render host surface
- user controls, forms, navigation, and shell state

The Android shell must not become a second runtime or a shadow physics layer.

## Runtime seam

The shell talks to Rust through the v2 FFI layer. In practice this means:

- session handles are opaque capabilities
- refresh / command / snapshot / export are explicit calls
- exported Vulkan packets are borrowed native resources and must be released
- the shell should prefer thin transport / presentation logic over reshaping
  runtime semantics in Kotlin

## Render host seam

Today the live rendering path is still a packet-host seam over the Rust runtime.
That is intentionally real, but it is also intentionally transitional.

The current architecture should be read as:

- **Kotlin** = shell / control plane
- **render-core style policy** = camera / projection / packet shaping direction
- **native Vulkan** = actual renderer seam
- **Rust** = longer-term authoritative world ownership target

## Known renderer gap / forward direction

Future agents should **not** treat the current packet-host behavior as the
end-state architecture.

The forward direction is:

- Rust-first authoritative runtime truth
- C++-thin Vulkan/NDK seam
- Kotlin shell over that runtime
- renderer logic that preserves a 3D camera / packet / picking worldview
  instead of reintroducing older flat assumptions

The most important current gap is not “make the physics 3D” — the physics/data
spine is already 3D. The real open work is camera/render/interaction/compute
migration around the world -> scene -> packet -> native -> Vulkan boundary.

## Native ownership direction

The strongest long-term split for this client is:

- Kotlin keeps shell/UI/lifecycle/search/forms/debug surfaces
- Rust owns the authoritative world, commands, checkpoints, and scene extraction
- C++ stays thin and owns Vulkan/NDK concerns: swapchain, buffers, shader
  pipelines, compute compaction, and frame pacing

That is the direction that should guide renderer and performance work on this
client.
