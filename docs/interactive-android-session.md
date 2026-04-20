# Interactive Android Session

This repository now has a dedicated GitHub-hosted interactive Android session
workflow for bounded live debugging on the same x64 emulator surface already
used by `validation-lab` and `prerelease-apk`.

The workflow lives at:

- `.github/workflows/interactive-android-session.yml`

It is intentionally separate from `validation-lab`:

- `validation-lab` remains the canonical remote proof lane
- `prerelease-apk` remains the installable artifact lane
- `interactive-android-session` is the operator-facing live debug lane

## Why this workflow exists

This public repository already uses GitHub Actions as the main remote compute
surface for Android proof work. The interactive lane extends that posture
without turning Actions into a generic public terminal host.

The workflow:

- boots the existing x64 emulator image on a GitHub-hosted runner
- builds the app debug APK under `clients/android`
- checks out and builds `android-emulator-mcp` from source inside the job
- checks out the pinned `mcp-toolkit-rs` sibling workspace needed by the MCP
- starts `android-emulator-mcp` on loopback only
- runs install-and-launch preflight before the session opens
- exposes a live web terminal through a Cloudflare Access-protected tunnel
- uploads a predictable evidence bundle when the session ends

## Workflow inputs

- `ref`
  - branch, tag, or commit from this repo
- `android_emulator_mcp_ref`
  - branch, tag, or commit from `sednalabs/android-emulator-mcp`
  - default: `9d8e67ea7195e9b0536f9b76166e68caaa218fc9`
- `mcp_toolkit_rs_ref`
  - branch, tag, or commit from `GraciousGazelles/toolkits-mcp-toolkit-rs`
  - keep this in sync with the MCP repo's path-based toolkit dependency surface
- `android_validation_mode`
  - one of:
    - `shell-v2`
    - `stage-first-mirror-off`
    - `stage-first-mirror-on`
- `emulator_boot_strategy`
  - `snapshot-cache` or `cold`
- `session_timeout_minutes`
  - bounded live-debug window
  - workflow logic clamps to `30-180`
- `keep_session_on_failure`
  - if true, preflight failure still leaves the live session up for debugging

## Required secrets

This workflow needs explicit repo reads and tunnel access:

- `SGL_ANDROID_EMULATOR_MCP_READ_TOKEN`
  - read access to `sednalabs/android-emulator-mcp`
- `SGL_MCP_TOOLKIT_RS_READ_TOKEN`
  - read access to `GraciousGazelles/toolkits-mcp-toolkit-rs`
- `SGL_INTERACTIVE_DEBUG_TUNNEL_TOKEN`
  - Cloudflare named tunnel token for the live terminal
- `SGL_INTERACTIVE_DEBUG_HOSTNAME`
  - the hostname protected by Cloudflare Access for the live session

The workflow also reuses the existing remote cache credentials already present in
the heavier Android lanes:

- `GRADLE_REMOTE_CACHE_PASSWORD`
- `R2_CACHE_ACCESS_KEY_ID`
- `R2_CACHE_SECRET_ACCESS_KEY`

## Safety rules

This repository is public. The interactive lane therefore avoids any live access
mechanism that would dump one-time credentials into GitHub Actions logs.

Current safety posture:

- `android-emulator-mcp` binds to `127.0.0.1` only
- `ttyd` binds to `127.0.0.1` only
- Cloudflare Tunnel exposes only the terminal port
- access control lives in Cloudflare Access rather than in workflow log output
- the workflow is `workflow_dispatch` only

Do not replace this with `tmate` or any similar public-log terminal pattern for
the hosted lane.

## Evidence contract

The workflow writes its main artifact bundle under:

- `dist/interactive-session/`

Expected contents:

- `app/`
- `android-emulator-mcp-artifacts/`
- `preflight/`
- `startup-log/`
- `live-access/`
- `emulator-logcat/`
- `ui-dumps/`
- `screenshots/`
- `session-state.json`
- `mcp-health.json`

The summary payloads live under:

- `dist/interactive-session-summary/`

Those files are designed to answer:

- which repo refs were used
- whether preflight passed
- whether live access came up
- how the session ended
- where the evidence bundle is rooted

## Operator notes

Recommended first run:

1. dispatch the workflow from a `validation/*` branch
2. keep `android_validation_mode=stage-first-mirror-on`
3. keep `emulator_boot_strategy=snapshot-cache`
4. keep the default timeout
5. verify the workflow summary shows the Cloudflare hostname

Inside the live shell, finish early with:

```bash
touch dist/interactive-session/finish-session
```

If that file is not created, the workflow ends automatically when the timeout
window is reached.

## Relationship to other Android surfaces

- `validation-lab` answers “does the canonical remote proof still pass?”
- `prerelease-apk` answers “can we package and launch an installable artifact?”
- `interactive-android-session` answers “can an operator or agent inspect the
  live emulator remotely without consuming Orchard host compute?”
- `infra/self-hosted-android-runner/` stays the phase-two path if hosted-runner
  cold-start or live-session limits become the next bottleneck
