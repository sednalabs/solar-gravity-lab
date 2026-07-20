# Android Codex Computer-Use Harness

Solar Gravity Lab uses the native Android/Codex computer-use harness as a
consumer and proving app. The reusable Android provider implementation and
generic computer-use contract are supplied by a
maintainer-configured Android provider; this repository should not grow a
second owner for that tooling.

## Boundary

Solar Gravity Lab owns:

- a real Android app under [`clients/android`](../clients/android/README.md)
  that can be built, installed, launched, observed, and driven in hosted runs
- workflow inputs that pin the Solar Lab ref, the Android provider ref, and the
  supporting toolkit ref for a session
- hosted workflow artifacts that prove which app build and Android provider
  were active for the run
- app-specific validation expectations, such as render readiness, runtime
  snapshot availability, and interaction continuity

The selected Android provider owns:

- Android device and emulator control
- provider-side session and lease lifecycle
- screenshots, UI/state capture, and input execution
- the generic Codex-facing provider backend
- artifact capture and remote artifact reads
- Android-level action semantics, transport details, and provider manifest
  schema

That split keeps Solar Lab documentation focused on how the app consumes and
proves the harness rather than redefining a general Android computer-use API.

## Hosted Session Artifacts

[`Interactive Android Session`](interactive-android-session.md) writes its main
artifact bundle under `dist/interactive-session/`. The Codex-facing parts of
that bundle are:

- `live-access/codex-android-tools-<provider-sha>.sh`: content-addressed
  provider helper, when the selected provider ref supports it
- `live-access/codex-android-observe.sh`: optional explicit observation helper
  for debugging
- `codex-bridge/status.json`: readiness, mode, helper paths, output root,
  provider-manifest status, native tool proof status, and available native tool
  names
- `codex-bridge/tool-specs.json`: provider-advertised tool metadata for
  `android_observe` and `android_step`, when supported
- `codex-bridge/android-observe-proof.json`: the lightweight hosted
  `android_observe` response, when supported
- `codex-bridge/android-observe-proof-validation.json`: Solar Lab's validation
  summary for the native proof response, when supported
- `codex-bridge/provider-manifest.json`: generic Android provider metadata, when
  the provider can emit it
- `codex-bridge/provider-manifest-validation.json`: the selected provider's
  validation summary for that manifest, when supported
- `codex-bridge-runs/`: output root for Codex bridge observations and tool-call
  artifacts
- `active-build.json`: the app build selected for the session
- `session-state.json`: the final hosted session status
- `mcp-health.json`: the Android provider health payload captured by the
  workflow

The live shell also exports the provider helper command when available:

```bash
CODEX_DYNAMIC_TOOL_COMMAND=dist/interactive-session/live-access/codex-android-tools-<provider-sha>.sh
```

The exact connector commit is part of the command path and is also recorded as
`adapter_revision` in `codex-bridge/status.json`. That immutable identity makes
provider upgrades visible to long-lived native tool sessions instead of
silently reusing a previously cached helper.

Normal Codex-driven use of the hosted session uses Codex's native
`android_observe` / `android_step` flow, with the helper script acting as the
provider-side runtime adapter below that boundary. This path does not require
an OpenAI API key in the session.

In that native flow, screenshots should reach Codex as native image content in
the computer-use response. Solar Lab artifacts and paths remain evidence for
workflow replay, provider diagnostics, and debugging, not instructions for the
model to fetch local files during normal visual interaction.

## Provider Manifest

The provider manifest is emitted by the selected Android provider ref and stored
by Solar Lab as evidence. It is generic Android capability metadata, not a
Solar-specific tool definition.

Solar Lab uses the manifest to record:

- the Android provider family and adapter
- the native Codex tool names available to the session
- MCP transport details and Android serial information when available
- session, artifact, and active-build manifest paths
- app package and activity hints for the focused Solar Lab session
- timeout and lease policy for read-only observation and mutating step calls
- the provider-owned outcome taxonomy used by Android tool responses, when the
  selected provider emits it

The interactive session summary includes both the bridge readiness payload and
the provider manifest details when present, so a completed run can be inspected
without guessing which provider capabilities were active.

When the selected provider ref supports the helper CLI, the hosted session also
asks the provider for tool metadata and performs a
single lightweight `android_observe` call against the running app. Solar Lab
validates that the response includes the provider-owned
`metadata.android.outcome` contract and records the result as run evidence. This
is deliberately a consumer-side proof, not a Solar-owned redefinition of the
generic Android tool response schema.

When present, the outcome taxonomy stays provider-owned. Solar Lab records it as
evidence so operators can see whether a selected provider distinguishes
successful actions, degraded observation, unsatisfied postconditions, retry
posture, and operator-required failures; this repository should not redefine
those generic Android meanings.

## Proof Surfaces

Use the hosted workflows for different questions:

- [Validation Lab](validation-lab.md) proves the canonical Rust platform and
  Android shell build path. It is the main remote proof lane for code changes.
- `interactive-android-build` produces reusable Android build artifacts for
  hosted interactive work.
- `interactive-android-session` proves that a bounded hosted emulator session
  can launch Solar Lab, expose the Android provider control plane, stage the Codex
  provider helper, capture provider tool metadata, exercise a lightweight
  `android_observe` call, and upload the resulting session artifacts.
- `prerelease-apk` proves installable preview packaging from a promoted head.

For documentation-only changes, prefer the docs sanity checks described in
[`validation-lab`](validation-lab.md) instead of dispatching heavy Android
validation.

## Visual Acceptance

Build and test proof is not the same thing as visual acceptance. When a change
affects Android layout, stage-first presentation, runtime controls,
scenario-pack interaction, or visual polish, the acceptance path must include a
real Android observation loop.

For those changes:

- use the native `android_observe` / `android_step` path when available, or the
  hosted interactive Android session when a live hosted emulator is the active
  surface
- navigate the actual user path being claimed, not just the default launch
  screen
- inspect the Rust-backed stage in both collapsed and expanded control states
- check collapsed and expanded/control states before claiming that the 3D stage
  remains visually dominant
- record the observation source in the PR or validation notes
- require evidence that the native response included image content before
  describing an observation as native visual proof; CodeQL claim-enforcement
  queries should treat image-free claims as structural regressions

If the Android provider or hosted interactive session is unavailable, mark the
visual proof as blocked. `validation-lab`, test tags, and code inspection can
prove important behavior, but they do not prove composition, density, or
beauty. CodeQL can keep the evidence contract honest, but it cannot decide
whether the pixels are beautiful.
