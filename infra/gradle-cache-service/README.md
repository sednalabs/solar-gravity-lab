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
