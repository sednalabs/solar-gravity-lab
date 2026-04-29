# Stage-first restoration (second recovery slice)

This slice restores sandbox authoring parity on top of the stage-first Android client.

## What this adds

The restored immersive client now supports the core sandbox authoring loop again:

- add a new object from the stage-first HUD
- choose whether to add immediately from numeric coordinates or stage placement
  first
- stage a ghost preview with a tap
- drag during placement to refine the staged position and seed initial velocity
- adjust staged mass, radius, position, velocity, role, category, and colour
  before committing
- commit or cancel the staged object explicitly
- edit the currently selected body from the immersive client
- delete the currently selected body from the immersive client
- preserve host linkage when editing an existing catalog-backed body

## Interaction behavior

When you open the body editor, the simulation pauses if it was running. If you choose staged placement, the stage enters placement mode and stays paused until you either:

- commit the staged object, or
- cancel the staged placement

The first tap or drag does **not** mutate the simulation. It creates a visual-only ghost body and a proximity-scaled forecast path. A live drag preview is visible while the gesture is moving, but the object only becomes committable after the gesture ends. The ghost body and forecast path stay visible even when trace layers are off so placement never becomes a blind commit. You can tap or drag again to reposition, open `Adjust` for exact initial conditions, or use `Commit object` when the placement is ready.

That preserves the alpha.9-style authoring flow while making placement feel deliberate instead of frantic: preview first, refine, then commit.

## Implementation notes

- `StageFirstSandboxApp` now owns staged authoring state, modal interruption handling, and placement commit/cancel behavior.
- `EditableBodyDraft` was added to `clients/android/app` as the stage-first authoring model.
- `BodyPlacementSession` keeps a stable draft body id, staged position, staged velocity, and commit readiness while the preview is active. Its saveable state uses a versioned key map rather than an index-coupled list so future fields can be added without silently mis-restoring older drafts.
- The render host accepts a visual-only placement preview so ghost bodies and forecast paths do not alter the `LabSession`, body count, invariants, checkpoints, collisions, or trail history.
- Forecast paths use denser samples over a proximity-scaled near-term horizon: local placements near massive bodies use a short fraction of the estimated local orbit period, while wide system-scale placements are capped to avoid implying long-term precision from a static-attractor preview.
- The body editor is implemented as a Compose dialog so the restored client stays self-contained inside `clients/android/app`.
- Existing `hostBodyId` is preserved during edits even though the current authoring dialog does not yet expose host reassignment.

## Still intentionally deferred

This restores the body-authoring workflow, but it is not the final visual overhaul yet.

Still to do:

- reconnect the stage-first renderer to the latest Rust-authoritative runtime stream
- replace the remaining overhead-first camera assumptions with a true multiscale orbit camera
- add a deeper universe context layer beyond the decorative starfield
- continue evolving placement planes so runtime-native authoring can use the same staged preview/commit model
