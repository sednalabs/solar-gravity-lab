# Stage-first renderer completion (baby step 6)

This slice finishes the first real native renderer pass on top of the earlier stage-first / orbit-camera / native-runtime slices.

## What changed

The native Vulkan renderer now enables real SPIR-V compute compaction again for medium and far tracer streams, but this time against the 3D orbit-camera basis instead of the old flattened XY-only contract.

Concretely:

- `compact_medium.comp` and `compact_far.comp` were added back as camera-aware 3D compute shaders.
- The native renderer now creates the medium and far compute pipelines again instead of hard-disabling them.
- Medium/far compute culling now works in camera space using the stage-first orbit basis (`right`, `up`, `forward`, depth extent) and scene-origin-relative positions.
- Far thinning now includes a depth slice in the spatial hash so very deep views keep a more stable statistical distribution when orbiting around dense belts or clouds.
- The renderer now owns an in-process Vulkan pipeline cache so swapchain recreation does not have to cold-build every graphics and compute pipeline from scratch.
- Shader compilation is requested with optimization enabled from the Android build.
- Cheap-point and density-point fragments are now circular soft sprites instead of hard square points, billboard sprites have a stronger halo/core falloff, and trails get a small brightness pass.
- The stage-first Vulkan surface now requests a high refresh rate on resume when the platform supports it.

## Intentional limits

This is still an in-process pipeline cache, not a persisted disk cache. It improves warm restarts and surface recreation inside one app lifetime, but it does not yet serialize cache blobs across launches.

The compute path still remains non-authoritative rendering logic. It only compacts already-built scene packets; it does not move authoritative world truth onto the GPU.

## Why this slice matters

Earlier slices restored the beautiful client and made the stage native-owned again. This slice is the point where the native renderer stops feeling like “uploaded packets plus pretty shaders” and starts behaving like a real scalable draw pipeline again, especially in medium/far tracer-heavy views.
