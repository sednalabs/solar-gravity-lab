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

## Sandbox unification migration map

The stage-first Android shell currently has two user-facing workbench modes:

- Sandbox remains the editable workbench and currently creates its local session
  through `StageFirstSandboxLocalExperience` and `LabSession.createDefault(...)`.
- Runtime mirror is the Rust-owned path and sends controls through
  `RuntimeFacade` / `RuntimeCommand` into the opaque native session handle.

The unification target is one Rust-owned world for both modes. Sandbox should
stay the editing surface, but its mutable world operations must become commands
against the same runtime session family as the mirror.

| User operation | Current owner | Target contract |
| --- | --- | --- |
| Focus body | Sandbox local selection or runtime `FocusBody` command | One command vocabulary; UI selection mirrors runtime focus state |
| Reset/frame camera | Android render host state | Non-authoritative camera state can stay client-owned, but it must never imply a world mutation |
| Add staged body | Sandbox `LabSession` local authoring | Rust command creates the body with explicit `mass_kg` and `source_mass_kg` |
| Edit body properties | Sandbox `LabSession` local mutation | Rust command updates body state and returns a new snapshot/revision |
| Delete body | Sandbox `LabSession` local mutation | Rust `RemoveBody` command records revision, provenance, and history effects |
| Enter runtime mirror | Android mode switch | Mode switch reuses or opens the same authoritative world session |

The next implementation slice should therefore route one Sandbox authoring
operation through a Rust command while preserving the current stage-first
editing ergonomics. A good first candidate is delete or property edit: either
is smaller than full staged placement, but still proves the shell is no longer
the owner of mutable body truth for that operation.

## Why camera-relative packing matters

Raw AU-scale world coordinates should not be pushed directly into the GPU as
floats. Camera-relative or scene-relative packing is required to preserve
precision and stable motion at large scales.
