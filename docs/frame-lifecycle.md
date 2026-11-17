# End-to-end frame lifecycle

This is the operational debugging map for a native Android frame.
The pipeline is implemented and qualified at source; simulation ticks and
display-only interpolation can be scheduled separately, and pausing does not
necessarily stop all scene presentation. Physical S25 frame-time acceptance
remains separate.

```text
Tick (world) -> RenderSceneFrame -> Camera/Projection -> NativeScenePacket -> Native Upload -> Vulkan Draw -> Present
```

## 1. Tick / world step

Owner: Rust runtime.

Output: updated 3D body state, trails, epoch, provenance.

## 2. Scene-frame assembly

Owner: scene extraction / translation layer.

Output: `RenderSceneFrame`.

## 3. Camera / projection

Owner: camera and packet-shaping layer.

Output: camera basis, spans, depth mapping, view-relative transforms.

## 4. Packet shaping

Owner: `NativeScenePacket` builder.

Output: camera-relative stream-specialized arrays.

## 5. Native upload

Owner: JNI/C++ bridge + Vulkan buffer upload path.

Output: stream buffers resident for draw.

## 6. Vulkan draw

Owner: native renderer.

Output: depth-tested authoritative/near/medium/far/trail streams, with
3D medium/far compute culling and counter/readback dependencies where active.
The GPU compaction path only filters visual output; it does not integrate bodies.
Native tests check projected pixels and resource reuse, not just dispatch counts.

## 7. Present

Owner: Android/Vulkan swapchain.

## Debugging rule

When something looks wrong, find which boundary is lying:

1. Is it wrong in the world?
2. Is it missing or wrong in the scene frame?
3. Does projection place it incorrectly?
4. Was it packet-shaped incorrectly?
5. Was native upload skipped or wrong?
6. Is the Vulkan pipeline/shader wrong?

This is the fastest way to localize renderer bugs on this branch.
