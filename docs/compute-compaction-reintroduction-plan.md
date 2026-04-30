# Compute compaction reintroduction plan

This is the current decision document for medium/far compute-compaction.

## Current status

Re-entered in the native renderer as 3D camera-space compute compaction for
medium/far tracer streams.

The older compaction direction should be read as historical and XY-native. It is
not the current implementation path on canonical `main`.

## Why the old path stayed paused

The earlier path assumed a flatter worldview in exactly the places that now
matter most:

- medium/far tracer state
- culling assumptions
- shader worldview
- compute kernel worldview

Reintroducing it unchanged would risk reintroducing hidden flattening underneath
a newer 3D camera and renderer direction.

## Re-entry decision

The project chose the first safe option: bring compaction back only after moving
it into the 3D orbit-camera basis and keeping it on the renderer side of the
world/scene/packet boundary.

## Continuing criteria

Any future expansion of compute-compaction must keep all of the following true:

- state is truly 3D (`vec3`, not `vec2`)
- culling is camera/frustum aware
- camera-relative precision is preserved
- renderer truth is not compromised
- authoritative world truth and source-mass semantics stay in the Rust runtime
- the performance win is measured on real tracer-heavy scenes

## Recommended next order

1. benchmark the current 3D compaction path against direct draw on real
   tracer-heavy scenes
2. measure visual stability while orbiting and zooming through dense belts
3. decide whether density aggregation, tile compaction, or direct draw is the
   right next step for each scale band
4. keep compute-driven integration separate until it has its own accuracy
   policy and scalar-oracle proof
