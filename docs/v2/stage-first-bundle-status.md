# Steps 1-7 ready bundle status

This bundle is the single cumulative handoff for the stage-first restoration track.

What is included in code:

- Step 1: stage-first client restoration
- Step 2: sandbox authoring parity
- Step 3: runtime mirror on the immersive client
- Step 4: multiscale orthographic orbit camera and 3D immersive pipeline foundations
- Step 5: native runtime stage path (Rust session -> native controller -> Vulkan renderer)
- Step 6: renderer completion pass (3D camera-basis compute compaction, pipeline cache, shader optimization, visual polish pass)

What is included as docs / execution plan rather than merged code:

- Step 7: NativeSimulationWorld unification

That is deliberate. Step 7 is the architectural merge where Sandbox and Runtime should both talk to one long-lived native world handle. It is the first slice that most strongly benefits from a real Android/NDK assemble and runtime verification.

Use this bundle as the first apply target when the offline Android build environment is available.
