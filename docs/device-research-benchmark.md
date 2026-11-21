# Physical-device research build

The **Solar Gravity Lab Research** APK is an isolated laboratory for the 61
hardware and numerical opportunities in
[`benchmark_catalogue.json`](../clients/android/app/src/main/assets/benchmark_catalogue.json).
New research releases use a version-scoped application ID, such as
`com.sednalabs.solarlab.benchmark2004002`, and a versioned launcher label. They
install alongside the normal preview and the original benchmark.1 package
(`com.sednalabs.solarlab.benchmark`), preserving earlier session stores despite
CI preview signing-key changes. Each version owns separate app storage. Its synthetic experiments have
no runtime/checkpoint handle and cannot overwrite a saved world in the normal app.

This is research software. A case being present, runnable, or compiled is not
proof of device support, a speed improvement, or a scientifically qualified
replacement. Every opportunity has an explicit coverage status. The full list
is visible in the app's Experiments tab, including the remaining implementation
gaps. No benchmark result changes production dispatch automatically.

The [61-row coverage table](device-research-coverage.md) provides a readable
mapping from the original opportunity numbers to each implemented scope.
The [first S25 result and resulting changes](device-research-s25-first-run.md)
record the physical evidence that motivated protocol version 2 and cache persistence.
The [first benchmark.2 recording](device-research-s25-benchmark2.md) exposed
first-position bias in fixed ABBA blocks and motivates protocol version 3.
The [quota-stopped benchmark.2 Full recording](device-research-s25-full-quota.md)
motivates bounded comparison cohorts, earlier dense-workload coverage and
compact environment records.
The installed APK's manifest records its actual protocol; a newer analyser
does not turn an older recording into a counterbalanced run.

**Follow-up source status:** the Full cohort scheduling, dense-workload priority
and compact environment encoding described below are intended for
**benchmark.4**. At this source checkpoint, its exact-source hosted validation,
remotely built APK and physical-device acceptance are pending.
[PR #249](https://github.com/sednalabs/solar-gravity-lab/pull/249) tracks the
current exact-source proof and release status. These source changes alone do
not establish delivery or a measured performance improvement.

## First recording on a Galaxy S25 Ultra

Install the research APK from its GitHub prerelease. Keep the release's build
provenance and SHA-256 asset if you keep the APK separately. The app records its
own installed APK hash, source commit, package, version, firmware and driver
identity in every session, so results can be matched to the exact build. Keep
the earlier app installed until its sessions are exported; no uninstall is needed
to install a new version-scoped research release.

Start with **Quick comparison** using system placement, the default display
mode and ADPF hints off. Protocol version 3 seeks at least 30 seconds of active
observation and five complete counterbalanced units per eligible pair, within a
three-minute ceiling. Each unit contains one ABBA and one BAAB block, with A
always the baseline and B always the candidate. Both blocks must complete in
one thermal epoch. `SKIP` is useful evidence: it states which feature, datatype, driver or
public API was unavailable. A `FAIL` preserves the attempted result and error;
it is never interpreted as a fast candidate.

After the phone has cooled, run **Full investigation**. It seeks at least two
minutes of active observation and five complete counterbalanced units per
eligible pair, with a ten-minute ceiling. The benchmark.4 source divides the
full declared schedule into cohorts of at most 32 fixture/step/placement pairs.
It completes the five-unit target for the remaining active pairs in one cohort
before advancing. Each pair receives one block per local round, and the first
slot rotates within the cohort. Alternating local rounds preserve ABBA/BAAB
counterbalancing and fixed baseline/candidate roles.

Cohort closure does not wait for the session's two-minute minimum. If a small
targeted Full schedule finishes its cohorts sooner, further passes continue
real measurements with increasing local round numbers. The global duration,
8,192-block and journal limits remain in force. A complete early cohort does
not complete the whole catalogue: the summary retains every planned pair,
unvisited counts, retired pairs, partial units and target shortfalls. Normal
completion closes active complementary units. Budget expiry or cancellation
retains unmatched evidence without counting it as a complete unit.

Full prioritises step counts **64, 1, 8, 2, 4, 16, 32**, visiting compatible
fixtures within each step and interleaving candidates before cohort partition.
This brings dense 192/256-body comparisons into earlier work while retaining
the complete fixture/step matrix. Hardware cases keep their declared one-step
scope and appear in the first candidate wave. Quick and Sustained keep their
existing whole-catalogue rotation. Use a targeted experiment run to investigate
a particular case and its declared control.

For sustained behaviour, use **15 minute stability run**. It repeats representative
CPU/GPU workloads, including the dense 256-source CPU fixture where both sides
admit it. The app pauses at Android's severe thermal state and requires 30 seconds
at light or no thermal pressure before resuming. It does not reduce numerical
accuracy to keep a benchmark running. A block spanning a cooldown is excluded
from clean comparisons.

For useful A/B evidence, hold brightness, charging state, display mode and other
phone activity steady. Unplugged runs can provide whole-phone battery depletion
observations; plugged runs remain useful for correctness and timing but have a
different energy interpretation. Repeat a cooled Full or targeted run with ADPF
enabled, changing only that option. Do not infer a benefit from one favourable
sample or from different fixtures being compared.

Choose **Record observatory** to capture the actual interactive renderer. Load
the scene of interest, pan/zoom, change the population, and exercise playback.
Use the recording badge's Stop action or return to the research screen to finish.
Recording ends when the activity leaves the foreground and does not silently
restart. A 30-minute budget and bounded queue keep the capture finite.

The updated observatory also records `runtime_observation` counters once per
second. These distinguish periodic summary polls, unchanged paused exports
avoided, packet export attempts, successful exports and failed exports. Counts
are cumulative within one bridge instance, identified by `scope_id`; they are
not reset by reconnecting or switching scenarios. Unavailable counters are null.
The analyser subtracts only adjacent valid observations with the same scope,
excluding startup work before the first observation and recreation/reset gaps.

To check paused-scene reuse, record a scene while paused, pan or zoom, play it,
then pause again and stop recording. A periodic export is suppressed only after
the current packet decoded and released successfully, while the native summary
still reports the same paused revision. Commands and explicit refreshes still
export; failed or stale decodes cannot authorise reuse. Summary polling and
native rendering continue. An avoided export count does not establish CPU time,
GPU-frame or energy savings by itself.

The renderer now also retains a compatible Vulkan pipeline cache in app-private
storage across renderer recreation. Record an observatory session, stop it, then
reopen and record the same scene. `pipeline_cache_persistence` records whether
compatible disk bytes were supplied, rejection/fallback reasons, byte counts,
load/save intervals and cumulative graphics/compute creation-call intervals.
Those intervals are CPU host time. They are neither GPU frame time nor a driver
cache-hit count. Resize preserves the in-memory cache; disk I/O occurs only at
initialisation, pipeline rebuild and teardown, never in steady-frame rendering.
The complete stored file is bounded at 16 MiB and replaced atomically. Driver or
device identity changes, invalid data and seed rejection fall back to an empty
cache. Repeated status callbacks do not count as extra loads or saves.

The **Sessions** tab offers Save ZIP through the Android document picker and
Share ZIP through the system share sheet. Export the quick, full, sustained and
observatory recordings separately and attach them for analysis. The app does
not automatically upload data. An observatory recording can contain the scenario,
checkpoint and body labels you worked with; a synthetic benchmark uses generated
fixtures.

## What the device records

| Evidence | Recorded scope |
|---|---|
| Source and binary identity | Full source SHA, build type, app/version, installed APK and split hashes |
| Phone and OS | Model/SoC, firmware fingerprint, Android/security patch, kernel page size, supported display modes |
| CPU execution environment | App-process auxv, legal affinity, readable topology/cache/frequency observations, actual placement result and worker-thread scope |
| GPU and accelerators | Vulkan driver/features/limits and activation reasons, public OpenCL availability, explicit NNAPI device qualification |
| Controlled runs | Calibration, warmups, alternating ABBA/BAAB order, local round/unit and chronological position, fixture/steps/requested and effective repeats, native timer scope, JNI wall and calling-thread CPU time, checksums, independent-oracle errors and invariants |
| Environment | Thermal state, headroom, battery current/charge/energy/voltage/temperature, power-save mode and observed display refresh; immutable snapshot source times and per-use observation ages |
| Live observatory | Runtime revision/scenario/body counts and playback observations; full native renderer message and hardware summary; Vulkan timing/calibration fields |
| Capture quality | Queue drops, writer records/bytes/time, sampling cadence, interrupted runs, explicit storage/time budgets and finalisation status |

Unavailable observations remain `null`, `unavailable`, or an explicit `SKIP` with
a reason. Zero is not substituted for an inaccessible sensor. Reading a sysfs
path is attempted only with the app's ordinary permissions. There is no root,
private-driver access, privileged CPU control or serial/IMEI collection.

The journal envelope uses monotonic time at the serialised append point. Live
callbacks retain their separate source timestamp, so event delivery delays do
not silently change the observation clock. An event is durably recorded before
entering native code; after a native crash, the last uncompleted sample can be
identified. Recovery retains complete newline-delimited records and handles a
torn tail without rewriting old-boot records with a new boot's clock.

The benchmark.4 source declares `environment_encoding` as
`journal_snapshot_references_v1`. It writes an immutable `environment_snapshot`
definition durably before any sample references it. Each before/after use keeps
its `snapshot_ref`, `observation_age_ns` and `observation_policy`; the referenced
sensor payload keeps its original monotonic timestamp. Reusing a cached
observation does not imply another sensor read. Native result bodies, calibration,
warmups, periodic environment observations and durable `sample_start` records
remain available. A failed or cancelled definition write cannot authorise a
subsequent reference. This removes repeated sensor payloads while preserving
the evidence needed to reconstruct each sample's conditions.

Each session's **uncompressed app journal** is bounded at approximately 256 MiB;
this is an app collection limit, not the phone's total or remaining disk space.
The bound is checked between atomic records/work items, with finalisation
retained, so the final journal can slightly exceed that threshold. Export is a
flat ZIP with an index covering each member's size and SHA-256. The FileProvider
exposes only the research export cache. The analyser reads ZIP members without extracting
them and rejects extra members, duplicates, changed hashes, oversized bundles,
corrupt completed records and symlinked directory members.

## Measurement rules

Each candidate uses a declared control from the **same APK**. This is not an
earlier-commit binary comparison. CPU fixtures are deterministic synthetic SI
worlds, including sparse 3-source/1,024-body, 15-source/365-body, all-massive
192/256-body, bounded close-encounter and analytic two-body cases. Details and
method limitations are in the [Rust engine guide](../engine/benchmarks/README.md).

One-repeat A/B calibration pilots choose a shared native batch targeting 5 ms,
bounded at 11 CPU repeats or 16 hardware iterations. Both roles are warmed at
the resulting batch size. Native clamps trigger a common downward adjustment
and rewarm both roles. The final batch is frozen for that pair across all rounds;
it never changes the fixture, timestep or scientific horizon. Calibration and
warmup observations remain in the journal but are excluded from timing comparisons.
Any failed pilot or warmup vetoes a subsequent performance claim. Unavailable or
failed pairs retire individually, including placement failures; unrelated pairs
that share a baseline still run. The summary retains target shortfalls and the
hard limits of 4,096 Quick, 8,192 Full and 65,536 Sustained measured blocks.

Protocol-v3 blocks run **A1, B1, B2, A2** on even pair-local rounds and
**B1, A1, A2, B2** on the following odd rounds. A always means the declared
baseline and B the candidate. The offline analyser retains each block's
geometric paired ratio `sqrt((B1/A1) * (B2/A2))`, then combines the two
complementary block ratios in log space into one eight-sample unit. It resamples
complete units, not percentages or individual native repeats. Different step
counts, fixtures and placements remain separate comparisons. Both wrapper and
native correctness results must pass. Incomplete units, cooldown crossings,
severe/unknown thermal state, charging changes and placement changes are excluded
from the clean cohort.

Numerical/approximate algorithms are labelled accuracy-versus-cost experiments,
not automatically ranked as equivalent-kernel speedups. Added 1PN, J2 and
radiation forces explicitly report a different physical model from the Newtonian
timing control; their timing is a cost observation, and their accuracy uses a
separate converged same-model reference.

Native CPU time includes the implemented integration work and its scratch,
packing, tree and worker costs. It excludes fixture generation, JNI, reference
calculation, invariant calculation and JSON. Rust `elapsed_ns` is the lower
median of its retained repeat durations; `kernel_total_ns` is their sum. Native
hardware cases declare their iteration and submission boundaries. Native elapsed
values retain their declared scope, including hardware totals over iterations.
Matched A/B samples must have the same requested and effective batch. Neither
pooled results from different batches nor CPU medians and GPU totals represent
equal work. Native repeats are subsamples, not independent counterbalanced units.

Most ordinary CPU candidates now use the production-selected solver as their
timing control because the S25 export demonstrated that its existing NEON path
was already running. Production-selected versus scalar remains a dispatch check;
special algorithm, precision and matched-layout experiments retain their declared
controls. The independent FP64 scalar oracle remains unchanged. The analyser
reports GPU timestamp intervals, JNI wrapper wall time, calling-thread CPU time
and native setup separately. Calling-thread CPU excludes internal workers and
GPU execution; wrapper time includes native setup, oracle work and JSON handling,
so it must not be labelled pure JNI overhead.

The CPU/GPU scheduling experiment deliberately uses a different scope: complete
cold end-to-end serial or concurrent jobs, including setup and validation. It
reports the component timings and overlap interval and does not claim to model
a warmed production frame. OpenCL, NNAPI and pipeline construction similarly
report their preparation boundaries rather than hiding compilation costs.

An ADPF session hints the dedicated native-calling Linux thread. Internal worker
TIDs that are not registered are not claimed as hinted. Affinity applies to the
calling thread and subsequently created workers; existing unrelated pools are
not silently migrated. Placement is read back and restored on that same thread.

Battery counters measure the entire device, including display, other processes,
reference work and the logger. They are not CPU/GPU rail measurements or
per-kernel joules. Current-derived power retains the driver's sign. The analyser
reports net battery-energy changes only when the counter is available and
separately labels unplugged depletion. Short runs may be below a counter's
resolution.

Live Vulkan timestamps describe the last completed native GPU query sampled by
the callback. Duplicate query counters are removed by the analyser. Callbacks
can skip intervening frames; their quantiles are not a complete presentation
trace. UI Choreographer intervals remain a separate population. Neither is
renamed to hardware-present frame time. Raw messages are retained to permit
more detailed follow-up analysis.

## Analyse an exported session

Python 3 and the standard library are sufficient:

```sh
python3 tools/analyse_benchmark_report.py /path/to/research-session.zip --output dist/phone-session
```

This writes JSON with all exclusions, timing summaries, error metrics,
observational summaries and a Markdown readout. Five or more clean, complete
counterbalanced units are required for a preliminary protocol-v3 candidate
decision. The analyser reconstructs units from raw samples, combines the two
complementary block ratios in log space and resamples complete units. It retains
raw block effects and excludes failed, unmatched or incompatible units. Legacy
fixed-ABBA recordings remain descriptive and receive explicit order diagnostics.
The updated analyser accepts both earlier inline environment values and the
declared compact reference encoding. It resolves references in journal order
before applying the existing environmental rules; unknown or forward references,
duplicate definitions, conflicting source observations and malformed ages are
rejected. Expanding a reference preserves its source clock and per-use age.
Encoding compatibility does not upgrade a protocol-2 run into protocol v3.
The seeded bootstrap is reproducible; it does not eliminate systematic drift.
A practical candidate currently requires at least a 5% median time reduction
and an upper 95% bootstrap limit below -1%. These are research triage thresholds,
not automatic production acceptance criteria.

Use repeated sessions to assess thermal drift, confidence and accuracy across
workloads. Select the next targeted experiment from the evidence. Promotion needs
separate production integration, whole-frame checks, physical-device repeats and
scientific acceptance over the intended domain.

## Hardware reciprocal-square-root comparison

Opportunity 58 includes `neon-rsqrt-refine-2` and `neon-rsqrt-refine-3`. These use
actual Arm FP64 `FRSQRTE` estimates followed by two or three `FRSQRTS` refinement
steps. Their timing control is `neon-block-4`, which uses the same four-target
layout and accumulation with ordinary square root and division. Correctness is
checked independently against the production FP64 scalar solver.

Use Full investigation or select either case for a targeted comparison. The
declared scaled-state error budgets are `2e-8` and `2e-11`, respectively. Raw
errors, repeat checksums, instruction configuration and actual kernel identity
remain in the native result. These are accuracy/cost experiments; a faster
sample does not establish an equivalent-accuracy production replacement. The
portable FP32-seed experiments remain separate cases. Unsupported processes
report SKIP, with no scalar implementation presented as hardware execution.

The remote Arm64 proof checks domain boundaries and odd target counts, inspects
the exact executed release binary for the FP64 estimate/refinement and control
instructions, and runs all six admitted fixtures at seven step counts with
three repeats. Hosted Arm64 evidence qualifies that implementation and sampled
domain; physical S25 performance and energy remain separate measurements.

## Implementation boundaries

The 61-opportunity catalogue is the exact coverage ledger. A scoped primitive
must not be described as the full proposed system. Examples include:

- Real predicated SVE arithmetic is distinct from a complete SVE gravity solver;
  SME/SME2, WFxT and CSSC consumers remain explicit gaps where only discovery exists.
- Descriptor-array experiments are distinct from a descriptor-buffer renderer.
  Indirect compute dispatch is distinct from a complete indirect-draw path.
- AHardwareBuffer import is a bounded sharing proof, not a completed OpenCL/NPU
  zero-copy render pipeline. NNAPI uses an explicitly selected accelerator when
  one supports the complete dense layer; it never silently calls CPU fallback
  while claiming an NPU result. There is no trained orbit surrogate.
- Universal-variable Kepler propagation is a building block for planetary
  splitting, not a complete Wisdom-Holman method. Radau IIA collocation is not
  an IAS15 port. Barnes-Hut is not FMM.
- FP32 forecasts, passive extrapolation and modified-force fixtures live in
  scratch worlds. They never commit predicted states into authoritative saved
  experiments. The normal FP64 physics path remains the reference.
- `tools/run_research_pgo.sh` is a host-only PGO training/comparison tool with
  immutable source/compiler/binary hashes. It does not supply an Android-trained
  profile or claim a measured BOLT benefit.

## Build and validation

The benchmark variant is release-derived, minified, separately identified and
signed with the project's preview/debug signing configuration. Normal release
builds do not launch the laboratory. Native JNI identities are checked after R8.
The publication workflow embeds the pinned QCOM shaders, retains compiler/source
and binary hashes, checks Android ELF and ZIP 16 KiB alignment, executes portable
native controls on software Vulkan and launch-smokes the packaged APK.

Local/hosted unit, oracle, ABI and emulator passes establish the scopes actually
executed there. Unsupported vendor cases remain skips. A 16 KiB aligned ELF is
structural compatibility evidence, not proof of execution on a 16 KiB phone.
Hosted Arm or software Vulkan timing must never be translated into S25 frame
rate or battery gains. The physical acceptance record is the exported phone
session and the subsequent analysis.

Research source and review: [PR #249](https://github.com/sednalabs/solar-gravity-lab/pull/249).
