# Validation Lab

This repository uses a dispatch-only remote validation workflow so we can keep
heavy build/test work off the local Orchard host and use GitHub-hosted runners
for measurement where possible.

Documentation-only updates should normally use the lightweight automatic
`docs-sanity` workflow instead of manually dispatching `validation-lab`, unless
the docs change is coupled to behavior or build logic that genuinely needs a
JVM/Android proof run.

## Current workflow

Workflow file:

- `.github/workflows/validation-lab.yml`
- `.github/workflows/docs-sanity.yml` for documentation-only link sanity

The workflow currently supports seven lane families:

1. `wrapper-bootstrap`
   Generates `gradle/wrapper/gradle-wrapper.jar` remotely from the distribution
   declared in `gradle/wrapper/gradle-wrapper.properties`, uploads the wrapper
   files as an artifact, and can optionally commit them back to the branch.
2. `core-jvm`
   Runs `check` for the pure JVM modules:
   `core-math`, `core-model`, `core-simulation`, and `render-core`.
3. `physics-accuracy-telemetry`
   Runs focused JVM tests for deterministic report generation and writes
   physics-accuracy telemetry artifacts (JSON + Markdown) under
   `core-simulation/build/reports/physics-accuracy/`.
4. `android-assemble`
   Installs Android SDK/NDK/CMake packages on a GitHub-hosted Ubuntu runner and
   attempts `:app:assembleDebug`.
5. `feature-lab-unit`
   Runs Android-backed JVM unit tests for the `feature-lab` module, including
   narrow `LabSession` policy seams that are too specific for `core-jvm` and
   cheaper than a full Android assemble.
6. `physics-native`
   Runs the current native-physics proof bundle: `core-jvm`,
   `feature-lab-unit`, deterministic `physics-accuracy-telemetry`, and
   `android-assemble`.
7. `field-reliability`
   Runs the same remote proof bundle as `physics-native`, but is named for the
   product question it answers: are scheduler, physics, and Android-host seams
   still holding together well enough for sustained field testing?

## Profiles

- `targeted`
  Default fail-small slice. In `auto` mode this runs the trusted baseline only:
  the pure JVM lane. It is meant to answer one active seam question without
  widening the run or redoing unrelated bootstrap work.
- `frontier`
  Bounded broader pass. In `auto` mode this keeps the JVM baseline and adds the
  Android assembly lane in parallel so we can harvest the next blocker family
  without widening into a full milestone run.
- `broad` / `full`
  Wider checkpoint modes. `broad` currently widens the trusted baseline in the
  same spirit as `frontier`, while `full` is the explicit "run every currently
  defined lane" option, including `feature-lab-unit`, physics telemetry, and
  Android assembly. Both are intentionally labeled as milestone/checkpoint
  passes rather than default iteration loops.

The important operational difference is that `frontier` is now treated as a
mode with an explicit intent: keep the already-trusted prefix, add one wider
seam, and avoid rerunning wrapper bootstrap unless you explicitly ask for it.

## Lane Sets

- `auto`
  Choose lanes from the selected profile.
- `bootstrap`
  Run only wrapper generation.
- `core-jvm`
  Run only the pure JVM checks.
- `feature-lab-unit`
  Run only the `feature-lab` Android-backed JVM unit-test lane.
- `physics-accuracy`
  Run only the deterministic physics-accuracy telemetry/report path.
- `physics-native`
  Run the current native-physics bundle without having to ask for a full
  milestone checkpoint.
- `field-reliability`
  Run the current field-reliability proxy bundle for sustained playback and
  Android-host confidence without widening to a broader checkpoint profile.
- `android-host`
  Run only Android assembly.
- `full`
  Run all currently-defined lanes.

You can also provide an additive `lane_list` input with a comma-separated set of
lane names when you want one dispatch to cover a narrow custom mix, for example:

- `lane_set=android-host`
- `lane_list=feature-lab-unit,physics-accuracy`

## Physics telemetry dispatch

Use the explicit lane when you need the first artifact-based physics-accuracy
evidence slice without widening to Android work:

- `profile=targeted`
- `lane_set=physics-accuracy`

This path intentionally does not change `lane_set=auto` semantics. `auto`
continues to select the trusted baseline for each profile, while
`physics-accuracy` is an explicit opt-in telemetry run.

## Wrapper generation

Wrapper generation is now a bootstrap-only concern rather than part of every
routine validation pass. The repository can regenerate and validate the wrapper
remotely when needed, but steady-state targeted/frontier loops should not spend
extra runner time on it unless `write_wrapper=true` or the explicit
`lane_set=bootstrap` path is being used.

The workflow bootstraps Gradle without relying on `./gradlew` by:

1. reading `distributionUrl` from `gradle/wrapper/gradle-wrapper.properties`
2. downloading that Gradle distribution directly
3. running gradle wrapper

This means the wrapper JAR can be generated entirely on GitHub Actions without
spending local Orchard compute.

## Recommended rollout

1. Dispatch `profile=targeted`, `lane_set=bootstrap`, `write_wrapper=true` on a
   validation branch to land the wrapper JAR remotely.
2. Dispatch `profile=targeted`, `lane_set=auto` on the same branch and get the
   pure JVM baseline green.
3. Dispatch `profile=frontier`, `lane_set=auto` to probe the Android lane and
   surface the next blocker family without widening further than necessary.
4. Reserve `profile=broad` or `profile=full` for milestone checkpoints once the
   active frontier queue is under control.

## Cheap path for docs-only changes

If a change only touches `README.md`, `docs/**`, or the docs-sanity workflow
itself, let `.github/workflows/docs-sanity.yml` answer the first question:

- do the repository markdown links still resolve?
- did we avoid accidentally widening documentation edits into Android/JVM work?

That keeps documentation maintenance fast and cheap while preserving
`validation-lab` as the deliberate remote compute path for real code or build
surface changes.
