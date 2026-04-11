# Stage-first restoration (second recovery slice)

This slice restores sandbox authoring parity on top of the stage-first Android client.

## What this adds

The restored immersive client now supports the core sandbox authoring loop again:

- add a new object from the stage-first HUD
- choose whether to add immediately or place directly on the scene
- place a new object with a tap
- drag during placement to seed initial velocity
- edit the currently selected body from the immersive client
- delete the currently selected body from the immersive client
- preserve host linkage when editing an existing catalog-backed body

## Interaction behavior

When you open the body editor, the simulation pauses if it was running. If you choose `Place on scene after save`, the stage enters placement mode and stays paused until you either:

- place the new object, or
- cancel the pending placement

That preserves the alpha.9-style authoring flow and makes placement feel deliberate instead of frantic.

## Implementation notes

- `StageFirstSandboxApp` now owns pending authoring state, modal interruption handling, and placement completion.
- `EditableBodyDraft` was added to `clients/android/app` as the stage-first authoring model.
- The body editor is implemented as a Compose dialog so the restored client stays self-contained inside `clients/android/app`.
- Existing `hostBodyId` is preserved during edits even though the current authoring dialog does not yet expose host reassignment.

## Still intentionally deferred

This restores the body-authoring workflow, but it is not the final visual overhaul yet.

Still to do:

- reconnect the stage-first renderer to the latest Rust-authoritative runtime stream
- replace the remaining overhead-first camera assumptions with a true multiscale orbit camera
- add a deeper universe context layer beyond the decorative starfield
- rework the renderer so placement and editing eventually happen in a real 3D camera model rather than a flattened stage projection
