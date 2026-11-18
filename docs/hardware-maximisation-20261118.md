# CPU and GPU hardware programme, 18 November 2026

## Scope and source truth

This is the implementation record for Grant's nine-part hardware request after
[PR #242](https://github.com/sednalabs/solar-gravity-lab/pull/242), merged as
`54b8d2036b9a03cc4b9587be0b86113db5bbbe6a`. It describes work on feature
branches, not a released APK or physical Galaxy S25 acceptance. Rust FP64 remains
the authoritative state. Optimisations must preserve source ordering, numerical
contracts, passive/source promotion and the renderer's camera/picking epoch.

Ops programme **w3454** contains CPU **w18987**, Qualcomm/subgroups **w18988**,
and Vulkan runtime **w18989**. The integration branch is
`perf/hardware-maximisation-20261118`. Each lane publishes recoverable source
checkpoints, test evidence and outstanding hardware gates.

| Requested area | Concrete work | Qualification |
|---|---|---|
| Cache-optimised NEON | Packed hot-source scratch and four-target register blocking, retaining FP64 sqrt/div and per-target source order | Frozen PR242 bitwise oracle, staged/bulk and mass-change tests; same-host Arm benchmark |
| Oryon topology | Discover allowed cores, capacity/frequency and cache sharing; measure valid affinity masks and apply public task-duration hints | No assumed CPU numbering or unrestricted cpuset; physical thermal/frame test required |
| Obscure Arm features | Qualify OS execution support and datatype requirements, including SME FP64 | Capability discovery is separate from implemented solver activation |
| QCOM tile shading | Bounded offscreen tile workload qualification | Device feature, shader/toolchain and tile visibility/lifetime checks |
| QCOM tile heap | Temporary far-tile counters with bounded heap budget and portable allocation fallback | Tile allocation requirements, legal usage and submission lifetime |
| QCOM image processing | Bounded image-processing workload qualification | Feature, format, sampler and device execution requirements |
| Vulkan subgroups | Ballot-based cooperative medium compaction and far telemetry reduction | Subgroup-stage/operation checks and portable shaders |
| Synchronisation | Synchronization2 with legacy fallback, persistent coherent maps, per-image presentation semaphores | Resource ownership, Android build and validation-layer/device exercise |
| Advanced Vulkan | Compiled pipeline statistics, GPU duration/calibrated timestamps and optional BDA medium compaction | Report support, enablement, implementation and activation independently |

## Measurement contract

`hardware-optimisation.yml` compares immutable baseline and candidate binaries
on the same native Arm runner and compiler. Each affinity runs baseline,
candidate, candidate, baseline, with eleven samples per invocation and existing
warm-up/calibration. Both unrestricted and one-allowed-CPU runs preserve raw
NDJSON, binary hashes, compiler, source SHAs, CPU and affinity metadata.

The comparison reports median-of-run-medians, per-run medians, p90 summaries,
scalar controls and scalar-normalised ratios. It does not translate hosted
microseconds into phone frame rate, battery life or sustained thermal results.
Unchanged scalar paths help expose environmental drift. Performance selection
must follow the measured workload and confidence, not a universal ISA ranking.

## Recovery checkpoints

- CPU first source checkpoint `4a0eeac4fcd23eccc610adce17d070e84ae47178`,
  [PR #243](https://github.com/sednalabs/solar-gravity-lab/pull/243).
  [Validation 44271293579](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44271293579)
  passes native x64/Arm workspace, Arm ISA/census and parity checks.
- Subgroup first source checkpoint `a5c0f7ab2ddc64b1dd322d1a18e2e592331318f5`
  on `perf/qcom-subgroups-20261118`; local ABI/projection contract tests pass.
- Vulkan first source checkpoint `9ace73a1a1decff1a44ef1310fc0ac57e7eefee4`,
  [PR #244](https://github.com/sednalabs/solar-gravity-lab/pull/244).
  NDK/Android qualification is pending at this checkpoint.

Later evidence is appended as the integrated code is qualified. A test passing
on a host without an optional extension proves fallback/compilation only, not
that extension's execution on Adreno or Oryon.

## Hosted qualification and measured cache policy

The integrated checkpoint `a6251811f5dc5d47c02ebb5d4ff50a5bdeddd7e8`
passes [validation 44275712538](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44275712538)
and [hardware proof 44275712028](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44275712028).
This includes native x64/Arm correctness, ISA/census/FFI/runtime contracts,
Android JVM/lint and **21 instrumentation tests across 12 classes** with the
Vulkan backend executing under SwiftShader. It is emulator integration evidence;
no live visual or physical S25 acceptance was performed.

The optional Vulkan proof links the production Arm64 library with NDK
27.3.13750724 at API 31 and both BDA/pipeline-statistics flags enabled. Compiled
SPIR-V verifies the BDA pointer layout and record strides without requiring
`shaderInt64`. The offscreen proof compiles actual QCOM tile-shading and box-filter
instructions with pinned glslang 16.6.0, runs portable inversion and box-filter
controls with validation enabled and zero errors, and cross-compiles the
standalone Android Arm64 probe. Both vendor execution modes report **SKIP** on
llvmpipe because the required capabilities are absent.

The original cache candidate exposed a one-step sparse regression. The
[CPU crossover run 44277468936](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44277468936)
then measured all 1/2/4/8/16/32/64-step cohorts with the same paired binaries:
streaming removes the one-step loss, cached two-step sparse work still regresses,
and eight steps give a clearer benefit than four. The selected policy retains
the original streaming kernel below eight steps and uses the packed cache from
eight steps. The boundary is covered by a 7/8/10-step schedule compared bitwise
with a 25-step bulk run. This final policy requires exact-head integrated proof;
the earlier runs retain their own source identities.

Evidence archives from checkpoint `a6251811`:

| Evidence | Artifact | Archive SHA-256 |
|---|---|---|
| Optional native Vulkan and compiled BDA contract | [14415275437](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44275712028/artifacts/14415275437) | `ce1ef0e2e4056388d9d6f4a3fc16fee2efd048ef3b4daa2abe37858494ca848f` |
| QCOM shaders, software controls and Android probe | [14415000802](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44275712028/artifacts/14415000802) | `6616a1f02a795273d9a912dedb865ee925388fbab52da9488e40cd5acd5ab30a` |
| Android instrumentation | [14415477368](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44275712538/artifacts/14415477368) | `578b1b6982d0c3783c2df1bd743f70a8e8f8b9f9fd470d985f44d37ffbd2aba4` |

Physical-device qualification remains **Ops w3455**. It must capture the actual
application cpuset, Oryon placement experiments, driver and extension activation,
CPU/GPU timing, frame-time, sustained thermal and energy behaviour. BDA and
pipeline statistics remain opt-in, tile/image probes remain qualification
workloads, and SVE/SME candidates remain feature-qualified possibilities rather
than implemented force kernels.
