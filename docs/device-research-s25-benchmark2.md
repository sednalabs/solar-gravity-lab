# S25 Ultra: first benchmark.2 recording

Physical evidence supplied by the owner on 21 November 2026 (Australia/Sydney).
The raw export remains private. This recording matches the published
[benchmark.2 APK](https://github.com/sednalabs/solar-gravity-lab/releases/tag/v0.2.0-benchmark.2).

## Identity and coverage

| Item | Recorded value |
|---|---|
| Source | `eac2a64c31aba654dfccdb007558d7c6d4e9270f` |
| APK SHA-256 | `ec80688005f750f86dc614bb97e76562205baafe04d29b9cb533cdfa736db059` |
| ZIP SHA-256 | `29211f6a1abd7257f1193248af5ea5337181f8f84451999f4d3b3f4775910350` |
| ZIP size | 1,305,446 bytes |
| Package/version | `com.sednalabs.solarlab.benchmark2004002`, `0.2.0-benchmark.2`, code 2004002 |
| Device | Galaxy S25 Ultra SM-S938B, QTI SM8750 |
| Plan | Quick, protocol version 2 |
| Active elapsed time | 30.429494781 seconds |
| Samples | 3,952 measured PASS, 52 calibration PASS, 52 warmup PASS |
| Blocks | 988 complete ABBA blocks, 38 for each of 26 comparisons |
| Admission | Seven unavailable capabilities excluded before sampling |
| Termination | Duration/block targets reached; no retired pair or thermal pause |

All six indexed payloads have matching byte lengths and SHA-256 hashes. There
are seven unique ZIP members including the index, and 10,141 complete journal
events with nondecreasing timestamps. Every sample has a matching start.
Independent reconstruction confirms role order, fixture, steps, requested and
effective repetitions, timing scope, placement and case identity in all blocks.
Protocol completion does not establish freedom from systematic order effects.

## CPU results

These are original fixed-ABBA descriptive results: geometric A1/B1/B2/A2
ratios, then their median across 38 blocks. Negative means less measured time.
Ordinary candidates compare with production NEON; scalar remains the oracle.

| Candidate and timing control | 1 step | 8 steps | 64 steps |
|---|---:|---:|---:|
| Production NEON versus scalar | -50.98% | -52.84% | -54.33% |
| Source-specialised portable versus production NEON | +38.64% | +46.85% | +46.58% |
| Research exact NEON versus production NEON | -7.93% | +1.19% | -0.27% |
| Portable one-worker versus production NEON | +47.74% | +56.00% | +53.35% |
| Kahan versus production NEON | +130.28% | +137.02% | +140.16% |
| Three-refinement rsqrt versus exact research NEON | +36.97% | +33.01% | +33.24% |

Production actually used `simd.arm64.neon-f64-passive-batch`, one worker and no
fallback. Its checksums match research exact NEON at every tested step count.
The small apparent differences between these equivalent paths do not justify
changing production dispatch.

Three-refinement rsqrt was slower in all 38 blocks at one/eight steps and 37/38
at 64 steps. At 64 steps its pooled per-repeat median is 1,175.156 microseconds,
versus 880.026 for exact NEON. Caller CPU and wrapper clocks agree in sign.
Both have maximum scaled state error `5.551115123125783e-17`, but checksums and
individual component errors differ. The 64-step trajectory covers only 0.0625
simulated seconds; this is not long-horizon scientific acceptance.

The longer serial refinement chain is a plausible source-level explanation,
not an instruction-stall measurement. Keep exact NEON as the default. The
existing targeted/Full `neon-rsqrt-refine-2` experiment has an explicit `2e-8`
scaled-state budget and was not selected by Quick. Measure it before creating
further variants or weakening the valid-domain guards. Kahan reduced scaled
error to `3.469446951953614e-18` at 64 steps at a substantial time cost; this
does not establish general scientific superiority.

## GPU and native results

Pooled medians show scale; paired changes use individual blocks.

| Measurement | A median | B median | Paired change |
|---|---:|---:|---:|
| Subgroup GPU query, 12 dispatches | 120.442 us | 61.354 us | -49.05% |
| Subgroup host submission/completion | 445.547 us | 393.464 us | -12.70% |
| Subgroup complete JNI wrapper | 10.165 ms | 11.277 ms | +9.05% |
| Visual FP16 GPU query, 13 dispatches | 39.999 us | 40.052 us | approximately 0% |
| Cached pipeline creation/destruction, two iterations | 6.272 ms | 88.464 us | -98.42% |
| Cached pipeline complete wrapper | 16.305 ms | 12.868 ms | -18.61% |
| Native FCMA | 2.768 ms | 2.372 ms | -15.93% |
| Native prefetch-four | 4.492 ms | 4.426 ms | -9.51% |

Subgroup GPU intervals are the strongest signal: all 38 blocks are between
-49.50% and -48.51%, with valid timestamps, zero reported error, matching
checksums and 7,023 accepted entries. Both baseline slots are approximately
120.5 microseconds and both candidate slots 61.4. Setup rises 32.20%, explaining
why the complete isolated probe is slower. The production renderer already
selects supported subgroup paths; a new whole-renderer gain is not established.

FP16 has no GPU benefit: exactly half the blocks favour it. Error is
`0.000244140625` against the visual-only `0.002` limit. The pipeline experiment
serialises 4,666 cache bytes without clearing driver-internal cache. It does
not test production disk persistence, renderer reopen, launch time or frame rate.

Do not compare raw batch timings directly with benchmark.1's one iteration.
Passive FP32 now uses seven dispatches and persistent transfer 15. Rust
`elapsed_ns` is a per-repeat median; hardware intervals cover their declared
batches. Repetitions are subsamples, not independent blocks.

## First-position bias

The identical passive-FP32 routine appears **27.56% faster** in role B under
the host submission/completion clock. Slot medians explain the bias:

| Chronological slot | A1 | B1 | B2 | A2 |
|---|---:|---:|---:|---:|
| Same-case host interval | 700.573 us | 361.276 us | 362.917 us | 358.412 us |
| Prefetch comparison native interval | 6.125 ms | 4.473 ms | 4.340 ms | 4.249 ms |

Same-case setup is 11.081 ms in A1 versus approximately 5.1-5.3 ms later. This
establishes a systematic position cost, without identifying its driver/cache/
power mechanism. Fixed ABBA cancels linear drift but not this nonlinear effect.
Many repetitions can yield a narrow interval around a biased result. Prefetch
is therefore not an established speed improvement; FCMA also needs balanced
confirmation.

Protocol v3 alternates ABBA and BAAB with fixed A/B semantics. Two complementary
blocks form an eight-sample unit; combine log ratios before converting to a
percentage, and bootstrap complete units. Averaging percentage effects would
not cancel reciprocal order effects. Unmatched, failed or environmentally
incompatible units remain visible without qualifying a candidate. The corrected
protocol is follow-up implementation, not something this recording executed.

## Environment and remaining evidence

Seventeen distinct cached environment observations span 30.064 seconds:
unplugged, 28% battery, reported battery temperature 31.6 C and thermal status
zero. Display refresh changes between approximately 120 Hz and 24 Hz, so
whole-phone conditions were not fixed. No battery energy counter is available.
Cached charge/current observations do not isolate kernel energy or establish
sustained behaviour.

JNI wrappers total 24.009 seconds; caller-thread CPU totals 23.162 seconds.
Journal serialization/write/flush/fsync totals 5.252 seconds, about 17.3% of
session time, with 37.17 MB uncompressed events. Do not subtract these scopes
from kernels or assign battery energy to them. Summing Rust `kernel_total_ns`
and declared hardware intervals gives 8.316 seconds of those scopes; summing
mixed per-repeat `elapsed_ns` would not be total work.

There are no observatory callbacks, paused-export counters or production cache
reopen observations. After protocol v3 qualification, repeat Quick, then run
targeted two-refinement rsqrt and separate observatory recordings across
paused/pan/play/pause and renderer reopen. Keep charging, brightness and a
selected display mode steady, while recording what Android actually reports.
See the [device guide](device-research-benchmark.md).

## Reproduce

```sh
python3 tools/analyse_benchmark_report.py /path/to/private-benchmark2.zip --output dist/s25-benchmark2
```

The `eac2a64` analyser supplies the original descriptive table. Later revisions
retain raw evidence while adding order/environment diagnostics. Neither analysis
changes scientific settings or production dispatch.
