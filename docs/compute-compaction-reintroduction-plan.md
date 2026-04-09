# Compute compaction reintroduction plan

This is the current decision document for medium/far compute-compaction.

## Current status

Paused.

The older compaction direction should be read as historical and XY-native. It is
not the automatic next implementation step on canonical `main`.

## Why it is paused

The earlier path assumed a flatter worldview in exactly the places that now
matter most:

- medium/far tracer state
- culling assumptions
- shader worldview
- compute kernel worldview

Reintroducing it unchanged would risk reintroducing hidden flattening underneath
a newer 3D camera and renderer direction.

## Re-entry options

1. bring compaction back in a fully 3D form
2. redesign compaction around a cleaner camera-space LOD model
3. retire the old idea if direct draw + other optimizations are good enough

## Re-entry criteria

Only reintroduce compute-compaction if all of the following are true:

- state is truly 3D (`vec3`, not `vec2`)
- culling is camera/frustum aware
- camera-relative precision is preserved
- renderer truth is not compromised
- the performance win is measured on real tracer-heavy scenes

## Recommended order

1. benchmark the honest 3D renderer path first
2. build the smallest viable 3D compaction prototype
3. compare against direct draw on real scenes
4. decide whether to reintroduce, redesign, or retire the path
