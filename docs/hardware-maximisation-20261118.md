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
| Advanced Vulkan | Compiled pipeline statistics, GPU duration/calibrated timestamps; evaluate concrete BDA workload | Report support, enablement, implementation and activation independently |

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
