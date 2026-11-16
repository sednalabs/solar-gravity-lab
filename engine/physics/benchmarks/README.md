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
1,024-source scene. These are CPU microbenchmarks, not renderer frame timings or
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
