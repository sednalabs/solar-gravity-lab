# SolarLab v8 native Vulkan graphics pipelines handoff

This pass moves the native renderer from "tiered buffers only" to real SPIR-V-backed Vulkan graphics pipelines.

## What is now implemented

- AOT shader source lives under `feature-lab/src/main/shaders/solarlab/`.
- Android Gradle / Shaderc compiles those shaders to SPIR-V and packages them into `assets/shaders/solarlab/*.spv`.
- The native bridge now receives an `AAssetManager` at renderer creation time.
- The native renderer loads compiled SPIR-V shader assets at runtime and creates four graphics pipelines in this order:
  1. billboard pipeline for authoritative bodies
  2. billboard pipeline reuse for near tracers
  3. cheap-point pipeline for medium tracers
  4. density-point pipeline for far tracers
  5. line-strip pipeline for trails
- Camera updates now flow through a uniform buffer, so camera motion no longer forces command-buffer rerecords.
- Trail strips are drawn as separate `vkCmdDraw(... firstVertex ...)` calls over a shared trail vertex buffer.

## Why the asset-manager step matters

The native side cannot assume precompiled shader binaries are available as raw filesystem files inside the APK. The renderer therefore loads the compiled SPIR-V modules through the Android asset manager using paths like:

- `shaders/solarlab/billboard.vert.spv`
- `shaders/solarlab/billboard.frag.spv`
- `shaders/solarlab/cheap_point.vert.spv`
- `shaders/solarlab/cheap_point.frag.spv`
- `shaders/solarlab/density_point.vert.spv`
- `shaders/solarlab/density_point.frag.spv`
- `shaders/solarlab/trail.vert.spv`
- `shaders/solarlab/trail.frag.spv`

## What is still intentionally next, not already done

Only after the graphics pipelines are stable should more native work land.

The next best tasks are:

1. Move far-tracer density rendering toward true aggregate / tile compaction instead of one-vertex-per-tracer drawing.
2. Introduce a compute descriptor layout for tracer storage buffers.
3. Add compute-driven compaction for medium/far tracer visibility buckets.
4. Only after compaction is correct, experiment with compute-driven tracer integration.

## Caution points for follow-on agents

- If you change shader input locations, update the Vulkan vertex input attribute descriptions in lockstep.
- If you add descriptor bindings, update both the shader set/binding declarations and the native descriptor set layout.
- If you replace point sprites with instanced quads, keep the authoritative / near pipeline order intact and preserve the current stream contracts unless you also update `NativeScenePacket`.
- Camera transforms are currently top-down XY orthographic with Vulkan Y-flip performed in shader space.
