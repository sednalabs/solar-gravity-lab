# CI Cache Rollout

This document captures the hosted-runner cache rollout for Solar Gravity Lab.

## Goals

- reduce repeated Android emulator cold-start cost on GitHub-hosted runners
- reduce repeated Gradle task execution across trusted CI branches
- prepare a separate R2-backed `sccache` path for later Rust/native compiler reuse

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

## Cloudflare layout

- custom hostname: `cache.sednalabs.io`
- Worker: `infra/cloudflare/gradle-cache-worker`
- private R2 bucket for Gradle HTTP cache: `solarlab-gradle-cache`
- private R2 bucket for `sccache`: `solarlab-sccache`

The Gradle cache is intentionally Worker-backed with HTTP Basic auth because
Gradle natively supports HTTP cache credentials. `sccache` stays on the direct
R2 S3-compatible endpoint rather than sharing the custom hostname.

## Grant operator checklist

1. Open the Cloudflare dashboard for the account that owns `sednalabs.io`.
2. Create private R2 bucket `solarlab-gradle-cache`.
3. Create private R2 bucket `solarlab-sccache`.
4. Create an R2 access-key pair for CI and record:
   - access key id
   - secret access key
   - account id
5. Create Worker `solarlab-gradle-cache`.
6. Add Worker secrets:
   - `CACHE_BASIC_AUTH_USER`
   - `CACHE_BASIC_AUTH_PASS`
7. Add Worker R2 binding:
   - binding: `GRADLE_CACHE_BUCKET`
   - bucket: `solarlab-gradle-cache`
8. Attach custom domain `cache.sednalabs.io`.
9. Copy the resulting values into GitHub secrets and vars.

## Validation expectations

- second-run `validation-lab` and `prerelease-apk` with `snapshot-cache` should
  show an AVD cache hit and skip snapshot seeding
- second-run `android-unit` and `android-lint` on trusted refs should show more
  Gradle task reuse than the first run
- the native Android build task must stop forcing itself dirty so remote cache
  hits are actually possible
