# Mainline integration record: 16–18 November 2026

This is a dated snapshot of **all 51 Git commits** on canonical `main`
between Monday 16 November 00:00 AEDT and the observed head
`b2e3dbed9e73e98fd6d749d71ee7f2c7a29b6d74` (18 November AEDT).
The history was checked using the repository commit API; the existing
[17 November documentation reconciliation](https://github.com/sednalabs/solar-gravity-lab/pull/241)
already classifies much of the first two days. This record groups the landed
changes by capability rather than repeating 51 commit messages. It is an
integration inventory, **not** a release manifest or physical-phone proof.

## Landed source and user experiences

- **16–17 November, Earth–Moon lab:** Opening Earth framing, scene-first
  navigation, portrait/landscape controls, camera picking, depth-tested native
  rendering, search and selection, visible document roots, resilient rotation,
  undo framing and startup readiness. Multi-step changes addressed native
  screenshot and pixel assertion failures; later fixes kept Objects available
  during catalogue loading, distinguished loading from empty search, and
  avoided cloning the entire simulation world for direct body inspection.
  The complete delivery merged via
  [PR #237](https://github.com/sednalabs/solar-gravity-lab/pull/237)
  at `bd79d5deb2`. See [Earth–Moon refinement](earth-moon-refinement-20261116.md)
  and [repair checkpoint](repair-checkpoint-20261117.md).
- **Probe/science workflow:** Atomic authored mass and source-mass commands,
  gravity backreaction only when separately enabled, paused edits and preserved
  intent, probe previews, portable `.sglexp` import/export, local saved-world
  continuity and recovery, and distant tracer/native spacecraft ownership.
  The scientific world remains Rust-authoritative. Display/inertial mass is
  not automatically gravitational source mass.
- **Physics and renderer evolution:** Dated commits refined integration
  policy and 3D Vulkan compute, restored an observed Arm per-worker grain
  crossover, reduced redundant solver work and corrected near/far tracer
  ownership. The dedicated
  [PR #242](https://github.com/sednalabs/solar-gravity-lab/pull/242)
  (merge `54b8d2036b9a03cc4b9587be0b86113db5bbbe6a`)
  implemented native FP64 NEON two-target passive batching, preserving
  source-order and scalar-oracle constraints.
- **18 November CPU/GPU programme:**
  [PR #245](https://github.com/sednalabs/solar-gravity-lab/pull/245)
  (merge `1a273918f97fbd432309f74470fde2a77d63a408`)
  added the cached four-target NEON approach with eight-substep crossover,
  OS-qualified Arm/topology census and ADPF hints, a 43-feature Arm catalogue,
  optional Qualcomm tile/image workloads, subgroup compaction, BDA and Vulkan
  synchronization2, calibrated GPU timestamps and optional pipeline statistics.
  See [hardware qualification](hardware-maximisation-20261118.md),
  [CPU/topology](cpu-cache-topology-20261118.md), and
  [Vulkan runtime qualification](vulkan-runtime-qualification-20261118.md).

## Landed documentation, security and CI changes

- **16 November release/validation hygiene:** bounded Android SDK setup and
  slower-download handling, release notes/changelog asset preservation, native
  journey/rotation test corrections and source-specific evidence were merged.
- **17 November security:**
  [PR #239](https://github.com/sednalabs/solar-gravity-lab/pull/239)
  and [PR #240](https://github.com/sednalabs/solar-gravity-lab/pull/240)
  introduced and corrected a tightly scoped, time-limited informational OSV
  exception for the `pin-utils` transitive dependency in the Gradle cache
  service. The exception expires on **17 February 2027**; tracked issue
  [#238](https://github.com/sednalabs/solar-gravity-lab/issues/238)
  remains the replacement path. Other advisories are not suppressed.
- **17 November documentation:**
  [PR #241](https://github.com/sednalabs/solar-gravity-lab/pull/241)
  reconciled README/module docs, distinguished active Rust contracts from
  historical reset-era handoffs, documented the native 3D camera/compute
  path and widened Markdown checks. Those earlier documents remain dated;
  they are not instructions to undo subsequently landed changes.
- **18 November expensive hardware CI:**
  [PR #247](https://github.com/sednalabs/solar-gravity-lab/pull/247)
  (merge `d14ea118988eacfa64cf6b195846e552bb45b0ba`)
  made `hardware-optimisation.yml` **workflow_dispatch only**. Benchmark,
  shader compiler and vendor-probe scripts remain available by manual launch.
- **18 November validation routing:**
  [PR #248](https://github.com/sednalabs/solar-gravity-lab/pull/248)
  (merge `b2e3dbed9e73e98fd6d749d71ee7f2c7a29b6d74`)
  avoids full Rust/Arm/Android runtime CI for changes wholly owned by docs
  or other tooling consumers, on PRs and main pushes. Product/shared/unknown
  changes and merge-group checkpoints keep required validation.

## Proof versus published and device state

- **Implemented:** all software changes above are in `main`, as at the
  observed head. The versioned camera/scene/physics contracts and optional
  execution gates are source truth, not claims about phone hardware.
- **Hosted tested:** [PR237 validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943),
  [PR245 final validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44279625030)
  and [hardware proof](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44279624931),
  with successful source-identical replays
  [44283854151](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44283854151)
  and [44283854331](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44283854331).
  Android emulator/Vulkan SwiftShader passing is not Adreno vendor execution.
- **Published:** the latest observed GitHub prerelease remains
  [`v0.2.0-alpha.2`](https://github.com/sednalabs/solar-gravity-lab/releases/tag/v0.2.0-alpha.2),
  published 16 November. Later source/hosted tests **do not imply** a newly
  installable signed APK or continuous upgrade signing.
- **Not accepted:** physical Galaxy S25 camera/picking UX, actual Oryon cpuset,
  Qualcomm tile/image availability, sustained frame and thermal/energy
  characteristics; Ops **w3455** remains blocked on device access.

## CI evidence reuse limits and next work

The existing [Validation Lab](validation-lab.md) reuses certain successful
**same-PR** exact-head evidence; `ready_for_review` with identical source can
skip expensive runtime jobs. PR #248 routes unaffected docs/other-consumer
changes away from product tests, even on `main` pushes. It does **not** reuse
arbitrary passed PR results across a squash merge: head commit and merge SHA
can differ despite identical Git tree hashes. A targeted PR proof may omit
more exhaustive default-branch lanes and independent CodeQL security uploads.

Ops **w18993** covers future trusted per-lane cross-merge proof reuse using
verified tree identity, test-input/toolchain hashes, source provenance, full
lane coverage and fresh check statuses without weakening protection. Ops
**w3454** tracks integration; the completed hosted implementation lanes are
**w18987**, **w18988** and **w18989**. The physical S25 item is **w3455**.

If new commits land after this snapshot, update this record or append a dated
successor; do not rewrite historical benchmark evidence or call an incomplete
release current.
