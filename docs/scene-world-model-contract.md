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

Celestial appearance is also authored before the renderer boundary. Rust data
owns a typed, renderer-neutral contract containing material family, honest
provenance, reference orientation, and optional ring, atmosphere, and comet
facts. The scene projection may derive presentation-only vectors from world
state, such as a comet's anti-solar and velocity directions.

These fields are one-way outputs. They cannot change mass, gravitational source
mass, integration, collision policy, forces, ephemerides, or stored body
kinematics. A render extraction regression test asserts that producing a scene
does not mutate the authoritative body state.

### Packet truth

`NativeScenePacket` is the backend-ready, camera-aware packet built from a scene
frame.

The stable appearance contract is packed into each Vulkan body instance at ABI
version 12. Optional feature flags distinguish absent rings, atmospheres, and
comet effects from zero-valued inputs; clients must use the packet stride and
decode the typed material and provenance codes.

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
