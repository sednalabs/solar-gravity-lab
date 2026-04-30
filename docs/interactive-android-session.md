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

For the recommended rapid UI loop that combines this workflow with reusable
APK builds, targeted validation lanes, and native Android observation, see
[`Rapid Android iteration`](rapid-android-iteration.md).

## Why this workflow exists

This public repository already uses GitHub Actions as the main remote compute
surface for Android proof work. The interactive lane extends that posture
without turning Actions into a generic public terminal host.

The workflow:

- boots the existing x64 emulator image on a GitHub-hosted runner
- builds the app debug APK under `clients/android`
- checks out and builds the configured Android provider from source inside the
  job
- checks out the pinned `mcp-toolkit-rs` sibling workspace needed by that
  provider
- starts the Android provider on loopback only
- runs install-and-launch preflight before the session opens
- exposes a live web terminal through a Cloudflare Access-protected tunnel
- optionally exposes the MCP HTTP surface on a second Access-protected hostname
  for agent use
- stages helper wrappers for both Codex-native observation packets and optional
  standalone OpenAI Responses-mode calls when the selected
  provider ref includes those adapter CLIs
- uploads a predictable evidence bundle when the session ends

## Workflow inputs

- `ref`
  - branch, tag, or commit from this repo
- `android_emulator_mcp_ref`
  - branch, tag, or commit from the maintainer-configured Android provider
    repository
  - default: `20c851bd93b76653443e1e06b99fb2b336e220f7`
  - this pinned provider ref returns screenshot bytes as native MCP image
    content for Codex computer-use observations; artifact paths remain
    diagnostics, not the model-facing visual channel
- `mcp_toolkit_rs_ref`
  - branch, tag, or commit from `GraciousGazelles/toolkits-mcp-toolkit-rs`
  - keep this in sync with the provider's path-based toolkit dependency
    surface
- `android_validation_mode`
  - one of:
    - `shell-v2`
    - `stage-first-mirror-off`
    - `stage-first-mirror-on`
  - `stage-first-mirror-on` builds with `solarlab.preferredGpuBackend=vulkan`
    so hosted interactive sessions exercise the native runtime mirror with the
    same requested GPU intent as the canonical Android validation lane
- `interactive_debug_profile`
  - one of:
    - `hosted-debug-lite`
    - `full-fidelity`
  - `hosted-debug-lite` starts the app in a calmer posture for GitHub-hosted
    runners:
    - simplified render processing
    - playback paused until the operator explicitly resumes it
  - `full-fidelity` keeps the existing heavy immersive posture for proof work
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
  - read access to the maintainer-configured Android provider repository
- `SGL_MCP_TOOLKIT_RS_READ_TOKEN`
  - read access to `GraciousGazelles/toolkits-mcp-toolkit-rs`
- `SGL_INTERACTIVE_DEBUG_TUNNEL_TOKEN`
  - Cloudflare named tunnel token for the live terminal
- `SGL_INTERACTIVE_DEBUG_HOSTNAME`
  - the hostname protected by Cloudflare Access for the human browser terminal
  - recommended value: `solarlab-android-debug.sednalabs.io`
- `SGL_INTERACTIVE_MCP_HOSTNAME`
  - optional machine-facing hostname for the Android provider
  - recommended value: `solarlab-android-mcp.sednalabs.io`
  - omit only if the rollout is intentionally human-terminal-only

The workflow also reuses the existing remote cache credentials already present in
the heavier Android lanes:

- `GRADLE_REMOTE_CACHE_PASSWORD`
- `R2_CACHE_ACCESS_KEY_ID`
- `R2_CACHE_SECRET_ACCESS_KEY`

## Safety rules

This repository is public. The interactive lane therefore avoids any live access
mechanism that would dump one-time credentials into GitHub Actions logs.

Current safety posture:

- the Android provider binds to `127.0.0.1` only
- `ttyd` binds to `127.0.0.1` only
- Cloudflare Tunnel exposes only the runner-local services we route explicitly
- the browser terminal and the MCP should use separate hostnames and separate
  Cloudflare Access apps
- human browser access should use normal Cloudflare Access identity
- agent access should use a Cloudflare Access service token, not an email inbox
- access control lives in Cloudflare Access rather than in workflow log output
- the workflow is `workflow_dispatch` only

Do not replace this with `tmate` or any similar public-log terminal pattern for
the hosted lane.

## Access model

Use the same dedicated tunnel for both runner-local services:

- `solarlab-android-debug.sednalabs.io`
  - human-facing browser terminal backed by `ttyd`
  - gated by Cloudflare Access human identity policy
- `solarlab-android-mcp.sednalabs.io`
  - machine-facing Android provider HTTP endpoint
  - gated by Cloudflare Access service-token policy

This split keeps the rollout honest:

- humans do not need to tunnel raw runner credentials through GitHub logs
- agents do not need a fake mailbox or OTP flow
- the provider can stay machine-usable without pretending the browser terminal
  is the right auth surface for automation

Recommended first Cloudflare posture:

1. dedicated named tunnel: `solarlab-android-debug`
2. dedicated Access app for the browser terminal
3. dedicated Access app for the MCP hostname
4. no DNS publish until each hostname has an explicit allow policy
5. keep the MCP hostname off by default until the Access service token exists

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
- `codex-bridge/`
- `codex-bridge-runs/`
- `openai-loop/`
- `openai-loop-runs/`
- `session-state.json`
- `mcp-health.json`

The Codex-native Android provider evidence is concentrated in:

- `codex-bridge/status.json`
- `codex-bridge/tool-specs.json`, when the selected provider ref can expose the
  provider tool metadata for the native Android names
- `codex-bridge/android-observe-proof.json`, when the selected provider ref can
  run the lightweight hosted `android_observe` proof
- `codex-bridge/android-observe-proof-validation.json`, when the hosted proof is
  evaluated
- `codex-bridge/provider-manifest.json`, when the selected
  provider ref can emit it
- `codex-bridge/provider-manifest-validation.json`, when the selected
  provider ref can validate the emitted manifest
- `codex-bridge-runs/`
- `live-access/codex-android-tools.sh`

The provider manifest is Android capability metadata from the selected provider.
Solar Lab stores and summarizes it as run evidence; it does not define the
generic computer-use contract here.

The summary payloads live under:

- `dist/interactive-session-summary/`

Those files are designed to answer:

- which repo refs were used
- whether preflight passed
- whether human terminal access came up
- whether machine-facing MCP access came up
- whether the Codex-native Android provider helper, provider tool metadata,
  `android_observe` proof, and manifest were available
- how the session ended
- where the evidence bundle is rooted

## Operator notes

Recommended first run:

1. dispatch the workflow from a `validation/*` branch
2. keep `android_validation_mode=stage-first-mirror-on`
3. keep `interactive_debug_profile=hosted-debug-lite`
4. keep `emulator_boot_strategy=snapshot-cache`
5. keep the default timeout
6. verify the workflow summary shows the browser terminal hostname
7. if `SGL_INTERACTIVE_MCP_HOSTNAME` is configured, verify the summary shows the
   machine-facing MCP hostname as well

Inside the live shell, finish early with:

```bash
touch dist/interactive-session/finish-session
```

If that file is not created, the workflow ends automatically when the timeout
window is reached.

## Replacing the app during a live session

The session does not need to be restarted for every app-only change. Build a
new reusable artifact with `.github/workflows/interactive-android-build.yml`,
then submit the build run id and artifact name as arguments to the live Android
provider MCP tool named `interactive_session.install_build_from_run`.

That tool verifies the build manifest, installs the APK, relaunches the
configured app, writes the new active build state, and appends install history
to the session evidence bundle. The artifact name must match the build artifact
emitted by the selected `interactive-android-build` inputs. Restart the hosted
session only when the session workflow, emulator lifecycle, tunnel, or provider
process itself needs to change.

## Codex native Android tools

When the selected Android provider ref includes the native Codex provider CLI,
the hosted session stages:

- `dist/interactive-session/live-access/codex-android-tools.sh`

Inside the live shell, the session also exports:

- `CODEX_DYNAMIC_TOOL_COMMAND=dist/interactive-session/live-access/codex-android-tools.sh`

This is the native harness direction:

- it does not require `OPENAI_API_KEY`
- it talks to `http://127.0.0.1:9526/mcp`
- it keeps the configured Android provider as the Android control plane
- it lets Codex route model calls through the native `android_observe` and
  `android_step` contract
- model turns are represented through `ComputerUseCallRequest` and
  `ComputerUseCallResponse` semantics in Codex transcript and rollout surfaces

The helper is a provider backend, not the primary UX. Codex calls the app
server directly, the app server issues provider/runtime requests into the TUI,
and the TUI resolves them through `CODEX_DYNAMIC_TOOL_COMMAND`.

Optional debug helper:

- `dist/interactive-session/live-access/codex-android-observe.sh`

The debug helper is still useful when you want to inspect the raw observation
payloads manually, but it is not the native model-callable path.

The session also writes:

- `dist/interactive-session/codex-bridge/status.json`
- `dist/interactive-session/codex-bridge/tool-specs.json`, when available
- `dist/interactive-session/codex-bridge/android-observe-proof.json`, when
  available
- `dist/interactive-session/codex-bridge/android-observe-proof-validation.json`,
  when available
- `dist/interactive-session/codex-bridge/provider-manifest.json`, when
  available
- `dist/interactive-session/codex-bridge-runs/`

Those files record which Android provider backend was available, which native
tool names were exposed, where session artifacts live, and what read/write lease
policy applied. The hosted session also performs a lightweight `android_observe`
proof when the selected provider supports it, and records the provider-owned
`metadata.android.outcome` status and retryability in the summary. When the
selected provider emits an outcome taxonomy, the summary records that too. See
[`Android Codex Computer-Use Harness`](android-codex-computer-use.md) for the Solar-side
boundary and artifact contract.

## Standalone OpenAI helper

When the selected Android provider ref includes the OpenAI adapter CLI, the
hosted session stages:

- `dist/interactive-session/live-access/openai-android-loop.sh`
- `dist/interactive-session/openai-loop/config.json`

This helper is intentionally optional standalone API mode:

- it talks to `http://127.0.0.1:9526/mcp`
- it keeps the configured Android provider as the Android control plane
- it uses the Responses-native loop above that MCP surface
- it prefers uploaded OpenAI `file_id` references for screenshots and XML/log
  artifacts when `OPENAI_API_KEY` is present in the live shell
- it is not required for normal Codex-driven use of the hosted session

Typical use inside the hosted shell:

```bash
export OPENAI_API_KEY=...
"$INTERACTIVE_OPENAI_LOOP_BIN" --prompt "Observe the current Solar Lab state and tell me what you see."
```

Loop traces and native screenshot/file outputs are written under:

- `dist/interactive-session/openai-loop-runs/`

## Relationship to other Android surfaces

- `validation-lab` answers “does the canonical remote proof still pass?”
- `prerelease-apk` answers “can we package and launch an installable artifact?”
- `interactive-android-session` answers “can an operator or agent inspect the
  live emulator remotely without consuming Orchard host compute?”
- `interactive-android-session` with `interactive_debug_profile=hosted-debug-lite`
  is the default “use this remote surface as much as we can” posture
- `infra/self-hosted-android-runner/` stays the phase-two path if hosted-runner
  cold-start or live-session limits become the next bottleneck
