# SolarLab v7 native Vulkan stream handoff

This pass does not pretend to finish Vulkan graphics pipelines. It pushes the native side from “swapchain bootstrap that clears the frame” to a renderer that genuinely **consumes** the packed scene as five separate native streams:

- authoritative bodies
- near tracers
- medium tracers
- far tracers
- trails

## What changed

### 1. Native scene upload now happens per stream

`SolarLabVulkanRenderer` now builds and uploads distinct host-visible Vulkan buffers for:

- `authoritative` → `BillboardVertex`
- `tracer-near` → `BillboardVertex`
- `tracer-medium` → `CheapPointVertex`
- `tracer-far` → `DensityPointVertex`
- `trails` → `TrailVertex`

Uploads are keyed off `sourceRevision`, so camera changes no longer imply native buffer rebuilds.

### 2. Medium/far tracers were moved to cheaper native paths

The native representations now intentionally degrade by tier:

- near tracers keep the richer billboard-style vertex payload
- medium tracers drop Z/kind and use a compact XY + color + point-size format
- far tracers drop to an even cheaper XY + color + density-weight format

That keeps the expensive path concentrated on the visually important subset.

### 3. Tracer buffers are already compute-friendly

The tracer buffers are created with `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT` in addition to `VK_BUFFER_USAGE_VERTEX_BUFFER_BIT`.

This is deliberate preparation for the next stage:

- compute-driven tracer compaction
- compute-driven tracer integration

But this pass stops *before* introducing compute, as requested.

### 4. Command recording now binds the streams separately

The Vulkan command path now binds the five streams to distinct vertex bindings (0 through 4). There are still no finished graphics pipelines in-tree, but the command buffers now reflect the intended split between authoritative / near / medium / far / trails instead of treating the whole scene as a single undifferentiated blob.

### 5. Native diagnostics are now queryable from Kotlin

The bridge now exposes a native scene summary string. The Kotlin Vulkan surface uses that summary in its backend status text so the UI reports what the native side actually uploaded.

## Current state

This is now a good handoff point for unrestricted agents to do the real Vulkan pipeline work.

The next recommended order is:

1. authoritative + near billboard graphics pipeline
2. medium cheap-point pipeline
3. far density-point pipeline
4. trail line pipeline
5. only then compute-driven tracer compaction or integration

## Important limitation

No SPIR-V shader modules are bundled in this pass, so the renderer still cannot issue real Vulkan draw calls yet. The hard native-side stream architecture and upload/caching work is in place; the graphics pipelines are the next step.
