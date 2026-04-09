# Performance and scaling strategy

This document explains how the project should think about performance without
regressing architectural correctness.

## First principle

Performance work must scale the **correct architecture**.

A faster path that reintroduces hidden flattening is not actually a win.

## Scaling dimensions

The main scaling pressures are:

- body count
- tracer count
- trail density
- camera regime (local vs wide/system view)
- mode (sandbox vs runtime mirror)

## Where optimization should live

### Kotlin shell

Keep it thin. Do not try to solve the real performance problem here.

### Packet / render policy layer

Optimize camera-aware culling, tiering, and packet shaping while preserving the
renderer contract.

### Native Vulkan layer

Optimize buffer reuse, stream specialization, graphics pipelines, and later
compute or instancing work.

### Runtime / authoritative world

Optimize deeper native ownership, fewer managed/native crossings, and better
SIMD / worker behavior only once the renderer contract is stable enough to make
those gains meaningful.

## Current compaction stance

Medium/far compute-compaction is paused until it can come back in a 3D
camera-space form. That pause is a deliberate performance trade-off in favor of
architectural correctness.
