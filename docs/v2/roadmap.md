# Solar Gravity Lab v2 Roadmap

## Phase 1: durable foundations

- ADRs for the major architectural commitments
- protobuf contracts
- Rust workspace and crate boundaries
- stable FFI conventions
- hardware profile model

## Phase 2: authoritative runtime

- command model
- checkpoint and replay model
- world runtime
- deterministic diagnostics
- offline-first data loading

## Phase 3: first native client

- Android Compose shell
- runtime handle ownership
- renderer host adapter
- diagnostics and update surfaces

## Phase 4: current rendering migration

This is the live renderer-focused phase.

- stage-first restoration as the conceptual target for immersive rendering
- runtime mirror over the Rust runtime without surrendering renderer primacy
- orbit-camera and camera-relative packet design as the correct 3D direction
- explicit renderer boundary: `world -> RenderSceneFrame -> NativeScenePacket -> native streams -> Vulkan`
- replace older flat packet-host assumptions with a truthful 3D
  camera/render/interaction model

## Phase 5: compaction and heavy-scene scaling

This phase has begun through the native renderer's 3D camera-space compaction
path. The gate remains closed for older XY-native assumptions and for any
compute path that would move scientific truth out of the runtime.

- benchmark tracer-heavy scenes on the restored 3D renderer compaction path
- decide whether medium/far density aggregation or tile compaction is worth the
  added complexity
- preserve the 3D camera-space compaction model rather than reviving the older
  XY-native path
- continue native stream/pipeline specialization only when it preserves the new
  rendering contract

## Phase 6: deeper native ownership and performance

- move further toward Rust-first authoritative world ownership
- reduce managed/native ping-pong in live shells
- deepen worker, SIMD, and diagnostics/checkpoint structure where justified
- revisit heavier hardware fast paths only after the renderer contract is stable

## Phase 7: live updates and additional clients

- signed update manifest service
- background content refresh
- desktop shell
- more backend adapters if justified
