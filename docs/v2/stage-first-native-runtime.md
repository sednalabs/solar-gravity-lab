# Stage-first native runtime slice

This slice finishes the hot-path handoff from the managed packet host to the real native Vulkan stage for the restored immersive client.

What changed:

- The immersive `feature-lab` Vulkan surface now binds directly to the long-lived Rust runtime session handle (`SlRuntimeHandle`) instead of requiring Kotlin to decode `RenderFrame` objects for the stage draw path.
- A new native `SolarLabStageController` sits in front of `SolarLabVulkanRenderer`. It owns:
  - runtime session binding
  - native free-camera state
  - native picking
  - native tracer/trail classification and LOD
  - runtime packet export and translation into renderer streams
- `SolarSystemVulkanSurfaceView` now has two honest paths:
  - local sandbox path: Kotlin assembles a local scene packet and submits it
  - runtime path: Kotlin binds a Rust session handle and forwards gestures/selection to native; native exports packets from Rust and renders them directly
- `StageFirstRuntimeMirrorExperience` now renders the immersive stage as soon as a runtime session handle exists, even before the shell has decoded a `RenderFrame` for search/debug metadata.
- Debug builds now default to the stage-first client as well. The old packet/Canvas shell remains opt-in through `-Psolarlab.debugStageFirstClient=false`.

What is now native in the runtime stage:

- camera ownership for the immersive runtime path
- pan / zoom / orbit gesture application
- ray-style body picking against the native scene cache
- tracer LOD split (near / medium / far)
- trail simplification per camera band
- packet export from the Rust runtime handle

What stays in Kotlin:

- Compose HUD
- search / debug sheets
- command buttons
- sandbox authoring UI
- optional decoded packet metadata used for search, labels, and diagnostics

Important note:

The Android shell still decodes runtime packet metadata for UI/search/debug when available, but the actual stage draw path no longer depends on that decoded frame. The immersive runtime view now runs Rust session -> native Vulkan stage directly.
