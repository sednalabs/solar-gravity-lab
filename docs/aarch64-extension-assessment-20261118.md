# AArch64 extension assessment — 18 November 2026

This is a source-based implementation review of Solar Gravity Lab's
authoritative **FP64** physics path, not a claim about the precise Galaxy S25
Ultra feature bits or **on-device** speed. Hosted performance is measured
separately below. Starting point: `main` at
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

The source-aware dispatcher uses this path only for **at least 256 bodies,
64 passive targets and no more than one gravitational source per eight total
bodies**, and only if the actual host provides NEON. This deliberately avoids
unbenchmarked 64–255-body workloads while preserving the measured 365/1024
improvements. Dense mutual-gravity worlds, tiny Earth–Moon scenes
and all non-Arm64 hosts keep their existing routes. An explicit path ID is
exported through the FFI and Android telemetry; no fabricated SVE or SME
activation. Promotion depends on ARM64 native tests and comparable release-mode
throughput; if a workload regresses, narrow or disable the dispatch gate.

## Hosted performance and numerical qualification

The first completed exact-head [ARM64 validation #44152318531](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44152318531)
compiled and exercised the new native intrinsics and passed the FP64 source
force, scalar-oracle, odd/even passive tail, source-mass toggle, full/staged
execution and ARM64 ISA tests. The baseline
[pre-change run #44092479811](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44092479811)
used an equivalent hosted four-core Neoverse-N2 ARM64 runner. Both ran 11
release-mode samples of **64 simulation steps** with the same workload
definitions and CPU class. These are *comparable hosted jobs*, not
measurements from the same physical Galaxy handset.

| Workload (source/total) | Old selected NEON median | New selected batch median | Reduction |
| --- | ---: | ---: | ---: |
| Canonical 15 / 365 | 2,152.236 µs | 1,357.173 µs | 36.9% |
| Sparse 3 / 1,024 | 1,537.832 µs | 974.181 µs | 36.7% |

One-CPU-affinity replay gave 2,152.069 → 1,357.205 µs and
1,533.385 → 970.585 µs respectively; **worker count remains one**. The
canonical scalar reference itself changed only 2,506.573 → 2,507.801 µs
across runs; sparse scalar 1,732.769 → 1,732.446 µs. This supports a
workload-specific SIMD improvement rather than a generally faster hosted
machine. The new canonical body's maximum scalar-oracle error remained 0;
sparse maximum absolute error stayed at 1.4551915228366852e−11 and
maximum relative error at 1.2336296197834854e−15. Tiny three/four-body
laboratories and dense mutual-gravity scenes keep the previous kernels.

Data source: two native ARM64 job log NDJSON measurements, old job
`134071220174` and new `134284208622`, plus the exact commit IDs
`fcc5539a` (baseline) and `88828572` (candidate). This performance
acceptance covers only measured hosted CPU workloads. Android frame time,
battery, thermal, memory and actual S25 dispatch must be evaluated on
device; do not extrapolate these percentages to whole-app speed.

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
- Matched x64/ARM64 compilation and source-identified hosted throughput
  comparisons for default selection. The measured canonical/sparse results
  above justify the conservative source-aware gate; unmeasured smaller
  workloads stay on the old kernel. Any speedup must name workload, selected
  workers, compiler, CPU class and source SHA, never compare unlike hosts.
- Real Galaxy S25 Ultra sustained frame time, scalar parity, battery/thermal
  tests still outstanding even if hosted performance is positive.

This is an experiment in a **new usage of existing NEON FP64 lanes**, not a
new silicon instruction extension: its benefit comes from independent
target parallelism while preserving the mass/source contract.
