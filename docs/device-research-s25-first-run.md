# First physical S25 Ultra benchmark

Aggregate evidence from the owner-supplied Quick export, analysed on 19 November
2026 (Australia/Sydney). The raw export remains private. These numbers describe
the first installed build, not the performance of the subsequent changes.

## Identity and limits

| Item | Observed value |
|---|---|
| ZIP SHA-256 | `d8b4fc424e77f40157185619b43ac95f55c64c7a40be09262e2639c651f56ff0` |
| ZIP size | 81,326 bytes |
| Source | `3f4f0fccd08348986d7e98e42a4261d81de73f47` |
| Installed APK SHA-256 | `f93052ea901a5c6be99b9ebaa213729b88a670fe591a67d702e675173e58c77b` |
| Release | [0.2.0-benchmark.1](https://github.com/sednalabs/solar-gravity-lab/releases/tag/v0.2.0-benchmark.1) |
| Device | Galaxy S25 Ultra SM-S938B; QTI SM8750; Adreno 830 |
| Platform | Android 17 / SDK 37; 4 KiB pages; observed display 1080 × 2340 at 120 Hz |
| Integrity | Six indexed payloads verified, seven ZIP members including index; no journal warning |
| Run | 1.503823124 seconds; 26 workloads; 52 complete ABBA blocks |
| Results | 208 measured PASS samples plus 52 warmup PASS samples; zero FAIL |
| Environment | Two distinct cached observations; AC plugged; energy counter unavailable |

Each workload has only two blocks, below the five-clean-block preliminary
candidate threshold. No thermal pause and thermal status zero were observed;
this does not establish sustained stability. Plugged operation and unavailable
energy counters prevent an energy-saving conclusion.

The legal CPU set was 0–7: six CPUs reported capacity 765 / maximum 3.5328 GHz,
and two capacity 1024 / maximum 4.4736 GHz. Two frequency snapshots differed
substantially. These cached sysfs observations do not identify the CPU executing
a sample or prove thread migration. ADPF was not requested.

## Recorded comparisons

Changes use geometric A1/B1/B2/A2 paired ratios, then the median across blocks.
Pooled medians can differ from paired ratios because roles ran at different times.

| Comparison | Result | Interpretation |
|---|---|---|
| Production-selected CPU versus scalar, 64 steps | 795.052 µs candidate median; paired −54.75% | Shipping NEON already active; not a new gain. |
| Four-target research NEON versus scalar, 64 steps | 833.959 µs candidate median; paired −52.26% | Same checksums as production-selected at every tested step/repeat; separate cohorts do not establish a difference between NEON paths. |
| Source-specialised portable versus scalar, 64 steps | Paired −42.86% | Does not establish an improvement over shipping NEON. |
| Kahan versus scalar, 64 steps | Paired +16.18% | Accuracy/cost tradeoff, not a speed candidate on this fixture. |
| Subgroup versus atomic GPU compaction | GPU 13.567 → 8.541 µs; paired −36.95% | Both blocks about 37% lower; all 7,023 IDs/checksums matched. Longer and whole-renderer confirmation needed. |
| FP16 versus FP32 visual | Paired GPU −0.16%; pooled 9.765 → 9.843 µs | No demonstrated GPU gain. Host −8.92% is not a shader speedup. |
| Serialised-cache pipeline creation versus no supplied cache | 3.232 ms → 72.396 µs; paired −97.82% | Pipeline microbenchmark, not launch/frame time. Cache size 4,666 bytes. |

CPU work used 1,024 bodies, three sources and 1/8/64 KDK steps. Production ran
`simd.arm64.neon-f64-passive-batch`, one worker, no fallback. Maximum scaled-state
difference from scalar was `5.551115123125783e-17`. Portable source-specialised,
target-block and one-worker cases were bitwise equal to scalar on this fixture.

FP16 error was `0.000244140625` against `0.002`. Isolated FP32 passive GPU error
was `3.3400960286274265e-7` against `2e-4`, with median GPU interval 10.416 µs.
Its 16,387-target/three-source fixture and force-law implementation differ from
Rust; CPU/GPU timing division would not represent equal work.

Self-versus-self host controls showed −9.25% for native FP64 vectors, +7.89% for
FP32 passive GPU and +0.22% for persistent transfer. Small host changes are not
established wins. Native timed work totalled 153.164 ms; wrapper wall time totalled
1,041.806 ms. Wrapper time includes setup, fixture/oracle/checksum work and JSON;
the difference is not pure JNI overhead.

## App-visible capability evidence

SVE, Vulkan shaderFloat64, Qualcomm tile memory, usable tile shading, public
OpenCL and an NNAPI accelerator driver were unavailable. NNAPI's reference CPU
is not hardware acceleration. The private QNN library was not loadable through
the ordinary app boundary.

FP16, integer shader types, descriptor indexing, BDA, timeline semaphores, sync2,
subgroup size 64, calibrated timestamps and pipeline statistics were advertised.
Timestamp period was about 52.083332 ns with 48 valid bits. Discovery does not
prove execution. Extension names, including image processing, ray tracing and
optical flow, alone do not establish useful acceleration for this app.

## Resulting implementation decisions

- Ordinary CPU candidates now time against production-selected; scalar remains
  the independent oracle. Production-selected versus scalar remains a dispatch check.
- Actual FP64 FRSQRTE/FRSQRTS experiments use two or three refinements and a matched
  sqrt/divide NEON control. Error budgets remain explicit; no production promotion.
- The renderer persists a bounded compatible pipeline cache across recreation,
  with atomic replacement, empty-cache fallback and load/save host diagnostics.
- Unchanged paused packet export/decode/publication is avoided only after exact
  packet decode/release acknowledgement. Summary polling and explicit work remain.
- Protocol v2 seeks 30 seconds Quick / 120 seconds Full and five complete blocks
  per eligible pair, with fair rounds, frozen calibrated batches and finite budgets.
- The analyser separates GPU, wrapper, calling-thread CPU and setup time, rejects
  effective-batch mismatches and lets pilot/warmup failures veto speed claims.

These changes still require exact-source hosted and packaged qualification, then
a new physical-device export. The original ZIP does not measure their speed or
energy savings. See [the device guide](device-research-benchmark.md) for follow-up.

## Reproduce

With the original private ZIP supplied by its owner:

```sh
python3 tools/analyse_benchmark_report.py /path/to/first-s25-export.zip --output dist/first-s25
```

The analyser verifies the archive index, reconstructs raw blocks and retains
failures/exclusions. It changes no dispatch, scientific settings or saved worlds.
