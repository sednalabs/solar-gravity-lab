# CPU cache, topology and Arm extension implementation

This is the follow-through to PR #242. It describes the candidate implementation
on `perf/cpu-cache-topology-20261118`; a feature branch, hosted proof, a published
APK and physical Galaxy S25 acceptance are separate evidence levels.

## Cache-aware passive gravity

The authoritative world remains AoS FP64 state. `PassiveCache` makes a compact
force-only view: each source occupies an aligned 32-byte record containing
`x`, `y`, `z` and `G * mass`. Velocities and unrelated state no longer enter the
repeated source scan. Source positions and masses refresh on **every** force
sweep, so endpoint acceleration reuse never becomes stale-position reuse.

Passive indices are retained for the fixed-source KDK segment. An explicit
body-count/source-membership comparison invalidates them after promotion,
demotion or a size change. Both buffers reuse capacity rather than allocating
on each sweep. Precomputing `G * mass` keeps exactly the former FP64 expression.

The kernel now processes four passive targets as two independent two-lane NEON
vectors. A source load feeds both vectors and independent square-root/divide
chains give the compiler an opportunity to overlap instruction latency. Each
target still accumulates sources in ascending original order. Source/source
forces and the odd scalar tail retain the original symmetric kernel. No
reciprocal estimate, low-precision force arithmetic or horizontal source
reduction has been introduced. The public path identifier remains
`simd.arm64.neon-f64-passive-batch`, with the existing conservative workload
eligibility. Within that path, a **single-step segment** retains PR #242's
allocation-free two-target traversal; longer segments use the packed four-target
cache. This is a **provisional** segment policy: the one-step exception follows
the measured preparation regression below, but counts 2/4/8/16/32 still require
the expanded paired proof to establish the crossover. They are not yet qualified.

The native Arm regression freezes the PR #242 kernel as an independent oracle
and requires **bitwise** force agreement over all four-target tail sizes,
multiple source layouts, refreshed positions, source changes, and 256/365/1024
body worlds. Existing scalar tolerance, source symmetry and staged/bulk tests
remain the integration contract. The cache's storage, refresh and invalidation
tests also execute on x64.

### Performance qualification

Compare the merge base and candidate on the **same native Arm runner**, same
compiler and affinity, in release mode with 11 samples and 64 steps. Use the
existing throughput example with canonical 365/15, sparse 1024/3, small 3/4-body
controls and dense 128/192/256 workloads. Repeat unrestricted and restricted to
one CPU from the actual allowed mask. Preserve all JSON output, compiler/source
identity, median/p90, active worker counts and scalar-oracle errors.

The acceptance question is whether the changed sparse paths improve without
moving unchanged scalar controls or regressing small/dense paths. Do not infer
a speedup from different hosted machines, and do not promote a hosted Neoverse
result into an Oryon, thermal or application-frame result.

The [dated paired result](../engine/physics/benchmarks/README.md#paired-native-arm-result-run-44273135829)
compares base `54b8d203` with candidate `2ca05d3a`: canonical/sparse medians
improve 2.32%/1.84% under full affinity and 2.39%/1.89% on one CPU, with unchanged
oracle errors and stable scalar controls. The dense-256 four-worker median is
1.76% slower with substantial between-run variance; that limitation is retained.
The [expanded paired result](../engine/physics/benchmarks/README.md#short-segment-qualification-run-44275712028)
uses the same binaries at one and 64 steps. It reproduces the longer-segment gains
but identifies a 1.87%/2.12% single-step sparse regression under full/one-CPU
affinity. The single-step streaming exception addresses that concrete gap;
exact-head paired proof must confirm its effect before qualification is claimed.
The same proof measures cached candidates at 2/4/8/16/32 steps so the final
amortisation threshold can follow evidence rather than assume count 2 is faster.

## Use actual topology and the Android scheduler

The capability collector now reads online CPUs, the collector's affinity,
reported capacities/max frequencies, cluster/core IDs and cache sharing/size/
line size. Its legal experimental masks are intersections of online CPUs and
allowed CPUs: unrestricted, discovered shared-L2 groups, the highest reported
capacity class and one CPU in that class. There are no hard-coded Oryon CPU
numbers, cluster sizes or cache sizes. Missing sysfs data remains unknown.

The Android census explicitly labels its affinity scope `adb-shell`: the shell
cpuset is **not** proof of the foreground application's allowed mask. Read the
actual target process/thread affinity before running placement experiments.
Maximum frequency is a hypothesis for a placement experiment, not a throughput
measurement. The application itself does not adopt these benchmark masks.

The production shell supplies Android Performance Hint sessions around native
`playbackTick` and `continueAdvance`. These report completed native work after
the serial boundary lock, excluding coroutine queue delay, hint setup and GPU
work. Playback uses the existing 100 ms solver update cadence, not simulated
time or a display-frame interval. Manual advance runs back-to-back; its separate
100 ms target is a provisional maximum latency hint per bounded native call,
not a cadence, a delay or a measured throughput optimum. This gives Android a CPU workload
signal with which to select core capacity and frequency under its thermal policy.

The coroutine boundary is serial but its Linux TID can migrate. Sessions are
recreated on a native TID change, avoiding stale thread membership on API 31-33
as well as later versions. Sessions close on idle playback, completed/cancelled
manual work, failed calls and teardown; unsupported or failing hint APIs do not
prevent physics. `SolarLabPhysicsHints` logs implemented/active/unavailable
state and the last work duration/report count. It does not claim ownership of
short-lived internal Rust workers, nor does a hint guarantee a particular core.

## Obscure extensions: exact qualification before execution

The aligned Rust/Kotlin/Python feature catalogue now has 43 entries. New masks
are appended so old ABI bits retain their meaning. In addition to stable Rust
probes, AArch64 reads its own Linux auxiliary vector without executing a trial
instruction. Census rows distinguish feature evidence from `os_auxv_qualified`;
Android `/proc/cpuinfo` alone never supplies auxiliary-vector qualification.

| Extension | Concrete relevance | Current status |
| --- | --- | --- |
| `SME_F64F64` | Optional FP64 matrix arithmetic; essential for a future FP64 SME kernel | Required alongside SME/SME2 by their candidate eligibility checks; no SME execution claim |
| `SVE_F64MM` | Optional FP64 matrix multiply, distinct from ordinary SVE FP64 arithmetic | Recorded independently; gravity's inverse-cube interaction is not a dense GEMM |
| `RPRFM` | Range/reuse hints for genuinely cache-missing source/stream ranges | Detected and documented; no unconditional prefetch on the tiny already-hot source set |
| `CSSC` | Short scalar integer/control operations | Compiler utility; not a force-kernel performance claim |
| `RCpc` / `RCpc2` | Alternative acquire-load instructions/addressing | Recorded; Rust atomic memory-order requirements are preserved |
| `WFxT` | Timed wait/event instructions | Recorded; no replacement of OS worker sleeping with an unmeasured wait loop |
| `SVE2 BitPerm` | Bit gather/deposit-style operations for a future packed visibility workload | Recorded as reserved, separate from FP64 physics |
| `MOPS`, LSE/LSE2, FCMA and existing features | Memory copies, atomics or complex-valued workloads where they actually occur | Existing classifications retained; `uscat` now normalises correctly to LSE2 |

The SME correction matters: `sme` or `sme2` alone previously qualified a named
FP64 candidate despite FP64 matrix support being optional. Positive and negative
tests now require the independent datatype bit. Capability eligibility remains
different from an implemented, benchmarked and selected kernel.

RPRFM is a hint with implementation-defined effects, so an opcode is not evidence
of useful prefetching. SVE does not follow from a device's Arm marketing version,
and SME/SME2 does not imply its optional FP64 datatype. Feature overrides remain
test/report inputs and cannot bypass the real NEON/AVX2 execution gates.

## Primary references

- [Linux Arm64 ELF hardware capabilities](https://www.kernel.org/doc/html/latest/arch/arm64/elf_hwcaps.html)
- [Linux AArch64 UAPI capability masks](https://github.com/torvalds/linux/blob/master/arch/arm64/include/uapi/asm/hwcap.h)
- [Linux SME userspace ABI and optional datatype capabilities](https://www.kernel.org/doc/html/latest/arch/arm64/sme.html)
- [Arm RPRFM instruction semantics](https://support.arm.com/documentation/ddi0602/2024-06/Base-Instructions/RPRFM--Range-prefetch-memory-)
- [Android performance hints and topology policy](https://source.android.com/docs/core/perf/performance-hint-api)
- [Android PerformanceHintManager API](https://developer.android.com/reference/android/os/PerformanceHintManager)

No physical S25 capability capture, CPU counters, sustained thermal run, battery
measurement or in-app performance gain is supplied by this source change alone.
