# Research opportunity coverage

Generated from the app's [canonical catalogue](../clients/android/app/src/main/assets/benchmark_catalogue.json). Status describes implemented scope, not physical-device support or a measured gain.

See the [device recording guide](device-research-benchmark.md) for execution and interpretation.

| ID | Opportunity | Coverage | Concrete scope and boundary |
|---:|---|---|---|
| 1 | Retail-device capability fingerprint | implemented | App auxv, legal affinity, cache topology, firmware, GPU/OpenCL/NNAPI drivers and actual APK hashes. |
| 2 | Physical-device performance laboratory | implemented | Bounded plans, raw samples, independent oracles, crash recovery and SHA-256 indexed ZIP exports. |
| 3 | Workload-sensitive kernel selection and calibration | tooling | Offline matched-workload analysis identifies candidates. Production dispatch does not automatically adopt unqualified profiles. |
| 4 | Oryon topology and legal core placement | implemented | System, one legal CPU and discovered high-capacity cluster. Affinity scopes the calling thread and subsequently created workers, not existing pools. |
| 5 | ADPF hints for native work | implemented | Optional ADPF hints on the dedicated native-calling Linux thread; unregistered internal worker TIDs are not claimed as hinted. |
| 6 | Thermal-aware scheduling | implemented | Sustained protocol, thermal pause/recovery hysteresis and raw battery/headroom. Production thermal policy remains unqualified. |
| 7 | Frame pacing and display-rate selection | partial | Supported display-mode requests, actual display/vsync and live-render evidence. Swappy is not integrated. |
| 8 | CPU/GPU overlap versus staggered scheduling | implemented | Equivalent fixed CPU/GPU jobs compared serially and concurrently, with cold end-to-end wall time, component oracles and overlap interval. Production scheduling remains unqualified. |
| 9 | PGO, ThinLTO and code placement | tooling | ThinLTO is active. tools/run_research_pgo.sh trains and compares immutable host PGO candidates. No Android PGO or BOLT result. |
| 10 | Packed-source four-target NEON cache | implemented | Merged PR245 production cache policy measured at step counts 1/2/4/8/16/32/64. |
| 11 | Source-count-specialised FP64 kernels | implemented | Real constant-source loops and explicit three-source FP64 NEON, independently checked. |
| 12 | NEON register-block width sweep | implemented | Actual two-lane FP64 NEON vectors with varying numbers of independent vector chains. |
| 13 | Independent sqrt/divide/FMA latency chains | implemented | Independent sqrt/divide/FMA chains with actual kernel identity and numerical errors. |
| 14 | Source-major target tiling | implemented | Source-major target tiles; source order preserved and scratch/packing charged. |
| 15 | SoA and AoSoA hot data | implemented | SoA coordinate arrays and packed source records; canonical world unchanged. |
| 16 | Symmetric dense force tiling | implemented | Unique triangular source pairs and deterministic equal/opposite updates. |
| 17 | Worker-local scratch and deterministic accumulation | implemented | Independent target slices, fixed source order, deterministic worker comparisons. |
| 18 | Worker-count and tile-size tuning | implemented | Per-sweep worker controls and bounded pools retained across one segment; pool construction/join is charged once, affinity inherited and target/source order deterministic. |
| 19 | PRFM and range prefetch | partial | Executable compiler-prefetch distance controls. Explicit RPRFM range-prefetch consumer not implemented. |
| 20 | SVE and SVE2 FP64 | partial | Actual predicated SVE FP64 arithmetic probe, app-auxv gated. Full SVE gravity solver not implemented. |
| 21 | SME and SME2 FP64 matrix assist | not_implemented | SME and independent FP64 datatype capabilities recorded. No matrix-assisted gravity kernel. |
| 22 | MOPS copy and clear | partial | Actual libc copy/clear bandwidth controls. Library instruction selection is not assumed to use MOPS. |
| 23 | LSE/LSE2/RCpc atomics | partial | Real contended counters compare memory ordering; no unsafe publication rewrite or assumed LSE/RCpc selection. |
| 24 | WFxT worker waiting | not_implemented | Capability evidence retained; no energy/latency-qualified WFxT waiting consumer. |
| 25 | CSSC and SVE2 bit permutation | not_implemented | Capability evidence retained; no dedicated CSSC or SVE2 bit-permutation consumer. |
| 26 | FCMA complex arithmetic | implemented | Actual gated FCMA FP64 complex multiplication with matching ordinary control. |
| 27 | CRC and cryptographic integrity acceleration | partial | Actual scalar/ARM CRC32. APK and export SHA-256 uses the platform provider; CRC is not cryptographic integrity. |
| 28 | PAuth, BTI and MTE | capability_only | PAuth/BTI/MTE observations only. No instrumented hardening result or speedup claim. |
| 29 | Page size and allocator behaviour | partial | Actual kernel page size recorded. Android ELF/ZIP 16KiB compatibility is verified separately; no forced page-size mode or allocator replacement. |
| 30 | Vulkan capability and activation ledger | implemented | Supported/implemented/enabled/executed distinctions, driver identity, limits, memory and fallback reasons. |
| 31 | Subgroup-cooperative compaction | implemented | Equivalent visibility and ID oracle, odd dispatch tails and actual subgroup gating. |
| 32 | Buffer-device-address compaction | implemented | Real typed physical-address compaction, allocation lifetime and readback validation. |
| 33 | Descriptor indexing and descriptor buffers | partial | Actual fixed-binding versus divergent nonuniform descriptor-array compute with exact output oracle. Descriptor-buffer extension and a complete renderer conversion are not implemented. |
| 34 | GPU-generated indirect work | partial | Real GPU-generated indirect compute dispatch. A complete indirect-draw renderer is not implemented. |
| 35 | Persistent mapping and Vulkan synchronisation | implemented | Actual persistent/remapped uploads and sync2 controls, plus merged production lifetime fixes. |
| 36 | Timeline semaphores and presentation retirement | partial | Actual isolated compute timeline completion. WSI remains binary; swapchain-maintenance retirement not implemented. |
| 37 | Pipeline cache and binary reuse | implemented | Real serialised pipeline cache reuse plus bounded app-private renderer persistence across recreation, compatibility checks, atomic replacement and observed load/save host timings. Driver cache hits, internal cache flushing and pipeline-binary extension not claimed. |
| 38 | GPU timestamps and calibration | implemented | Available GPU timestamps, host scopes and live-render calibrated timing fields preserved separately. |
| 39 | Qualcomm tile memory heap | implemented | Bounded transient tile-heap counter, same-submission lifetime and CPU readback oracle. |
| 40 | Qualcomm tile shading | implemented | Embedded pinned shaders execute ordinary/per-tile offscreen inversion. Whole-frame adoption remains unqualified. |
| 41 | Qualcomm image processing | implemented | Actual textureBoxFilterQCOM versus sixteen fetches and independent image oracle. Weighted filters/block matching absent. |
| 42 | Camera-relative coordinate precision | baseline | Existing FP64 camera-relative subtraction retained and observable. New high/low split coordinates not implemented. |
| 43 | Particle LOD and dynamic resolution | baseline | Existing near/medium/far policies and live capture retained. New calibrated adaptive LOD/dynamic-resolution controller not implemented. |
| 44 | GPU passive-population calculation | partial | Actual isolated GPU passive acceleration with CPU oracle. Live population integration/promotion handoff not implemented. |
| 45 | GPU FP64 qualification | implemented | Actual FP64 gravity shader gated by shaderFloat64, with full readback validation. |
| 46 | OpenCL comparison | implemented | Actual public OpenCL loader/device qualification and FP32/FP64 GPU kernels; inaccessible runtimes explicitly skip. |
| 47 | AHardwareBuffer sharing | partial | Actual Android BLOB allocation, Vulkan import, CPU unlock fence and foreign ownership transfer with gravity readback oracle. Cross-API OpenCL/NPU zero-copy integration is not implemented. |
| 48 | Reduced-precision visual work | implemented | Actual FP16/FP32 visual transforms with measured error; physical state remains FP64. |
| 49 | Compensated FP64 accumulation | implemented | Kahan and Neumaier force accumulation with analytic cancellation and scalar controls. |
| 50 | Reproducible pairwise reductions | implemented | Fixed binary reduction and deterministic worker target slices; changed rounding measured. |
| 51 | Error-driven adaptive timesteps | implemented | Bounded step-doubling with convergence-checked independent reference; adaptive schedule is not asserted globally symplectic. |
| 52 | Close-encounter refinement | partial | Actual global dyadic refinement from crossing/orbital times. Local regularisation and collision resolution not implemented. |
| 53 | Higher-order symplectic integration | implemented | Fourth-order Yoshida on conservative collision-free fixtures; convergence and reversal tests. |
| 54 | Wisdom-Holman style planetary splitting | partial | Universal-variable Kepler primitive covers elliptic/parabolic/hyperbolic cases. General planetary Wisdom-Holman mapping not implemented. |
| 55 | High-order Gauss-Radau reference | partial | Actual eight-stage order-15 Radau IIA collocation with bounded solve. This is not an IAS15 port. |
| 56 | 1PN, J2 and radiation force models | implemented | Synthetic fixed-primary force models, velocity-aware RK4 and same-law fine reference; explicit applicability limits. |
| 57 | Barnes-Hut and FMM | partial | Actual bounded source octree and target-containing aggregate rejection, with error measurement. FMM not implemented. |
| 58 | Reciprocal-square-root refinement | implemented | Actual NEON FP64 FRSQRTE/FRSQRTS gravity with two or three refinements, matched four-target sqrt/divide control, domain and odd-tail tests, and explicit numerical budgets. Portable FP32-seed controls retained. No production dispatch promotion or S25 gain claimed. |
| 59 | Passive acceleration/jerk extrapolation | implemented | Massive sources remain exact; passive acceleration/jerk predictions checked against FP64 segment checkpoints. |
| 60 | Mixed-precision forecasts and checkpoints | partial | CPU FP32 forecast and separate actual GPU force probe, both FP64-checked. Live GPU forecast/checkpoint handoff absent. |
| 61 | Hexagon/NPU surrogate experiments | partial | Explicit public NNAPI accelerator dense layer versus FP64, no CPU fallback. No trained orbit model or private QNN access. |
