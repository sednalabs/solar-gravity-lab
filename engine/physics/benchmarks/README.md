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
