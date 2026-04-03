# SolarLab v6 hybrid packet handoff

This pass does not pretend to finish Vulkan drawing. It pushes the hard CPU-side render-prep work further so unrestricted agents can spend their time on native pipelines instead of scene wrangling.

## What changed

- `RenderSceneFrame` now carries a monotonically increasing `sourceRevision` from `RenderSceneAssembler`.
- `render-core` now owns `ScenePacketBuildPolicy` and tracer LOD tiering (`NEAR`, `MEDIUM`, `FAR`).
- `NativeScenePacket.fromScene(...)` is now camera-aware and viewport-aware.
- Tracers are deterministically budgeted into three LOD buckets before they cross JNI.
- Trails are simplified in view space before they cross JNI.
- The Vulkan surface view now caches packet build state and only rebuilds when the scene or camera changes.
- JNI/native signatures were extended so the native side receives the three tracer tiers and `sourceRevision`.

## Why this matters

This is the first real hybrid-rendering step beyond bootstrap. The Kotlin side now does:

- deterministic LOD selection
- deterministic tracer downsampling under budget pressure
- trail simplification
- scene revision tracking

That means the native side can focus on:

1. authoritative-body pipeline
2. near-tracer pipeline
3. medium/far-tracer cheaper pipelines
4. later compute-driven tracer compaction or integration

## Recommended native follow-up

- Consume `tracerNear*`, `tracerMedium*`, and `tracerFar*` as separate draw streams.
- Keep near tracers as the highest-quality sprite/instance path.
- Collapse medium/far tracers into progressively cheaper point/sprite paths.
- Use `sourceRevision` to avoid redundant native uploads when only camera matrices change.
