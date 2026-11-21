# S25 Ultra: Full investigation reached its journal quota

Analysis recorded on 21 November 2026. The supplied physical recording was made
by the published [benchmark.2 APK](https://github.com/sednalabs/solar-gravity-lab/releases/tag/v0.2.0-benchmark.2),
using **protocol version 2** and Full investigation. The owner's raw export
remains private; this note publishes aggregate findings from its independent
integrity, storage and dense-NEON audits.

The recording identifies a useful exact-FP64 NEON follow-up and a collection
problem: a large catalogue consumed the journal allowance before any comparison
received a second block. It is **one-block reconnaissance, not performance
qualification or a production-dispatch recommendation**. The source follow-up
targets **benchmark.4**. At this source checkpoint, exact-source hosted
validation, remote APK construction and physical-device acceptance are pending.
[PR #249](https://github.com/sednalabs/solar-gravity-lab/pull/249) tracks current
exact-source proof, release publication and their remaining acceptance limits.

## Source provenance

| Item | Recorded value |
|---|---|
| Source commit | [`eac2a64c31aba654dfccdb007558d7c6d4e9270f`](https://github.com/sednalabs/solar-gravity-lab/commit/eac2a64c31aba654dfccdb007558d7c6d4e9270f) |
| APK SHA-256 | `ec80688005f750f86dc614bb97e76562205baafe04d29b9cb533cdfa736db059` |
| Input ZIP SHA-256 | `26e5ff1222df432ec9f37e3f4d1de3d9ed3389a4db43590e0f2fa97f1fd3e8e2` |

## What the recording contains

| Evidence | Aggregate result |
|---|---:|
| Elapsed session | 228.004 seconds |
| Planned fixture/step/placement comparisons | 4,571 |
| Visited comparisons | 3,438 |
| Completed measured blocks | 3,437; one fixed-order ABBA block per comparison |
| Unvisited comparisons | 1,133 |
| Calibration samples | 6,876 PASS |
| Warmup samples | 6,880 PASS |
| Measured samples | 13,748 PASS |
| Complete journal records | 65,445 |
| Termination | `storage_budget_exhausted` |

The final visited pair has premeasurement evidence but no measured block. The
extra warmups are retained batch-clamp rewarmups. All six indexed payload sizes
and hashes match, the export has seven unique members, journal timestamps are
nondecreasing, and all 27,504 samples have unique matching preceding starts.
The journal ends in `session_end`. No supplied native correctness flag is false
and no production fallback is recorded. These checks establish the integrity
and admitted correctness scope of this export, not a speedup.

Every completed comparison has only one fixed-ABBA block. Even identical scalar
self-controls range from **-27.02% to +42.03%**. The
[earlier benchmark.2 Quick recording](device-research-s25-benchmark2.md)
independently exposed first-position bias. Different fixture, step and placement
cells cannot substitute for repeated observations of one workload. Placement
was successfully applied and restored, but its fixed traversal order also
confounds absolute comparisons across placements with time, cache state and
dynamic frequency changes.

## The 256 MiB limit belongs to the app journal

The stop came from the **per-session uncompressed journal allowance**, not a
measurement of available phone storage. Complete-record and finalisation rules
allow a small threshold overrun. The final journal contains 269,799,771 bytes;
its compressed journal member contains 7,540,460 bytes, and the complete ZIP is
7,751,993 bytes. ZIP compression happens after collection and therefore does
not extend the recording allowance.

| Stored component | Accounted bytes | Share of the final journal |
|---|---:|---:|
| Sample before/after environment values | 123,101,297 | 45.63% |
| Sample native result values | 94,632,157 | 35.07% |
| Sample-start records | 13,531,486 | 5.02% |
| Final session-end record | 1,362,720 | 0.51% |

Sample field-value counts are compact-reserialised UTF-8 JSON estimates,
excluding enclosing key punctuation; record rows use recorded byte lengths.
The 45.63% environment estimate is not an exact source-span measurement: the
original environment source spans occupy 126,181,745 bytes.
There are **115 distinct cached environment observations but 55,008 copies**
in sample before/after fields. This is repeated storage of the same sensor
observations, not 55,008 independent sensor reads.

Journal serialization, writing, flushing and fsync total **56.775 seconds**, or
**24.90%** of the session. That counter excludes lock wait and the final
session-end record. It identifies instrumentation overhead worth reducing;
it is neither a kernel timing correction nor an allocation of battery energy.
The effect of a different encoding on a real phone remains to be measured.

### Offline reconstruction check

A **synthetic offline conversion of this existing export** replaces the 55,008
sample environment copies with references to 115 immutable definitions. It
preserves the original Android JSON escaping and every native-result JSON byte.

| Artifact scope | Original bytes | Re-encoded bytes | Reduction |
|---|---:|---:|---:|
| Uncompressed journal | 269,799,771 | 152,601,889 | 117,197,882 bytes; 43.4388% |
| Complete ZIP | 7,751,993 | 6,732,563 | 1,019,430 bytes; 13.1506% |

A control ZIP written with the same converter gives a 13.1493% ZIP reduction;
the small difference reflects container/writer overhead. Reconstruction matches
all 27,504 sample dictionaries and all 3,437 comparison analyses. All 114
standalone environment events, 115 observations and three refresh transitions
are retained. All report fields match except manifest/input-hash identity; the
manifest changes only by declaring the encoding.

This establishes storage equivalence and byte savings for the converted export.
Its protocol remains version 2 with descriptive-only statistical treatment.
Original session duration, quota outcome and writer counters remain historical
observations. Android per-event durability, collection overhead, recording
duration and phone energy with the new writer still require separate validation
and physical runs.

## The strongest CPU lead is exact blocked NEON on dense fixtures

The dense production baseline actually executes
`simd.arm64.neon-f64-pairwise`. The `neon-block-*` candidates execute research
FP64 blocked NEON with ordinary square root and division. They are distinct
from reciprocal-square-root estimates.

These are **individual 64-step, system-placement ABBA blocks**. A and B medians
describe their local block; changes use the geometric paired ratio. Negative
means the candidate took less time in that block.

| Fixture | Candidate | A median, us | B median, us | Paired change | Maximum scaled error |
|---|---|---:|---:|---:|---:|
| dense192 | `neon-block-4` | 4,558.437 | 2,863.151 | -37.12% | 3.496e-18 |
| dense192 | `neon-block-8` | 3,762.579 | 2,361.718 | -37.06% | 3.496e-18 |
| dense192 | `neon-block-16` | 4,357.682 | 2,749.896 | -37.08% | 3.496e-18 |
| dense256 | `neon-block-4` | 5,810.339 | 3,524.063 | -39.35% | 2.776e-17 |
| dense256 | `neon-block-8` | 5,792.943 | 3,499.114 | -39.60% | 2.776e-17 |
| dense256 | `neon-block-16` | 5,794.219 | 3,397.057 | -41.37% | 2.776e-17 |

The dense192 calls use two native repeats and dense256 one; the reported native
scope is a median per repeat. Those repeats are subsamples. The case error
budget is `2e-11`, and the 64-step trajectory spans only **0.0625 simulated
seconds**. Small observed errors do not establish long-horizon stability,
equivalent accumulation behaviour or production integration correctness.

The consistency across these dense blocks justifies prioritising repeated,
counterbalanced runs of all three block sizes. It does not select a winner or
justify changing production dispatch. Portable dense tiling was approximately
flat or slower in the selected 64-step system-placement blocks, so it is a
different and weaker lead in this recording.

## Two-refinement rsqrt is mostly slower here

Actual FP64 estimate/refinement instructions ran in 87 rsqrt2 cells, and the
candidate was slower in **83 of 87**. The three-refinement candidate was slower
in all 87 of its cells. These are different workloads and placements, not 87
independent repetitions of a single comparison. Their declared timing control
is exact `neon-block-4`, not the production-selected dense kernel.

| rsqrt2 cell, 64 steps and system placement | Paired native time change |
|---|---:|
| sparse3 | +21.54% |
| canonical15 | +33.53% |
| dense192 | +9.38% |
| dense256 | +7.90% |

The maximum rsqrt2 scaled error is `5.746715002695215e-12`, within its `2e-8`
case budget. Correctness within that sampled budget does not reverse the timing
result. The evidence favours investigating the exact dense NEON traversal
before adding more reciprocal-square-root variants or relaxing numerical
guards. The implemented rsqrt cases remain available for targeted confirmation.

## Other useful reconnaissance

| Observation | What it supports and what remains open |
|---|---|
| Subgroup GPU query -49.50% | Corroborates the earlier Quick probe; one Full block does not establish a new whole-renderer gain. |
| Visual FP16 GPU query +0.23% | No material gain in this probe; visual precision remains a separate acceptance boundary. |
| Descriptor indexing +112.13%; indirect dispatch +149.86% GPU query time | These isolated probes cost more in their declared scopes; a useful production workload could differ. |
| ARM CRC32 native timing -98.37% | A promising isolated CRC primitive; it needs balanced confirmation and an actual CRC hotspot. It is not a substitute for SHA-256. |
| Persistent four-worker path -74.53% on sparse3/64/system | Its control creates portable workers per step. That separate control is over 15 times slower than production in another comparison; differently timed blocks cannot establish a transitive production speedup. |

All 35 admitted hardware cases executed. The seven admission exclusions were
SVE, shader FP64, QCOM tile memory, QCOM tile shading, both OpenCL paths and the
public NNAPI accelerator. The NNAPI CPU control is not an NPU execution result.
The persistent-worker scope is one integration segment, with worker lifecycle
and source-copy counters retained.

The QCOM image-processing probe executed on Adreno 830 and matched its oracle
within `0.0004902213` absolute error. Readable pipeline executable statistics
reported 88 instructions, four 128-bit full-precision register slots, zero
scratch and a maximum ALU occupancy of 75%. Those are compiler/executable
statistics, not observations of live GPU utilisation or energy.

## Source changes for the benchmark.4 follow-up

### Complete bounded cohorts before expanding coverage

Protocol v3 retains fixed A/B semantics and alternates ABBA and BAAB. The
follow-up source partitions Full's entire declared schedule into cohorts of at
most **32 pairs** and seeks **five complete eight-sample counterbalanced units
per remaining active pair** before advancing. The start rotates within each
cohort. Round numbers are local to a pair's cohort and continue increasing when
a cohort is revisited; starting another cohort does not reuse a pair identity.

Cohort closure is independent of the global two-minute minimum. A small targeted
Full schedule continues actual measurements after its first completed pass
until duration and unit targets are met, subject to the existing limits. The
ten-minute time budget, 8,192 measured-block limit, thermal handling, retirement
rules and 256 MiB journal limit remain. Completion of an early cohort does not
claim completion of unvisited comparisons. Cancellation and cutoff retain
unmatched blocks and explicit target shortfalls.

The original 4,571-pair catalogue needs 9,142 measured blocks merely to give
every pair one complementary unit, already beyond the 8,192-block cap. Bounded
cohorts instead obtain complete evidence for a subset. The new deterministic
regression models encode the following expectations, assuming complete passing
blocks, no retirement and no earlier limit; their hosted execution is pending:

| Model cutoff | Pairs with five complete units | Other visited pairs | Unvisited pairs |
|---|---:|---|---:|
| 3,437 blocks | 320 | 13 with four units; 19 with three units and an unmatched half | 4,219 |
| 8,192 blocks | 800 | 32 with three units | 3,739 |

Neither model meets whole-catalogue completion. A unit-count target also does
not establish that the offline analyser will accept every unit as clean.

Full now orders steps **64, 1, 8, 2, 4, 16, 32**, visits compatible fixtures
within each step, and retains candidate round-robin ordering before cohort
partition. This advances dense 64-step work and broad case coverage while
preserving the entire fixture/step/placement catalogue. Hardware cases keep
their declared one-step scope in the first candidate wave. Quick and Sustained
retain their previous whole-catalogue scheduling.

### Store repeated sensor observations once

The source declares `environment_encoding: journal_snapshot_references_v1`.
Each immutable `environment_snapshot` definition is durably written before a
sample can reference it. Before/after uses retain their own
`observation_age_ns` and `observation_policy` alongside `snapshot_ref`; sensor
source timestamps are unchanged. A cached observation remains one observation,
however often it is referenced. Failed definition writes cannot authorise use.

Full native results, error metrics, calibration and warmup evidence, periodic
environment observations, and the durable `sample_start` breadcrumb before each
native invocation remain available. The change targets duplicated environment
payloads; it does not increase the journal quota, reduce durability or infer
extra sensor measurements.

The updated analyser resolves definitions in journal order before applying its
existing environmental qualification. It rejects unknown or forward references,
duplicate definition IDs, conflicting source observations and invalid ages. It
also accepts older inline environment exports. Storage compatibility does not
convert this protocol-2 recording into a counterbalanced protocol-3 experiment.

Implementation and regression seams are in the
[scheduling model](../clients/android/app/src/main/kotlin/com/sednalabs/solarlab/benchmark/BenchmarkModel.kt),
[protocol tests](../clients/android/app/src/test/kotlin/com/sednalabs/solarlab/benchmark/BenchmarkProtocolTest.kt),
[environment journal](../clients/android/app/src/main/kotlin/com/sednalabs/solarlab/benchmark/BenchmarkEnvironmentJournal.kt)
and [offline analyser](../tools/analyse_benchmark_report.py). Publication and
exact-source remote proof remain separate from this source description.

## Environment and next acceptance evidence

All recorded thermal states are zero. Battery temperature observations span
31.1–32.7 C, charge falls from 26% to 25%, charging remains off, and reported
display refresh alternates between approximately 120 and 24 Hz. No battery
energy counter is available. These readings do not prove fixed silicon clocks,
rail power, thermal equilibrium or sustained performance, and do not attribute
the charge change to a particular kernel or the logger.

After benchmark.4 has passed exact-source validation and its remotely built APK
is published, repeat Quick and collect Full/targeted evidence for exact
`neon-block-4`, `neon-block-8` and `neon-block-16`. Compare matching dense192 and
dense256, 64-step, system-placement cells across independently repeated sessions
and inspect self-controls, order diagnostics, native timing scopes, accuracy
and environmental exclusions. Keep brightness, charging and the requested
display mode steady while recording the mode Android actually reports.

Production promotion still requires a separate integration, representative
whole-frame measurements and scientific acceptance over the intended domain.
This recording contains no production observatory/frame or pipeline-cache
reopen acceptance. Recording and analysis instructions are in the
[device research guide](device-research-benchmark.md).
