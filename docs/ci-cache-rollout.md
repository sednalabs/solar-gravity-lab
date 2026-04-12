# CI Cache Rollout

This document captures the current Solar Gravity Lab hosted-runner cache rollout.

## Goals

- reduce repeated Android emulator cold-start cost on GitHub-hosted runners
- reduce repeated Gradle task execution across trusted CI branches
- keep Gradle remote-cache access stable while the backend is hosted on our own infrastructure
- reuse Rust/native compiler work across trusted CI runs with R2-backed `sccache`

## Current direction

The GitHub Actions-side contract is stable, but the backend design has pivoted.

- GitHub Actions talks to `https://cache.sednalabs.io/cache/`
- that hostname should be exposed through a dedicated Cloudflare Tunnel
- the cache service behind it is a self-hosted Rust HTTP service under `infra/gradle-cache-service/`
- local disk is the hot cache authority
- R2 is a backing store for mirror and read-through behavior, not the primary Gradle request path

This intentionally replaces the earlier Worker-fronted cache concept.

## GitHub Actions contract

### Repository variables

- `GRADLE_REMOTE_CACHE_URL`
- `GRADLE_REMOTE_CACHE_USERNAME`
- `SCCACHE_BUCKET`
- `SCCACHE_ENDPOINT`
- `SCCACHE_S3_KEY_PREFIX`

### Repository secrets

- `GRADLE_REMOTE_CACHE_PASSWORD`
- `R2_CACHE_ACCESS_KEY_ID`
- `R2_CACHE_SECRET_ACCESS_KEY`

Optional later:

- `GRADLE_CONFIGURATION_CACHE_KEY`

## Host-side cache service

The Rust service implements:

- `GET /cache/<key>`
- `HEAD /cache/<key>`
- `PUT /cache/<key>`

Authentication is HTTP Basic auth because Gradle already supports it cleanly.

### Service environment

Required:

- `GRADLE_CACHE_BIND`
- `GRADLE_CACHE_ROOT`
- `GRADLE_CACHE_BASIC_AUTH_USER`
- `GRADLE_CACHE_BASIC_AUTH_PASS`

Optional R2 backing:

- `GRADLE_CACHE_R2_ENDPOINT`
- `GRADLE_CACHE_R2_BUCKET`
- `GRADLE_CACHE_R2_ACCESS_KEY_ID`
- `GRADLE_CACHE_R2_SECRET_ACCESS_KEY`
- `GRADLE_CACHE_R2_REGION`
- `GRADLE_CACHE_R2_KEY_PREFIX`

### Deployment split

- app service: `infra/gradle-cache-service/deploy/systemd/solarlab-gradle-cache.service`
- tunnel service: `infra/gradle-cache-service/deploy/systemd/cloudflared-solarlab-gradle-cache.service`
- tunnel ingress template: `infra/gradle-cache-service/deploy/cloudflared/solarlab-gradle-cache.yml`

## Workflow-side cache layers

- `validation-lab` and `prerelease-apk` use AVD snapshot caching with generation `v3`
- KVM must be enabled before any emulator boot, including snapshot seeding
- Gradle jobs use `gradle/actions/setup-gradle@v5` plus the remote cache at `cache.sednalabs.io`
- Workflow helper actions were refreshed to current Node 24 builds where upstreams already provide them
- Rust-heavy jobs install `sccache` through the in-repo `.github/actions/install-sccache` action so we avoid the stale Node 20 runtime in `mozilla-actions/sccache-action` while keeping the same pinned `v0.10.0` binary
- Rust-heavy jobs configure `sccache` from repo vars and secrets and emit per-job stats into the workflow summary
- `configure_sccache.sh` also exports `CMAKE_C_COMPILER_LAUNCHER` and `CMAKE_CXX_COMPILER_LAUNCHER` so AGP-driven CMake builds can reuse the same R2-backed `sccache` backend without module-specific workflow branching
- Android/native jobs use the in-repo `.github/actions/rust-shared-cache` action so cargo registry, cargo git, cargo binaries, and workspace `target/` contents can be reused without carrying the upstream `punycode` deprecation warning

## Current measured result

The first two green targeted `validation-lab` runs on `validation/cache-acceleration-20260412` already showed the hosted-runner gains were real:

- cold cache-backed Android shell job: about `6m13s`
- warm cache-backed Android shell job: about `4m41s`
- improvement: about `1m32s`, roughly `25%` faster end to end

What changed across that pair:

- AVD snapshot cache hit replaced a fresh seed
- Gradle remote cache reused more Android tasks on the warm run
- `:app:buildSolarlabNative` hit `FROM-CACHE` on the warm run

The next hotspot after those wins was repeated `feature-lab` CMake work, so the rollout now routes CMake compiler invocations through `sccache`, preserves per-job stats in both logs and uploaded artifacts, and installs `sccache` through an in-repo composite action instead of the stale Node 20 upstream action wrapper.

### Prerelease + ARM64 proof checkpoint

Comparing `prerelease-apk` run `24300220776` on `c007d6f` with run `24300530788` on `6fc8c95`:

- prerelease build lane total: about `6m29s` -> about `3m31s`
- prerelease assemble step: `149s` -> `47s`
- Rust/Android toolchain step: `79s` -> `5s`
- prerelease `sccache` stats: `103` misses (`91` Rust) -> `0` misses
- prerelease `sccache` hits: `4` total -> `16` total, all C/C++ hits
- ARM64 ISA proof `sccache` stats: `6` Rust misses -> `6` Rust hits

The `6fc8c95` run was still the first run to populate the new per-arch `sccache` binary cache keys:

- `sccache-bin-Linux-X64-v0.10.0`
- `sccache-bin-Linux-ARM64-v0.10.0`

So the next warm run should avoid both the older Node 20 action wrapper and the initial `sccache` binary download path.

### Warning-free rust cache transition checkpoint

The last remaining deprecation warning in this workflow came from `Swatinem/rust-cache@v2`, not from any Node 20 runtime in our own workflow helpers. That warning is now removed by replacing the upstream action with the in-repo `.github/actions/rust-shared-cache` composite action.

Measured proof:

- run `24300758591` on `0ce642b` kept the previous cache shape warm:
  - prerelease build lane: about `3m35s`
  - `sccache-bin-Linux-X64-v0.10.0`: cache hit
  - `sccache-bin-Linux-ARM64-v0.10.0`: cache hit
  - prerelease `sccache`: `16/16` C/C++ hits, `0` misses
  - ARM64 ISA proof `sccache`: `6/6` Rust hits, `0` misses
  - logs still contained the `Swatinem/rust-cache` `punycode` deprecation warning

- run `24300835664` on `d6415b5` was the first run after the in-repo rust-cache replacement:
  - prerelease build lane: about `4m22s`
  - no `punycode`, `DeprecationWarning`, or `Swatinem/rust-cache` text in either the prerelease or ARM64 logs
  - the new rust-shared-cache keys missed once and were saved:
    - `v0-rust-solarlab-rust-android-Linux-X64-Linux-x64-4e52fb97-5c4d0e2c`
    - `v0-rust-solarlab-rust-native-Linux-ARM64-Linux-arm64-5846ecfe-5c4d0e2c`
  - prerelease `sccache`: `106` hits, `1` miss, including `90` Rust hits
  - ARM64 ISA proof `sccache`: `6/6` Rust hits, `0` misses

- run `24300939970` on `d6415b5` was the second warm run on the replacement action:
  - prerelease build lane: about `3m42s`
  - prerelease assemble step: `41s`
  - second-run rust shared-cache exact hits on both architectures:
    - `Cache hit for: v0-rust-solarlab-rust-android-Linux-X64-Linux-x64-4e52fb97-5c4d0e2c`
    - `Cache hit for: v0-rust-solarlab-rust-native-Linux-ARM64-Linux-arm64-5846ecfe-5c4d0e2c`
  - `sccache-bin-Linux-X64-v0.10.0`: cache hit
  - `sccache-bin-Linux-ARM64-v0.10.0`: cache hit
  - prerelease `sccache`: `16/16` C/C++ hits, `0` misses
  - ARM64 ISA proof `sccache`: `6/6` Rust hits, `0` misses
  - logs remained free of `punycode`, `DeprecationWarning`, and `Swatinem/rust-cache`

Interpretation:

1. the warning removal is real, not cosmetic log filtering
2. the first run after the cache-key change paid a one-time repopulation cost
3. the second run on the same head returned to the warm-path behavior we wanted
4. the remaining hosted-runner cost is now dominated more by Android SDK + emulator bootstrap than by repeated Rust/native compilation

### Build-first / test-many direction

After these gains, the dominant hosted-runner tax is shifting away from repeated native compiles and toward runner bring-up:

- Android SDK/toolchain setup
- emulator startup / launch smoke
- workflow bootstrap around already-cached build inputs

That means a build-first / test-many split only makes sense if we also increase test fan-out per validation mode. With the current one-batch-per-mode shape, shuffling large Android artifacts between jobs is likely to add more moving parts than it saves. The current recommendation is:

1. keep leaning on remote Gradle cache plus `sccache`
2. preserve the simple one-job-per-mode shape while the suite is small
3. revisit a build artifact split if instrumentation gets sharded more aggressively

### Android SDK package cache checkpoint

The next remaining bootstrap seam after the Rust/native cache wins was repeated installation of the Android emulator package and the API 35 Google APIs system image. Both workflows now restore those directories from a dedicated GitHub Actions cache before invoking `sdkmanager`.

Measured proof on `acaf8f1`:

- `prerelease-apk` run `24301470488` was the first population run:
  - build lane total: about `3m29s`
  - AVD cache hit: yes
  - Android SDK component cache key: `android-sdk-components-Linux-X64-v1`
  - Android SDK component cache: miss
  - SDK install step still had to install:
    - `emulator`
    - `system-images;android-35;google_apis;x86_64`
  - post-job cache save uploaded about `1.97 GB` in about `16s`

- `prerelease-apk` run `24301554955` was the second warm run on the same head:
  - build lane total: about `2m58s`
  - AVD cache hit: yes
  - Android SDK component cache: hit on `android-sdk-components-Linux-X64-v1`
  - SDK component restore step: about `18s`
  - SDK verification step: about `11s`
  - `sdkmanager` reported all required packages already installed, including:
    - `emulator`
    - `system-images;android-35;google_apis;x86_64`
  - assemble prerelease step remained warm at about `52s`

Interpretation:

1. the emulator + system-image install seam is now cached rather than re-downloaded on every warm run
2. the first population cost is acceptable relative to the repeated savings
3. the remaining hosted-runner Android bootstrap tax is now mostly:
   - AVD restore: about `12-13s`
   - SDK package restore: about `18s`
   - SDK verification: about `11s`
   - then the actual assemble + emulator smoke work
4. this makes a build-first / test-many split less urgent than it looked before the SDK package cache existed

### Cross-workflow Android shell proof

The same SDK package cache is now proved on the targeted `validation-lab` Android shell lane, not just on `prerelease-apk`.

- `validation-lab` run `24301670464` on `5ae574e`:
  - Android shell job total: about `4m01s`
  - AVD cache hit: `avd-Linux-pixel_7_api35_google_apis_x86_64-v3`
  - Android SDK component cache hit: `android-sdk-components-Linux-X64-v1`
  - SDK package restore step: about `25s`
  - SDK verification step: about `8s`
  - `sdkmanager` reported all required packages already installed, including:
    - `emulator`
    - `system-images;android-35;google_apis;x86_64`
  - assemble debug Android shell + androidTest: about `53s`
  - connected Android shell smoke + continuity tests: about `1m24s`
  - Android shell `sccache`: `16/16` C/C++ hits, `0` misses

Interpretation:

1. the SDK package cache is genuinely shared across both workflows
2. the remaining warm-path Android setup tax is now mostly cache restore bandwidth and emulator execution, not repeated package installation
3. the strongest remaining case for more speed is no longer "cache the Android packages" but either:
   - reduce artifact duplication further, or
   - move heavy Android lanes onto a prewarmed self-hosted runner when hosted-runner bootstrap latency becomes the dominant cost

### Self-hosted adoption seam

The heavy Android jobs are now ready for a controlled self-hosted migration without another workflow refactor.

- `validation-lab` Android shell
- `prerelease-apk` build / launch-smoke lane

Both jobs now read the optional repository variable:

- `SGL_ANDROID_HEAVY_RUNS_ON_JSON`

Behavior:

1. if the variable is unset, the workflows keep using the current GitHub-hosted default:
   - `"ubuntu-24.04"`
2. if the variable is set, the workflows use that `runs-on` JSON value directly

Example values:

- current hosted default:
  - `"ubuntu-24.04"`
- dedicated self-hosted Linux x64 runner:
  - `["self-hosted","linux","x64","sgl-android"]`

This keeps the current validated hosted-runner behavior unchanged while giving the next self-hosted runner evaluation a small, reversible control surface.

## Cloudflare and R2 layout

- hostname: `cache.sednalabs.io`
- dedicated tunnel for the cache service
- private R2 bucket for Gradle cache backing store
- private R2 bucket for `sccache`

The Gradle cache hostname should terminate at the tunnel and local Rust service.
Do not use a public bucket custom domain or `r2.dev` as the Gradle cache front door.

## Grant operator checklist

1. Create private R2 bucket `solarlab-gradle-cache`.
2. Create private R2 bucket `solarlab-sccache`.
3. Create an R2 access-key pair for the cache service and later `sccache`.
4. Create a dedicated Cloudflare Tunnel for the Gradle cache service.
5. Attach `cache.sednalabs.io` to that tunnel.
6. Install the Rust service and `cloudflared` systemd user units.
7. Populate the host env files with Basic auth credentials, local cache root, R2 settings, and tunnel token.
8. Copy the resulting GitHub-side values into repo or org secrets and vars.

## Validation expectations

- second-run `validation-lab` and `prerelease-apk` with `snapshot-cache` should show an AVD cache hit and skip snapshot seeding
- second-run trusted Gradle jobs should show more task reuse than the first run
- second-run Rust/native jobs should show non-zero `sccache` hits in the workflow summary
- the native Android build task must stop forcing itself dirty so remote cache hits are possible
- when the self-hosted cache backend is down, Gradle builds must still succeed with the remote cache disabled for that build
