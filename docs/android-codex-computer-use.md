# Android Codex Computer-Use Harness

Solar Gravity Lab uses the native Android/Codex computer-use harness as a
consumer and proving app. The reusable Android provider, Codex dynamic-tool
adapter, and generic computer-use contract are supplied by a
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
- the generic Codex-facing provider backend
- the model-callable Android tools exposed as `android_observe` and
  `android_step`
- artifact capture and remote artifact reads
- Android-level action semantics, leases, transport details, and provider
  manifest schema

That split keeps Solar Lab documentation focused on how the app consumes and
proves the harness rather than redefining a general Android computer-use API.

## Hosted Session Artifacts

[`interactive-android-session`](interactive-android-session.md) writes its main
artifact bundle under `dist/interactive-session/`. The Codex-facing parts of
that bundle are:

- `live-access/codex-android-tools.sh`: dynamic-tool provider helper, when the
  selected provider ref supports it
- `live-access/codex-android-observe.sh`: optional explicit observation helper
  for debugging
- `codex-bridge/status.json`: readiness, mode, helper paths, output root,
  provider-manifest status, and available tool names
- `codex-bridge/provider-manifest.json`: generic Android provider metadata, when
  the provider can emit it
- `codex-bridge-runs/`: output root for Codex bridge observations and tool-call
  artifacts
- `active-build.json`: the app build selected for the session
- `session-state.json`: the final hosted session status
- `mcp-health.json`: the Android provider health payload captured by the
  workflow

The live shell also exports the dynamic-tool command when available:

```bash
CODEX_DYNAMIC_TOOL_COMMAND=dist/interactive-session/live-access/codex-android-tools.sh
```

Normal Codex-driven use of the hosted session follows that dynamic-tool path and
does not require an OpenAI API key in the session.

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

The interactive session summary includes both the bridge readiness payload and
the provider manifest details when present, so a completed run can be inspected
without guessing which provider capabilities were active.

## Proof Surfaces

Use the hosted workflows for different questions:

- [`validation-lab`](validation-lab.md) proves the canonical Rust platform and
  Android shell build path. It is the main remote proof lane for code changes.
- `interactive-android-build` produces reusable Android build artifacts for
  hosted interactive work.
- `interactive-android-session` proves that a bounded hosted emulator session
  can launch Solar Lab, expose the Android provider control plane, stage the Codex
  dynamic-tool helper, and upload the resulting session artifacts.
- `prerelease-apk` proves installable preview packaging from a promoted head.

For documentation-only changes, prefer the docs sanity checks described in
[`validation-lab`](validation-lab.md) instead of dispatching heavy Android
validation.
