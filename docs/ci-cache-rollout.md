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
- Rust-heavy jobs install `sccache`, configure it from repo vars and secrets, and emit per-job stats into the workflow summary
- Android/native jobs use shared `Swatinem/rust-cache` keys so helper binaries such as `cargo-ndk` do not get reinstalled independently in each lane

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
