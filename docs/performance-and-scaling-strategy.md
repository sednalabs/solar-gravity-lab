# Performance and scaling strategy

Performance work must scale the **correct** double-precision physics and
3D camera-space rendering architecture, not reintroduce flat/approximate
physics or move simulation authority into the GPU.

## Current implemented accelerators

- **Physics:** Rust KDK reuses one acceleration endpoint across steps;
  useful-pair enumeration skips zero-source interactions while retaining
  passive probe forces. Native Arm64 FP64 NEON
  **passive-two-target batching**, symmetric pairwise/tiled/parallel-tiled
  and x64 AVX2 kernels remain gated by detected CPU/OS capabilities and
  scalar-oracle comparisons. The passive NEON path retains source backreaction
  and uses FP64 sqrt/div/FMA; its hosted canonical/sparse median times
  improved 36.9%/36.7% relative to previous selected NEON, without a
  measured oracle regression. See
  [AArch64 evidence](aarch64-extension-assessment-20261118.md).
- **Arm64 scheduling:** parallel dispatch requires at least
  **8,128 useful gravitational pairs per selected worker** (provisional
  hosted Neoverse-N2 crossover), *not* the earlier 4,560 grain or a
  body/tile count alone. With four workers, dense 192 (18,336 useful pairs)
  stays nonparallel while dense 256 (32,640 pairs) is eligible. Non-source
  tracer swarms do not magically supply additional gravity pairs.
- **Renderer:** the native Vulkan stage uses camera-relative 3D packets,
  five streams, depth, medium/far GPU compute compaction, bounded density
  rendering and reuse of compatible resources and recorded commands.
- **Android shell:** viewport/label snapshots and lifecycle handling
  minimise duplicate projections. Native `body_by_id` inspection does
  not clone an entire world and its trail history for one body's values.
- **Optional platform hints:** supported Android API 31+ devices may
  receive public render-executor Performance Hint sessions; those do not
  imply a guaranteed clock selection, GPU timestamp or thermal improvement.

## Evidence boundaries

[Hosted CPU benchmarks](../engine/physics/benchmarks/README.md) record
source-identified scalar and selected-path medians on named x64/Arm64
hosts. [Exact-source validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943)
tests parity, ARM64 activation, Vulkan compute pixels and Android lifecycle.
Those checks do not establish a Galaxy S25 Ultra speedup, sustained refresh
rate, battery or thermal envelope; obtain installed-device traces separately.

## Scaling priorities

Measure frame time, achieved simulation rate, draw/upload traffic, total
useful gravitational pairs, body/tracer count, trail history, selected
worker count and thermals together. Keep two separate tuning axes:

- **World/CPU:** source-aware work, numerical accuracy, adaptive *operation*
  caps, SIMD/worker crossover and history storage.
- **Scene/GPU:** camera-space culling, stream specialization, density
  aggregation, stable label picking and resource lifetime.

Compaction may thin already-published pixels, but must never choose masses,
forces, ephemerides or authoritative trajectory integration. Benchmark each
change against the documented reference model and measured visual output,
especially at wide zoom and with user-authored massive probes.
