# Isolated CPU and numerical research engine

This crate supplies the benchmark APK's Rust experiments. It constructs private,
deterministic synthetic worlds; it has no runtime/checkpoint handle and cannot
mutate a saved experiment. The production scalar and selected SIMD controls call
`solarlab-physics::advance_fixed_steps`, including the production source-aware
dispatch and eight-step packed-source policy.

## Invocation and result contract

```sh
cargo run -p solarlab-benchmarks --release --example research -- --catalogue
cargo run -p solarlab-benchmarks --release --example research -- --case neon-block-4 --fixture sparse3 --steps 64 --repeats 11
cargo test -p solarlab-benchmarks
cargo check -p solarlab-benchmarks --target aarch64-linux-android
```

`catalogue_json()` describes cases, recommendation IDs, legal fixtures, fixed
tolerances and current-process availability. A runnable catalogue entry is not
evidence of correctness or speed. `run_case_json(id, fixture, steps, repeats)`
returns `PASS`, `FAIL` or `SKIP`; `steps` is strictly 1..64 and `repeats` is 1..11.
Unknown inputs fail; out-of-domain fixtures and absent ISA support skip.

Catalogue identity records current-process CPU features using the production
detector, even when discovery runs without any benchmark. Detected ISA support
does not establish that a corresponding candidate exists or has executed.
Catalogue entries and known-case results also carry `comparison_kind`,
`physics_model`, `baseline_physics_model` and `control_scope_note`. CPU kernels use
`equivalent_kernel`; numerical and approximate policies use
`accuracy_cost_experiment`. The 1PN, J2 and radiation cases use
`different_force_model_cost`: their Newtonian timing control measures the extra
model/integrator cost, and cannot support an equivalent-physics speedup claim.

`samples_ns` contains every native integration duration. `elapsed_ns` is the
lower median per repeat, identified by `elapsed_scope=median_per_repeat`;
`kernel_total_ns` is their sum. Timers include kernel scratch allocation, source
packing, tree construction and worker creation/join. They exclude initial
fixture generation, JNI, independent reference calculation, invariant calculation
and JSON. A failed integration retains its attempt time and partial checksum,
explicitly marks the state unaccepted and does not masquerade as a zero-time pass.

These timings intentionally measure the candidate as implemented. In particular,
the scoped worker variants charge thread lifecycle costs and may lose badly on
small scenes. The separate `persistent-workers-2/4/8` candidates retain workers
for all steps of one measured segment/repeat. Bounded channels transfer immutable
source snapshots and owned output buffers, whose allocations are reused. Threads
inherit the caller's affinity, preserve per-target source order, and join before
the timer stops. There is no global pool. Started/joined worker counts and snapshot
copies are logged. These compare against the corresponding per-sweep-worker cases.

The checksum is deterministic FNV-1a over state bits, for identity and repeat
consistency. It is not a cryptographic integrity check. Every repeated state must
match the first checksum, and the final identical state is checked against its
independent oracle. Export/archive integrity belongs to the outer SHA-256 bundle.

## Implemented experiments

The catalogue is the canonical list; it currently includes 46 executable cases:

- Production selected/scalar controls; source-count specialisation for 1..8;
  portable target blocks 2/4/6/8/16; independent FP64 dependency chains; source
  tiles 16/32/64; aligned AoS source scratch and explicit source SoA scratch.
- Six real AArch64 NEON variants: FP64 target blocks 2/4/6/8/16 and a fixed
  three-source/four-target loop. These execute explicit FP64 vector sqrt, divide
  and FMA after real process capability detection. Other hosts skip them. The
  largest block is an experiment in register pressure, not an asserted win.
- Unique symmetric dense pair tiles 16/32/64; deterministic target partitions
  with 1/2/4/8 per-sweep workers and 2/4/8 segment pools; Kahan, Neumaier and
  binary-tree force reductions.
- Error-estimated KDK step doubling; encounter-time subdivision; fourth-order
  Yoshida composition; universal-variable two-body Kepler drift; eight-stage
  Radau IIA collocation; separate test-particle 1PN/J2/radiation force modules.
- Barnes-Hut monopole octrees; FP32 reciprocal seeds with one/two FP64 Newton
  corrections; passive acceleration/jerk extrapolation; CPU FP32 passive
  forecasts with FP64 massive sources and explicit FP64 acceptance checks.

Packed source records refresh on each force sweep. The scratch world's source
membership is fixed for one call; promotion/membership changes remain a production
runtime responsibility. Passive probes exert no force. FP32 forecasts keep massive
source force/state calculations in FP64. None of these candidates changes normal
product dispatch or persistence.

## Scientific domains and accuracy

All fixtures use SI units with artificial gravitational parameters. `sparse3`
contains 1,024 bodies/3 sources; `canonical15` contains 365/15; `dense192` and
`dense256` have all bodies massive. These are repeatable workloads, not a published
ephemeris or the canonical production scene. The `kepler` fixture is a unit-radius,
unit-angular-speed circular passive orbit about a fixed primary. The four-body
`close-encounter` fixture includes two massive sources and two passive probes.
Its bounded window uses `dt=1/1024 s`, up to 0.0625 s per call.

Performance-only force kernels compare against the unchanged production scalar
solver at identical steps. That establishes kernel equivalence, not absolute
trajectory accuracy. Numerical integrators compare the circular fixture against
the analytic orbit. Modified-force and encounter cases use independent RK4 at
128 times finer steps and report its disagreement with the 64-times-finer result.
The oracle itself must converge below one tenth of the declared candidate budget
(with a 1e-13 floor); otherwise the result fails rather than certifying the
candidate. Energy, angular/linear momentum, passive specific energy, radius and
phase are reported where meaningful. The Newtonian source-energy field is a
diagnostic, not an asserted conserved Hamiltonian for modified-force models.

The algorithm limits are deliberate and visible:

- Adaptive KDK scheduling is not claimed globally symplectic. Encounter refinement
  uses 0.005 of the minimum orbital/crossing time and global subdivision, not a
  local regularisation or collision algorithm.
- Yoshida's negative middle step is used only for conservative collision-free
  fixtures.
- Kepler drift is the unperturbed building block, not a complete planetary
  Wisdom-Holman implementation. It handles elliptic, parabolic and hyperbolic
  universal-variable propagation within its bounded convergence domain.
- Radau IIA has formal order 15 and verified degree-14 quadrature moments. Its
  bounded fixed-point solve is an experimental small-system reference. It is not
  a port of IAS15, its predictor, adaptive controller or long-time accuracy claims.
- The 1PN model is a fixed spherical primary/test particle correction with
  synthetic `c=100 m/s` so the effect can be measured. J2 uses a fixed +Z spin,
  `J2=0.001` and radius 0.2 m. Radiation uses constant radial `beta=0.001`, excluding
  shadowing and Poynting-Robertson drag. All use RK4, including velocity-dependent
  1PN forces, and compare against the same physical model at finer steps.
- The tree is Barnes-Hut monopole with theta 0.3, leaf size 4 and depth cap 24,
  rebuilt each sweep. It is not FMM. Cells containing a target are never accepted
  as aggregate self-forces.
- Reciprocal seeds are explicitly FP32 sqrt/reciprocal calculations, not an
  undocumented hardware estimate instruction. The FP32 forecast runs on the CPU;
  it does not claim GPU/NPU execution. Successful acceptance never replaces a live
  authoritative world.

## Qualification and retained negative evidence

Local host qualification includes analytic orbit/reversal tests, observed
second-/fourth-order convergence, Radau coefficient moment checks, scalar parity
including odd target tails, exact worker partition determinism, symmetric-force
momentum balance, and passive/source isolation. AArch64 Android cross-checking
establishes intrinsic compilation only; real-device timing and execution remain
separate evidence.

The NEON source uses explicit register-lane inserts/extracts instead of temporary
load/store arrays. Before/after optimized AArch64 Android crate assembly from
rustc 1.100.0 / LLVM 23.1.5 showed that the compiler already eliminated those
temporary arrays. The combined dispatch function retained the same static stack
reference count (137), including wide-block register spills. This review improves
source clarity and the pointer boundary; it does not demonstrate a removed
store-to-load forwarding bottleneck or a performance gain. Final APK assembly
and physical-device measurements remain separate qualification.

An exploratory close-encounter window of 0.25 s (`dt=1/256`, 64 steps) exposed
real limitations: adaptive and encounter-refinement work budgets exhausted;
fixed-step Yoshida missed its accuracy budget; and coarse/fine reference results
disagreed. That is why this bounded interactive fixture uses a shorter declared
window and the report now verifies reference convergence explicitly. The work
limits and tolerances were not loosened to convert those failures into passes.
Longer, harder encounter qualification needs a converged independent reference
and its own workload/cancellation budget before being promoted into this suite.

## Method references

- [Yoshida, Construction of higher order symplectic integrators (1990)](https://doi.org/10.1016/0375-9601(90)90092-3).
- [Barnes and Hut, A hierarchical O(N log N) force-calculation algorithm (1986)](https://www.nature.com/articles/324446a0).
- [NASA NAIF two-body universal-variable propagation contract](https://naif.jpl.nasa.gov/pub/naif/toolkit_docs/C/cspice/prop2b_c.html).
- [Rein and Spiegel, IAS15 (2015)](https://arxiv.org/abs/1409.4779), for the
  distinction between a general Gauss-Radau collocation experiment and the
  specialised IAS15 method. No IAS15 implementation was copied or claimed here.
