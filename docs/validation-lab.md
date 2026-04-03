# Validation Lab

This repository uses a dispatch-only remote validation workflow so we can keep
heavy build/test work off the local Orchard host and use GitHub-hosted runners
for measurement where possible.

## Current workflow

Workflow file:

- `.github/workflows/validation-lab.yml`

The workflow currently supports three lane families:

1. `wrapper-bootstrap`
   Generates `gradle/wrapper/gradle-wrapper.jar` remotely from the distribution
   declared in `gradle/wrapper/gradle-wrapper.properties`, uploads the wrapper
   files as an artifact, and can optionally commit them back to the branch.
2. `core-jvm`
   Runs `check` for the pure JVM modules:
   `core-math`, `core-model`, `core-simulation`, and `render-core`.
3. `android-assemble`
   Installs Android SDK/NDK/CMake packages on a GitHub-hosted Ubuntu runner and
   attempts `:app:assembleDebug`.

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
  Wider checkpoint modes. For the current repository surface they currently use
  the same lane bundle as `frontier`, but they are intentionally labeled as
  milestone/checkpoint passes rather than default iteration loops.

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
- `android-host`
  Run only Android assembly.
- `full`
  Run all currently-defined lanes.

## Wrapper generation

Wrapper generation is now a bootstrap-only concern rather than part of every
routine validation pass. The repository can regenerate and validate the wrapper
remotely when needed, but steady-state targeted/frontier loops should not spend
extra runner time on it unless `write_wrapper=true` or the explicit
`lane_set=bootstrap` path is being used.

The workflow bootstraps Gradle without relying on `./gradlew` by:

1. reading `distributionUrl` from `gradle/wrapper/gradle-wrapper.properties`
2. downloading that Gradle distribution directly
3. running `gradle wrapper --gradle-version 8.13 --distribution-type bin`

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
