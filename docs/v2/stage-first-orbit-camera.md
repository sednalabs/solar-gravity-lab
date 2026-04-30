Stage-first restoration — slice 4: multiscale orbit camera + 3D immersive pipeline

Historical note: this slice intentionally disabled the older XY-native
medium/far compute-compaction path. Later renderer work restored compute
compaction through the 3D orbit-camera basis rather than reusing the old
flattened path unchanged.

This slice is the first real camera/render migration away from the overhead XY contract.
It keeps the restored stage-first client and the runtime mirror from slices 1–3, but replaces the
camera math that was still flattening the immersive client.

What changed

1. Orbit camera state now lives in render-core.
   `CameraState` now carries yaw + pitch in addition to center + view radius, and `OrbitCameraMath`
   provides a shared orthographic orbit frame for screen projection, camera rays, and scene-origin
   quantisation.

2. Interaction is now 3D-aware.
   The immersive Vulkan surface still supports one-finger pan and pinch zoom, but two-finger drags
   now orbit/tilt the camera. Screen-to-world placement no longer hardcodes `z = 0`; it intersects
   a ray with the selected placement plane.

3. Scene packet culling is no longer XY-only.
   `NativeScenePacket` now classifies tracers/trails in camera space using right/up/forward axes and
   packs positions relative to a quantised scene origin for better float precision on the GPU.

4. The native renderer now accepts a real orbit camera.
   The JNI bridge forwards scene origin + yaw/pitch. The native uniform block now carries
   camera-relative center, right/up/forward basis vectors, half spans, and half depth.

5. The main graphics shaders now project full XYZ data.
   Billboard, cheap-point, density-point, and trail vertex shaders now transform camera-relative
   XYZ positions instead of reading only `xy`.

6. Depth testing is now enabled for the immersive Vulkan path.
   A depth attachment is created for the swapchain, the render pass/framebuffers include it, and the
   graphics pipelines now depth-test. Trails depth-test but do not write depth.

7. Multiscale camera policy is now scale-aware.
   The stage-first immersive client derives packet budgets from the current camera scale band
   (Close / Local / System / Wide / Deep), so the tracer/trail mix changes with zoom instead of
   acting like one fixed overhead mode.

Intentional trade-off in this slice

The old medium/far compute-compaction path is still based on the previous XY-native structs and
compute shaders. Rather than pretending it was 3D-ready, this slice disables compute compaction and
uses direct draw buffers for medium/far streams. The next native bite can port those compute shaders
cleanly into the new 3D camera basis.

What still remained after slice 4

- richer camera controls / presets for jumping directly between scale bands
- 3D compute compaction for medium/far tracer streams
- more deliberate trail layering / stylistic polish once the new 3D baseline is smoke-tested
- optional perspective mode; this slice stays orthographic by design
