# Vulkan Handoff Notes

This project now has a real Vulkan bootstrap path and a stable scene-packet contract. The quickest high-value follow-up work for unrestricted agents is:

1. **Finish the graphics pipeline**
   - create vertex + fragment shader modules in SPIR-V
   - build a graphics pipeline for authoritative bodies
   - build a line pipeline for trails or convert trails to camera-facing strips
   - consume the existing `NativeScenePacket`

2. **Move tracers to GPU rendering first**
   - keep authoritative bodies CPU-simulated
   - upload tracer positions/radii/colors as a dedicated storage / vertex buffer
   - draw them with instancing or a compact point/billboard path

3. **Then move tracer integration to compute**
   - treat the CPU-major-body state as the authoritative source each tick
   - upload the major-body state to Vulkan buffers each frame
   - run a compute pass that advances tracer positions/velocities from the major-body field
   - keep collision / capture policies explicit before changing semantics

4. **Add validation + diagnostics**
   - enable `VK_LAYER_KHRONOS_validation` in debug builds
   - log swapchain / pipeline init failures clearly back through the JNI bridge
   - add frame timing counters and backend stats to the UI

## Existing JNI contract

The Kotlin side already packs and submits:

- authoritative body positions / radii / colors / kinds
- tracer positions / radii / colors
- trail positions / colors / per-trail vertex counts
- camera center and view radius

The bridge entry points are in `SolarLabVulkanBridge.kt` and `SolarLabVulkanBridge.cpp`.

## Important architectural constraint

Do not make the Vulkan renderer reinterpret `SimulationSnapshot` directly. That translation work now belongs in `render-core`. Keep the native side consuming the already-packed scene contract so the CPU and GPU paths stay decoupled.


## v6 hybrid packet notes

The Kotlin side now builds a camera-aware `NativeScenePacket` before crossing JNI. That packet keeps:

- authoritative bodies unthinned
- tracers partitioned into deterministic `NEAR` / `MEDIUM` / `FAR` LOD tiers
- simplified trails with view-dependent thinning
- a `sourceRevision` so native code can reason about scene lifetime and caching

Next native steps should consume the three tracer tiers as separate draw streams and eventually map them to:

- near tracers: full sprite/instance path
- medium tracers: cheaper instanced point/sprite path
- far tracers: cheapest density/sprite path or compute-driven compaction


## v7 native-stream update

The native side now consumes the Kotlin `NativeScenePacket` into five distinct Vulkan-side buffers:

- authoritative billboards
- near billboards
- medium cheap points
- far density points
- trails

Those buffers are revision-cached and only rebuilt when `sourceRevision` changes. Medium/far tracers already use cheaper native formats, and tracer buffers now include `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT` so later compute work has a clean starting point.

The next unrestricted-agent step is to add real SPIR-V-backed graphics pipelines on top of these existing native streams.
