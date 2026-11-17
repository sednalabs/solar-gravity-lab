# AArch64 extension assessment — 18 November 2026

This is a source-based implementation review of Solar Gravity Lab's
authoritative **FP64** physics path, not a claim about the precise Galaxy S25
Ultra feature bits or achieved speed. Starting point: `main` at
`64c8fe8ee180959c7940f0838e09e52ed2e076ea`.
Tracking: Ops **w18979**; implementation PR on
`perf/aarch64-passive-neon-batch-20261118`.

## What was actually active

- AArch64 NEON (`asimd`) already provided 128-bit 2-lane FP64 arithmetic,
  including fused multiply-add (`vfmaq_n_f64`) for X/Y; scalar Z used
  `mul_add`. That is **already fused FP64**; it would be incorrect to present
  FMA as a newly activated optional extension.
- The existing symmetric pairwise, tiled and parallel tiled NEON paths were
  runtime-feature-gated, used scalar-oracle tolerances and, for parallel work,
  required 8,128 useful pairs per selected worker.
- The existing pairwise kernel put X and Y of a **single pair** into a NEON
  vector, requiring repeated gathering/scattering accelerations for targets.
  For sparse worlds with a few gravitating sources and many passive tracers
  or spacecraft, source-source and passive dynamics can be computed separately
  without changing the force law.

## Implemented experimental improvement

`simd.arm64.neon-f64-passive-batch` processes two **independent passive
targets** per 128-bit vector. It uses NEON FP64 sub/mul/FMA/sqrt/**full-precision
division** (never reciprocal estimates, FP16, FP32, BF16 or reduced precision),
visiting sources in ascending order. Source-source forces remain with the
previous symmetric NEON pair kernel, preserving the equal/opposite physical
interactions, source backreaction and FP64 accumulation order. Passive
bodies exert no gravity, so target-wise batching is valid. An odd passive
target is handled with the existing exact pair path.

The source-aware dispatcher uses this path only for at least 64 bodies,
32 passive targets and at most one source for eight bodies, and only if the
actual host provides NEON. Dense mutual-gravity worlds, tiny Earth–Moon scenes
and all non-Arm64 hosts keep their existing routes. An explicit path ID is
exported through the FFI and Android telemetry; no fabricated SVE or SME
activation. Promotion depends on ARM64 native tests and comparable release-mode
throughput; if a workload regresses, narrow or disable the dispatch gate.

## Other AArch64 features assessed (not claimed active in physics)

| Feature | Assessment |
| --- | --- |
| SVE/SVE2 | Wider *predicated* FP64 target loops could scale across bodies if the **actual device** exposes SVE. Qualcomm's public Oryon/8 Elite information does not establish the S25's exact SVE vector length or support; gather a runtime census before dispatch. Stable Rust SVE intrinsic/toolchain support must also be proven. |
| SME/SME2 | Matrix throughput is mainly useful for dense algebra/ML. Newtonian gravity is irregular FP64 inverse-distance and source/target work, not an obvious SME tile multiply. Requires supported hardware, a real matching kernel and proof. |
| FP16/FHM/BF16/DOTPROD/I8MM | Could assist quantised render-only density, statistics, or bounded forecast models. Not suitable for authoritative AU-scale FP64 trajectories: mantissa/accumulation and truncation errors would change scientific results. |
| LSE/LSE2 | Potential optimisation for *measured contended atomics* such as frame/work queues, not the current disjoint-worker force sweeps. Do not add atomics to physics merely to activate a feature. |
| CRC32, SHA/AES/PMULL | Useful for format checksums or signed content, but first check the existing libraries' accelerated implementations; no evidence the gravity loop is bottlenecked on checksums. |
| MOPS/LS64 | May help bulk copies or stores if exposed by runtime CPU and compiler and if profiling shows large memory-copy pressure; no assumption of S25 activation. |
| PAuth/BTI/MTE | Security/hardening features; potentially important but not physics acceleration, with platform ABI and build-policy caveats. |

Architecture descriptions:
[Qualcomm Snapdragon 8 Elite](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-elite-mobile-platform),
[Arm AArch64 extension overview](https://sourceware.org/binutils/docs/as/AArch64-Extensions.html),
[Rust NEON full-width FP64 divide](https://doc.rust-lang.org/stable/core/arch/aarch64/fn.vdivq_f64.html).
These establish available architectural mechanisms, not the active S25 CPU
feature set. Native features are read through the runtime and capability census.

## Required acceptance

- ARM64 tests: unchanged source-source bitwise dynamics; passive scalar/legacy
  oracle equivalence, zero-source and dynamic backreaction; odd/even target tails
  and source positions before/within/after; fixed-plan staged/bulk equality.
- Host ARM64 selected-path proof and x64 non-activation; JNI/Kotlin path-code
  and kernel-mask mapping aligned with the Rust catalog.
- Matched x64/ARM64 compilation and hosted release throughput before default
  performance claims. Any reported speedup must name workload, active worker
  count, compiler, CPU/host and source SHA, and avoid cross-machine comparisons.
- Real Galaxy S25 Ultra sustained frame time, scalar parity, battery/thermal
  tests still outstanding even if hosted performance is positive.

This is an experiment in a **new usage of existing NEON FP64 lanes**, not a
new silicon instruction extension: its benefit comes from independent
target parallelism while preserving the mass/source contract.
