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
  Default fail-small slice. In `auto` mode this runs wrapper bootstrap plus the
  pure JVM lane.
- `frontier`
  Bounded broader pass. In `auto` mode this adds Android assembly to surface the
  next independent blocker family.
- `broad` / `full`
  Wider checkpoint modes. For the current repository surface they use the same
  lane bundle as `frontier`, but they are intended as explicit milestone passes
  rather than the default loop.

## Lane Sets

- `auto`
  Choose lanes from the selected profile.
- `bootstrap`
  Run only wrapper generation.
- `core-jvm`
  Run wrapper generation plus the pure JVM checks.
- `android-host`
  Run wrapper generation plus Android assembly.
- `full`
  Run all currently-defined lanes.

## Wrapper generation

The repository currently does not include `gradle/wrapper/gradle-wrapper.jar`.
That is intentional for now so it can be regenerated and validated remotely.

The workflow bootstraps Gradle without relying on `./gradlew` by:

1. reading `distributionUrl` from `gradle/wrapper/gradle-wrapper.properties`
2. downloading that Gradle distribution directly
3. running `gradle wrapper --gradle-version 8.13 --distribution-type bin`

This means the wrapper JAR can be generated entirely on GitHub Actions without
spending local Orchard compute.

## Recommended rollout

1. Dispatch `profile=targeted`, `lane_set=bootstrap`, `write_wrapper=true` on a
   validation branch to land the wrapper JAR remotely.
2. Dispatch `profile=targeted`, `lane_set=core-jvm` on the same branch and get
   the pure JVM baseline green.
3. Dispatch `profile=frontier`, `lane_set=auto` to probe the Android lane and
   surface the next blocker family without widening further than necessary.
