# Galaxy S20 Ultra Agent Developer Device

This directory contains the first committed scaffolding for a rooted Samsung
Galaxy S20 Ultra that we are willing to dedicate as an agent developer device.

The goal is not a general device farm. The goal is a repeatable real-device
debug and evidence surface that complements the hosted interactive emulator
workflow.

## Scope

This device lane is intentionally narrow at first:

- one rooted Galaxy S20 Ultra
- one connected operator or agent session at a time
- install, launch, and evidence capture for Solar Gravity Lab builds
- optional short rooted traces for deeper debugging

## Files

- `scripts/bootstrap_device.sh`
  - verify `adb`, root shell, and local `scrcpy` readiness
- `scripts/capture_device_evidence.sh`
  - collect screenshot, logcat, dumpsys, and optional perfetto
- `scripts/install_actions_artifact.sh`
  - install a built APK, launch the app, and write a small summary bundle

## Operating posture

- keep bootstrap USB-first
- treat ADB-over-TCP as an explicit follow-up, not the first trust path
- keep host-local credentials and tunnels outside tracked repo state
- keep evidence format predictable so Actions artifacts and device artifacts can
  be compared without hand parsing

## Related docs

- `docs/galaxy-s20-agent-developer-device.md`
- `docs/interactive-android-session.md`
