# Scene / world model contract

This document defines the contract between world truth, scene truth, packet
truth, and renderer truth.

## Layer model

### World truth

Authoritative simulation state.

Body mass semantics live here, not in renderer labels or visual classes:

- `mass_kg` is display/inertial mass.
- `source_mass_kg` is gravitational source mass.

A body can be visible, selectable, dense-looking, and pedagogically "massive"
while still carrying `source_mass_kg = 0` so it behaves as a probe/tracer that
responds to the canonical system without perturbing it.

### Scene truth

`RenderSceneFrame` is the renderer-facing structured view of that world.

### Packet truth

`NativeScenePacket` is the backend-ready, camera-aware packet built from a scene
frame.

### Renderer truth

Native streams + Vulkan draw path.

## Canonical invariants

- The world stays 3D end to end.
- Scene frames must not silently flatten truth.
- Packets must be camera-relative / scene-relative before GPU projection.
- Selection and picking must agree with rendered reality.
- LOD / tiering must be camera-space aware.
- Performance work must not quietly change world-model semantics.

## Why camera-relative packing matters

Raw AU-scale world coordinates should not be pushed directly into the GPU as
floats. Camera-relative or scene-relative packing is required to preserve
precision and stable motion at large scales.
