# Authoritative CPU throughput

Run from the repository root:

```sh
cargo run --release -p solarlab-physics --example throughput -- --samples 11
```

The example measures a fresh working copy, 64 fixed one-second KDK steps and
invariants, using the same public authoritative entry point as the runtime.
Each workload is calibrated to a batch of at least 20 ms, warmed twice, and
reported with minimum, median and 90th-percentile microseconds per operation.
The reported source count excludes nonperturbing tracers. Requested and actual
backends are separate. Every measured backend first has to agree with the scalar
oracle and retain finite state.

The workloads include the actual bundled three-body Earth–Moon lab, its 800 km
probe preset, the 365-body canonical seed using runtime source-mass semantics,
a cloud of 1,021 test particles around the three-body lab, and a dense synthetic
1,024-source scene, plus 128/192/256/512-source crossover fixtures. These are CPU microbenchmarks, not renderer frame timings or
phone thermal tests. Repeat on the intended host and preserve CPU features,
affinity, compiler, source, and raw output when making a performance claim.

`local-core-comparison.json` records the first before/after measurement on the
shared x86_64 development container. Its baseline is commit `2b661472` with this
benchmark example, before changing the solver. The optimized version reuses an
endpoint acceleration through the fixed segment, keeps its two half-kicks in
the same order, reuses scratch, and visits only pairs with at least one source.
The scalar regression independently reproduces the original full-pair,
uncached KDK and requires exactly equal body states for dense and sparse cases.

The ARM64 target is compiled locally; actual NEON activation, scalar agreement,
and staged/bulk equivalence require the hosted ARM64 lane. Do not infer ARM64
or S25 Ultra speedups from these x86_64 measurements.

## AVX2 selection

`local-avx2-comparison.json` compares the optimized scalar and AVX2 paths from
the same source. AVX2 keeps four target accelerations in vector registers while
visiting the ordered nonzero source list. Each target retains the scalar
contribution order; vector tails and staged boundaries are covered by exact
state comparisons. Square root and division use FP64 instructions, not a reduced
precision reciprocal approximation. The AVX2 integer self-mask is guarded by
both the advertised feature set and actual CPU/OS detection, independently of
test feature overrides. FMA is not required.

The first experimental implementation vectorized four pairs but scattered
partial accelerations after each batch; it did not beat the optimized scalar
path and was replaced before activation. The register-resident target design
does improve these measured workloads. The three-body case stays scalar because
its measured vector-tail overhead outweighed the benefit; four bodies and larger
can use the AVX2 path. These decisions remain workload measurements on this host,
not a claim that every AVX2 processor has identical crossover points.

## ARM parallel work distribution

Parallel NEON keeps its existing minimum body count and worker-budget cap.
Its work is partitioned by the number of useful gravity pairs, rather than
assigning contiguous 32-body rows cyclically. The canonical seed puts all 15
sources in its first 15 rows; the old first row tile therefore owned all 5,355
interactions. Each new partition is a contiguous section of the lexicographic
physical-pair stream. A long source row can be split between workers, so loads
differ by at most one pair even when only one source supplies the work.

The schedule is prepared once per fixed segment, using O(N + workers) range
storage rather than an O(SN) pair list, and is reused with worker scratch on
every force sweep. Each worker visits its ranges in order; reduction follows
worker index, independent of completion order. Exact repeated and staged/bulk
state equality and scalar-tolerance tests run on the ARM host. The existing
path identifier remains `simd.arm64.neon-f64-parallel-tiled-pairwise`.

For compatibility, report `tile_size_bodies` and `tile_count` describe the
coarse body-based worker-budget cap. `active_workers` describes actual workers;
`parallel_tile_workers` describes nonempty pair partitions in the active or
candidate plan, according to `mode`. These fields do not imply that every
partition is 32 contiguous bodies.

Balanced work does not remove per-sweep thread creation and joining. Hosted
ARM measurements must determine whether sparse workloads should use a
single worker. The balanced-partition candidate itself has no measured speedup
claim until its hosted comparison finishes.

## Hosted baseline and provisional work grain

The verified source, CPU metadata, actual selected paths, affinity masks,
medians/p90s, scalar-oracle errors and artifact digests from validation run
`43868064550` are preserved in `hosted-43868064550.json`. Reproduce its table:

```sh
python3 engine/physics/benchmarks/summarize_hosted.py \
  engine/physics/benchmarks/hosted-43868064550.json
```

`hosted-43868064550.md` is that generated output. Both ARM runs used the same
release executable. On the four-core Neoverse-N2 host, the canonical 365-body /
15-source operation took 7,090.657 µs with four workers, 2,737.129 µs with
single-worker tiled NEON, and 2,510.148 µs with scalar. The former row partition
gave all 5,355 useful pairs to worker 0. This is a measured parallel regression,
and single-worker tiled NEON was itself about 9% slower than scalar. Vector
instructions alone do not establish a speedup.

The dense 1,024-source operation did benefit: four-worker NEON took 62,267.889 µs
versus 199,905.704 µs for single-worker NEON and 232,067.382 µs for scalar.
The sparse 1,024-body / three-source workload was already single-worker, at
1,547.796 µs versus 1,734.785 µs for scalar. Small three/four-body NEON gains were
only about 2%/5% on this host. These are same-source comparisons within one host;
the separate x64 table must not be combined into a cross-machine before/after
claim.

The **implemented** Arm64 scheduler requires **8,128 useful gravitational
pairs per selected worker** rather than the earlier 4,560-pair work-grain
candidate. This is separate from the 32-body tile size and counts only
physical interactions involving a gravitational source. With four workers
the minimum is 32,512 useful pairs: dense 192 has 18,336 and stays
single-worker, while dense 256 has 32,640 and is eligible. The canonical
365-body/15-source scene has only 5,355 pairs and avoids multithreaded
startup. Dense 128/192/256/512 fixtures and scalar-parity tests preserve
the crossover policy. This is a *provisional hosted Neoverse-N2 decision*,
not a universal optimum or measured S25 Ultra speedup.

The earlier `hosted-43868064550` archived data reports a previous
scheduler; it remains unchanged as dated evidence and must not be read as
an exact-head benchmark of the newer 8,128-pair gate. Qualify actual
selected worker paths and current-source timings independently.

A longer-lived worker scope could remove repeated thread creation. A safe Rust
implementation is practical with standard synchronization and owned buffers,
but needs separate phase, panic and termination tests. It remains follow-up
work; this change does not claim to eliminate per-sweep thread startup.

## November 2026 — two-passive-target FP64 NEON batching

A new source-aware Arm64 path,
`simd.arm64.neon-f64-passive-batch`, packs **two independent zero-source
targets** per `float64x2_t` while preserving the existing symmetric NEON
source-source forces. FP64 square root, division and fused arithmetic are
used throughout; active path telemetry and the Kotlin/FFI catalog agree.
Selection is limited to numerous sparse worlds, not tiny or dense scenes.

Hosted release-mode 64-step operation (11 samples, comparable four-core
Neoverse-N2 runner class; selected active workers = 1):

| Scene | Before `fcc5539a` | After `88828572` | Median reduction |
| --- | ---: | ---: | ---: |
| Canonical 365 bodies / 15 sources | 2,152.236 µs | 1,357.173 µs | 36.9% |
| Sparse 1,024 bodies / 3 sources | 1,537.832 µs | 974.181 µs | 36.7% |

Both before/after runs' scalar reference timings were comparable, and
scalar-oracle discrepancies remained unchanged (canonical 0; sparse
maximum absolute error 1.4551915228366852e−11 and relative error
1.2336296197834854e−15). The same workload improvements appeared under
single-CPU affinity. Small/fully massive scenes kept their previous paths.
Exact logs: [old run](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44092479811)
ARM job `134071220174` and
[new run](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44152318531)
ARM job `134284208622`. Detailed source/ISA audit:
[AArch64 extension assessment](../../../docs/aarch64-extension-assessment-20261118.md).

This is a **hosted CPU microbenchmark**, not a benchmark of the entire
Android UI, GPU, thermal budget, battery, or actual Galaxy S25 Ultra.
Physical device capability and activation evidence is separately required.

## Cache and topology follow-through

The next candidate packs force-only sources into aligned 32-byte records and
keeps two independent NEON vectors (four passive targets) in flight per source
scan. It reuses passive membership and source storage through the KDK segment,
refreshing source coordinates every force sweep. A frozen PR #242 force kernel
is the bitwise native Arm regression oracle. The cache layout, ADPF solver
boundary, discovered CPU/cache topology and optional Arm datatype qualification
are documented in [CPU cache and topology](../../../docs/cpu-cache-topology-20261118.md).
This candidate requires a paired same-host release comparison before claiming
a throughput improvement; it does not inherit PR #242's measured speedup.
