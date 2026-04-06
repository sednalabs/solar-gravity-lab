# Developer Telemetry Collector

This is a small optional service for receiving best-effort developer telemetry
from the Android shell.

It is intentionally narrow:

- `POST /v1/android/developer-telemetry`
- `GET /healthz`
- optional bearer-token auth
- optional NDJSON append-only logging on the host

The simulation remains offline-first. If this service is not running, the
Android shell still keeps its local in-app ring buffer and logcat mirror.

## Environment

- `SOLARLAB_DEV_TELEMETRY_BIND`
  - default: `127.0.0.1:8787`
- `SOLARLAB_DEV_TELEMETRY_TOKEN`
  - optional bearer token expected as `Authorization: Bearer <token>`
- `SOLARLAB_DEV_TELEMETRY_LOG`
  - optional path for append-only NDJSON output

## Tunnel patterns

- SSH tunnel:
  - run the collector bound to `127.0.0.1`
  - expose it to the phone through a local SSH forward or reverse tunnel
- Cloudflare Tunnel:
  - keep the collector bound locally
  - publish only the tunnel edge
  - still prefer a bearer token even if the tunnel is private

## Android configuration

Set these before building the Android app:

- `SOLARLAB_DEV_TELEMETRY_ENDPOINT`
- `SOLARLAB_DEV_TELEMETRY_TOKEN` (optional but strongly recommended)

The Android shell only enables remote streaming when `SOLARLAB_DEV_TELEMETRY_ENDPOINT`
is non-empty at build time.
