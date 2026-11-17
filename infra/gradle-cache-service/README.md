# Solar Lab Gradle Cache Service

This service is the Gradle-facing remote cache endpoint for Solar Gravity Lab.

## Architecture

- Rust `axum` HTTP service bound to localhost
- Cloudflare Tunnel exposes `cache.sednalabs.io`
- local disk is the hot authoritative cache
- optional R2 mirroring and read-through on local miss

The service implements the Gradle HTTP cache contract:

- `GET /cache/<key>`
- `HEAD /cache/<key>`
- `PUT /cache/<key>`
- `GET /statsz` for authenticated plain-text counters

Operational visibility:

- per-request logs for cache hits, misses, and writes
- authenticated `/statsz` counters for local hits, R2 hits, misses, mirror success/failures, and auth failures

Authentication is HTTP Basic auth because Gradle natively supports it.

## Required environment

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

## Deployment split

- app service: `deploy/systemd/solarlab-gradle-cache.service`
- tunnel service: `deploy/systemd/cloudflared-solarlab-gradle-cache.service`
- tunnel config: `deploy/cloudflared/solarlab-gradle-cache.yml`

Build the release binary before (re)starting the app service:

```bash
cargo build --release --manifest-path infra/gradle-cache-service/Cargo.toml
systemctl --user restart solarlab-gradle-cache.service
```

## Dependency security and OSV Scanner

`Cargo.lock` currently resolves `aws-smithy-http 0.63.6`, which depends
transitively on `pin-utils 0.1.0`. OSV/RustSec
[`RUSTSEC-2026-0441`](https://osv.dev/RUSTSEC-2026-0441)
reports that `pin-utils` is **unmaintained** (informational/unknown severity,
not a documented exploit or a patched crate release).

The directory-scoped [`osv-scanner.toml`](osv-scanner.toml) contains an
exception for **that single advisory only**, with v2.3.8-supported
`ignoreUntil = 2027-02-17`. The security workflow retains
`fail-on-vuln: true` for all other advisories. The
[full main-branch OSV scan](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44014438874)
passes with this scope. The exception must be removed after a compatible
AWS Smithy dependency migration under
[issue #238](https://github.com/sednalabs/solar-gravity-lab/issues/238);
do not silently extend it or mistake its presence for a fixed dependency.
