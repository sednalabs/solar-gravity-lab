# Rendering architecture (current state)

This is the current-state front-door document for the rendering stack on
canonical `main`.

## The most important truth

The physics/data spine is already 3D.

The current open migration problem is the render-side stack: camera,
projection, picking, packet shaping, native upload, and GPU-side optimization
must stop preserving older flat assumptions and instead preserve a truthful 3D
camera-relative worldview.

## Current boundary

```text
world -> RenderSceneFrame -> NativeScenePacket -> native streams -> Vulkan
```

This is the renderer boundary that matters most now.

## Layer ownership

### World / runtime truth

- Rust runtime remains the intended authoritative owner on canonical `main`
- scene extraction should be read as world-truth publication, not renderer truth

### Scene-frame layer

`RenderSceneFrame` is the renderer-facing structured view of world state. It is
where the project bridges from simulation truth into renderable state without
yet committing to a backend-specific representation.

### Packet layer

`NativeScenePacket` is the backend-ready, camera-aware packet seam. It is where
camera-relative packing, tiering, culling, and stream separation live.

### Native renderer layer

The native Vulkan side consumes stream-specialized packet data and is the place
where graphics pipelines, depth policy, upload discipline, and future compute
work belong.

## Canonical scene streams

The native-facing scene split is:

- authoritative bodies
- near tracers
- medium tracers
- far tracers
- trails

That stream split is structurally valuable and should be kept in mind even when
specific implementation details (like older XY-native compaction) are retired or
reworked.

## Current strategic tension

Canonical `main` still presents the Android path as a packet host over the Rust
runtime, but the richer design truth is:

- Kotlin = shell / control plane
- Rust = authoritative runtime truth
- native Vulkan = actual renderer seam
- packet shaping = camera-aware policy layer

Any future work that treats the packet-host state as the end-state architecture
will misread the branch.

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
