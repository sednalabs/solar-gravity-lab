# Compute compaction handoff

> **Historical snapshot: pre-3D-camera compaction path.**
>
> This document describes the earlier XY-native compaction direction.
> The current project direction should treat that path as **paused pending a 3D
> camera-space redesign** rather than as an automatically active next step.
> See [`docs/compute-compaction-reintroduction-plan.md`](compute-compaction-reintroduction-plan.md)
> for the current re-entry criteria and decision model.

## Historical context

This file exists because the project already did meaningful native stream
separation and early compaction-oriented design work. That work still matters.
What changed is the framing: once the renderer/camera migration became the live
problem, the old XY-native compute path stopped being safe to treat as the
obvious next implementation step.

## What this older path was trying to do

The original direction was to reduce the cost of tracer-heavy scenes by:

- partitioning near / medium / far tracer workloads
- introducing cheaper native representations for medium/far tiers
- eventually adding GPU-side compaction and density aggregation

That direction is still valuable as history and as groundwork.

## Why the old path is paused now

The older compaction direction assumed a flatter worldview in exactly the places
that now matter most:

- medium/far tracer state was XY-centric
- culling assumptions were XY-centric
- medium/far shader paths were 2D-centric
- compute kernels were designed around the old top-down packet-host renderer

Once the project is read as a camera/render/interaction/compute migration,
keeping that path active without redesign would risk reintroducing hidden
flattening underneath the newer 3D renderer direction.

## What remains useful from this historical work

The project still keeps the value of the earlier native direction:

- stream separation
- cheaper medium/far native representations as a design idea
- revision-aware upload discipline
- a clear future place where GPU-side compaction could slot back in

## How to read this file now

Read this file as:

- a historical milestone in the native-rendering evolution
- a reference for why tracer stream specialization existed in the first place
- a source of ideas that may be revived **only** after they are translated into
  a fully 3D camera-space contract

Do **not** read it as current canonical guidance for the next unrestricted-agent
implementation step.
