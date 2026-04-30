# SolarLab v7 native Vulkan stream handoff

> **Historical milestone: native-stream split.**
>
> This document is still useful as the explanation of the five-stream native
> split, but it should now be read as a historical renderer milestone rather
> than the canonical current-state architecture note.

This pass does not pretend to finish Vulkan graphics pipelines. It pushes the
native side from “swapchain bootstrap that clears the frame” to a renderer that
genuinely **consumes** the packed scene as five separate native streams:

- authoritative bodies
- near tracers
- medium tracers
- far tracers
- trails

## What changed

### 1. Native scene upload now happens per stream

`SolarLabVulkanRenderer` now builds and uploads distinct host-visible Vulkan
buffers for:

- `authoritative` -> `BillboardVertex`
- `tracer-near` -> `BillboardVertex`
- `tracer-medium` -> `CheapPointVertex`
- `tracer-far` -> `DensityPointVertex`
- `trails` -> `TrailVertex`

Uploads are keyed off `sourceRevision`, so camera changes no longer imply native
buffer rebuilds.

### 2. Medium/far tracers were moved to cheaper native paths

The native representations now intentionally degrade by tier:

- near tracers keep the richer billboard-style vertex payload
- medium tracers drop Z/kind and use a compact XY + color + point-size format
- far tracers drop to an even cheaper XY + color + density-weight format

That kept the expensive path concentrated on the visually important subset in the
older renderer worldview.

### 3. Tracer buffers are already compute-friendly

The tracer buffers are created with `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT` in
addition to `VK_BUFFER_USAGE_VERTEX_BUFFER_BIT`.

This was deliberate preparation for:

- compute-driven tracer compaction
- compute-driven tracer integration

That preparation still matters. The old XY-native compaction path is historical;
the current renderer compaction path re-entered through a 3D camera-space
contract instead of reviving these older structs unchanged.

### 4. Command recording now binds the streams separately

The Vulkan command path now binds the five streams to distinct vertex bindings
(0 through 4). There are still no finished graphics pipelines in-tree, but the
command buffers now reflect the intended split between authoritative / near /
medium / far / trails instead of treating the whole scene as a single
undifferentiated blob.

### 5. Native diagnostics are now queryable from Kotlin

The bridge now exposes a native scene summary string. The Kotlin Vulkan surface
uses that summary in its backend status text so the UI reports what the native
side actually uploaded.

## Current reading

This is still the right historical explanation of the five-stream native split.
For the current design target, pair it with:

- `rendering-architecture-current-state.md`
- `compute-compaction-reintroduction-plan.md`

Those docs explain how this stream split fits into the current 3D camera and
compute-compaction framing.
