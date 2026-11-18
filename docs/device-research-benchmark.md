# Physical-device research build

The **Solar Gravity Lab Research** APK is an isolated laboratory for the 61
hardware and numerical opportunities in
[`benchmark_catalogue.json`](../clients/android/app/src/main/assets/benchmark_catalogue.json).
It uses application ID `com.sednalabs.solarlab.benchmark`, so it installs alongside
the normal preview. It owns separate app storage. Its synthetic experiments have
no runtime/checkpoint handle and cannot overwrite a saved world in the normal app.

This is research software. A case being present, runnable, or compiled is not
proof of device support, a speed improvement, or a scientifically qualified
replacement. Every opportunity has an explicit coverage status. The full list
is visible in the app's Experiments tab, including the remaining implementation
gaps. No benchmark result changes production dispatch automatically.

The [61-row coverage table](device-research-coverage.md) provides a readable
mapping from the original opportunity numbers to each implemented scope.

## First recording on a Galaxy S25 Ultra

Install the research APK from its GitHub prerelease. Keep the release's build
provenance and SHA-256 asset if you keep the APK separately. The app records its
own installed APK hash, source commit, package, version, firmware and driver
identity in every session, so results can be matched to the exact build.

Start with **Quick comparison** using system placement, the default display
mode and ADPF hints off. This is a roughly three-minute admission and repeatability
check. `SKIP` is useful evidence: it states which feature, datatype, driver or
public API was unavailable. A `FAIL` preserves the attempted result and error;
it is never interpreted as a fast candidate.

After the phone has cooled, run **Full investigation**. Its ten-minute budget
spreads work across candidates before exploring additional fixtures and step
counts. The declared schedule and completed records identify exactly what ran.
Budget expiry is a partial result, not evidence for the unvisited cases. Use a
targeted experiment run to investigate a particular case and its control.

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
| Controlled runs | Warmups, A1/B1/B2/A2 order, fixture/steps/repeats, native timer scope, JNI wall time, checksums, independent-oracle errors and invariants |
| Environment | Thermal state, headroom, battery current/charge/energy/voltage/temperature, power-save mode and observed display refresh |
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

Each journal is bounded at approximately 256 MiB; the bound is checked between
atomic records/work items, with finalisation retained. Export is a flat ZIP with
an index covering each member's size and SHA-256. The FileProvider exposes only
the research export cache. The analyser reads ZIP members without extracting
them and rejects extra members, duplicates, changed hashes, oversized bundles,
corrupt completed records and symlinked directory members.

## Measurement rules

Each candidate uses a declared control from the **same APK**. This is not an
earlier-commit binary comparison. CPU fixtures are deterministic synthetic SI
worlds, including sparse 3-source/1,024-body, 15-source/365-body, all-massive
192/256-body, bounded close-encounter and analytic two-body cases. Details and
method limitations are in the [Rust engine guide](../engine/benchmarks/README.md).

Warmup A/B observations remain in the journal but are excluded from comparisons.
Measured blocks run A1, B1, B2, A2. The offline analyser uses the geometric paired
ratio `sqrt((B1/A1) * (B2/A2))`; it never compares different step counts or fixtures
as if they represented equal work. Both wrapper and native correctness results
must pass. Incomplete blocks, cooldown crossings, severe/unknown thermal state,
charging changes and placement changes are excluded from the clean cohort.

Numerical/approximate algorithms are labelled accuracy-versus-cost experiments,
not automatically ranked as equivalent-kernel speedups. Added 1PN, J2 and
radiation forces explicitly report a different physical model from the Newtonian
timing control; their timing is a cost observation, and their accuracy uses a
separate converged same-model reference.

Native CPU time includes the implemented integration work and its scratch,
packing, tree and worker costs. It excludes fixture generation, JNI, reference
calculation, invariant calculation and JSON. Rust `elapsed_ns` is the lower
median of its retained repeat durations; `kernel_total_ns` is their sum. Native
hardware cases declare their iteration and submission boundaries. The Android
wrapper normalises multi-iteration totals before comparing matched cases and
retains the original native result and whole-call duration.

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
observational summaries and a Markdown readout. Five or more clean ABBA blocks
are required for a preliminary candidate decision. The seeded block bootstrap
is a reproducible descriptive interval; it does not eliminate systematic drift.
A practical candidate currently requires at least a 5% median time reduction
and an upper 95% bootstrap limit below -1%. These are research triage thresholds,
not automatic production acceptance criteria.

Use repeated sessions to assess thermal drift, confidence and accuracy across
workloads. Select the next targeted experiment from the evidence. Promotion needs
separate production integration, whole-frame checks, physical-device repeats and
scientific acceptance over the intended domain.

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
