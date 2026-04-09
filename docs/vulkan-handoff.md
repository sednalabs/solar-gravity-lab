# Vulkan handoff

> **Historical handoff note.**
>
> This document predates the stronger camera-relative / orbit-camera /
> depth-tested renderer contract that now frames the project. Keep it as useful
> history, but do not treat it as the current front-door architecture note.
> Start with [`docs/rendering-architecture-current-state.md`](rendering-architecture-current-state.md)
> and [`docs/frame-lifecycle.md`](frame-lifecycle.md) before using this file.

The original Vulkan bring-up path focused on moving from packet storage and
frame clear into real native scene upload, graphics pipelines, and later compute
work. That historical sequence was still directionally useful, but it assumed an
older rendering worldview where camera center + view radius and top-down packet
thinking were enough to reason about the renderer.

That is no longer the best current framing.

## What still matters from this older handoff

- finishing real native draw pipelines was and remains the right structural move
- native stream specialization was and remains useful
- compute should still come after stable graphics pipelines, not before

## What no longer reflects the current design target

- camera should not be understood only as center + view radius
- renderer correctness is no longer just “finish graphics pipelines”; it is also
  about preserving a 3D camera-relative packet contract
- medium/far compute should not be treated as a blind follow-on step without a
  3D camera-space redesign and explicit re-entry criteria

Keep this file as historical context, not as the canonical current-state design.
