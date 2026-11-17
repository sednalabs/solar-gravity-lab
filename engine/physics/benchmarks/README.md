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
