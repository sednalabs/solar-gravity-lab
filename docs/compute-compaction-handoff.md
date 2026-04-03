# SolarLab compute compaction handoff

This pass adds native Vulkan compute on top of the existing graphics/descriptor/buffer path.

What is now implemented:

- SPIR-V compute shaders for medium and far tracer compaction.
- Compute descriptor path with bindings:
  - set 0 / binding 0: scene uniform buffer
  - set 0 / binding 1: source tracer buffer (storage)
  - set 0 / binding 2: compacted output tracer buffer (storage + vertex)
  - set 0 / binding 3: indirect draw command buffer (storage + indirect)
- `vkCmdUpdateBuffer` reset of medium/far `VkDrawIndirectCommand` each frame.
- Compute dispatch before render pass.
- Compute-to-graphics barrier before `vkCmdDrawIndirect` / vertex fetch.
- Graphics now draws medium/far with indirect draws when compute compaction is enabled.

Current behavior:

- Medium tracers are viewport culled and lightly downsampled as zoom increases.
- Far tracers are viewport culled and more aggressively downsampled using density-weight-aware hashing.
- Authoritative bodies, near tracers, and trails remain on the direct graphics path.

Next strong passes:

1. Replace host-visible source/output buffers with device-local buffers plus staging uploads.
2. Add GPU-side readback or timestamp/stat instrumentation for visible compacted counts.
3. Introduce tile/bin compaction for far tracers instead of hash thinning.
4. Only after compaction is stable, start GPU integration for medium/far tracers using ping-pong state buffers and authoritative-body influence buffers.
