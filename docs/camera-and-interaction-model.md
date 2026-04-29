# Camera and interaction model

This document captures the current design target for camera, projection,
selection, and placement.

## Camera model

The project should be read as moving toward a real 3D camera contract rather
than a simple top-down center + radius packet host.

The intended camera model is:

- focus center in world space
- scalar zoom radius
- yaw
- pitch
- camera basis vectors (`right`, `up`, `forward`)
- scale-aware behavior from local to system views

An orthographic orbit camera is the preferred baseline because it preserves the
clean solar-system overview feel while making out-of-plane structure visible.

## Picking

Selection should be ray/sphere based, not just 2D screen-circle proximity.

If the user can see a body in the current camera, selection logic should agree
with that rendered reality.

## Placement

Placement should stop assuming `z = 0` as the only meaningful interaction
surface. The correct long-term direction is selectable placement planes:

- ecliptic plane
- camera-facing plane
- host orbital plane
- explicit numeric Z where needed

Object insertion should also be staged, not committed on first touch. The
expected flow is:

- define the object's physical and visual parameters
- stage a visual-only preview on the selected placement plane
- refine position and initial velocity through tap/drag gestures or exact
  numeric fields
- show a short forecast path that updates with the staged state
- keep placement previews visible even when ordinary trace layers are hidden
- keep live drag previews non-committable until the gesture finishes
- commit explicitly once the user accepts the initial conditions

The staged preview must not mutate simulation body count, invariants,
checkpoints, collision state, or trail history. It is a render aid until commit.

## Interaction principle

Camera, picking, and placement should all preserve the same worldview as the
renderer. A faster or simpler path that silently flattens interaction back into
XY is the wrong direction.
