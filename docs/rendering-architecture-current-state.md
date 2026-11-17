# Rendering architecture (current implementation)

This is the current-state front door for the native Android rendering stack
on `main`. The 3D camera/picking, perspective-independent orthographic
projection, camera-space packets, depth-tested Vulkan streams and 3D
medium/far compute compaction are **implemented** and passed
[hosted Android pixel/interaction checks](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943).
The source is newer than the published alpha.2 APK; those tests are **not**
physical Galaxy S25 frame-rate, thermal or battery measurements.

## Ownership and boundary

```text
Rust world -> RenderSceneFrame -> NativeScenePacket -> native streams -> Vulkan
```

- **World and extraction:** Rust owns double-precision physics, gravity source
  mass, history, provenance and renderer-neutral appearance, rings, atmosphere,
  comet and light facts.
- **Scene frame:** typed bodies, trails, source IDs and render-only facts
  projected from the runtime without mutating world state.
- **Packet:** camera-relative coordinates, stable struct strides, 3D camera
  basis, viewport-dependent LOD, native stream separation and provenance.
- **Native controller:** `SolarLabStageController` binds the Rust session,
  manages camera, ray/sphere picking, frame transitions, stream preparation
  and presentation-aligned viewport anchors.
- **Vulkan:** C++/GLSL own buffers, analytic sphere depth, physical-light
  shading, native command/pipeline reuse and GPU-side visibility compaction.
  They do not modify mass, integration or ephemeris state.

The current scene splits into authoritative bodies, near tracers, medium
tracers, far tracers and trails. Tracer bodies are not also drawn as
ordinary authoritative billboards: selected tracers are rendered once
through the authoritative path, and ordinary tracers through the tiered
streams. Spacecraft remain authoritative billboards because Rust does not
export them as `SceneTracer`. Picking proxies preserve independent
selectability.

## Camera, depth and interaction

The native stage uses an orthographic yaw/pitch orbit camera; map mode views
a declared physical authoring plane. One finger pans in map mode and orbits
in 3D. Two-finger movement pans and pinches around the gesture midpoint.
The native viewport's camera basis and submitted presentation epoch are
also used for picking and accessible overlay labels; no separate shell
projection may invent a different body location.

The [navigation contract](../render/android-vulkan/LAB_NAVIGATION.md) records
map/3D restoration, drag cancellation, camera-follow behavior and viewport
invalidations. The shell presents object labels, selection, mass editing
and staged probe placement without owning a second physical world.

## Native compute and resource lifecycle

The medium and far pipelines consume **3D** source coordinates with Z and VZ,
share a camera-uniform prefix with graphics, and cull/compact in camera space.
Far tiles use the Vulkan window-coordinate orientation. Counter readback and
transfer/compute/graphics dependencies are explicit. Native emulator tests
verify projected medium/far pixels and resource reuse across resizing; a
nonzero dispatch alone is not accepted as proof of visual correctness.

Vulkan allocations are retained where compatible and grow geometrically.
Frame invalidations coalesce; display-only interpolation can reuse scene
buffers instead of re-exporting the Rust world. These are source/hosted
behaviour claims, not measured physical-device performance gains.

## Limits and next work

The current shader colour, atmosphere and orientation are visual choices,
not real-time surface/weather data. Scene drawing is non-authoritative.
Future work is **measured S25 performance, usability and energy costs**,
possibly stronger far-density aggregation, stable preview updates and
additional backend capability. Earlier XY-native compaction plans are
historical and should not replace the current 3D packet contract.

## Earth–Moon laboratory display predictions

The unedited `lab.earth-moon` scene retains the normal 12-hour prediction window
with one sample per hour. Added bodies can require much closer display samples:
the default probe 800 km above Earth has an orbital period of about 6,043 seconds,
so one-hour samples would connect points more than half an orbit apart with
straight chords through the globe.

The laboratory's [display sampling policy](../engine/runtime/src/lab_prediction.rs)
estimates the shortest local timescale between an added body and a gravity source
using the circular gravitational period and relative motion. If the usual sample
gap would give fewer than 64 samples per local period, the scene instead requests
96 samples over the shorter of 12 hours or 1.5 local periods. For the default low
probe this gives approximately 94.43 seconds between points and a 9,065-second
window. The integrator still computes every point using its existing physics
policy; this changes only when predicted positions are retained for display.

All bodies share the shortened window while a close probe is present. This trades
away some displayed lunar prediction horizon to keep one bounded prediction pass
and exact Earth reference epochs for every probe point. The [trail frame helper](../engine/runtime/src/trail_frame.rs)
uses those simultaneous positions for the Earth-centred view. Output is bounded
to 96 prediction points per selected body and never extends the original time
window. Public archive queries, stored trajectory/checkpoint history, manual
advances, the physics model and other scenarios keep their existing behavior.
