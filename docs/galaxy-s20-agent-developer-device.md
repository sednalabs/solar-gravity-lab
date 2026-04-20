# Galaxy S20 Ultra Agent Developer Device

The rooted Galaxy S20 Ultra is the real-device companion to the hosted
interactive emulator lane.

Its job is different from GitHub-hosted emulation:

- GitHub-hosted emulator sessions give us reproducible remote compute and a
  bounded live-debug surface
- the rooted Galaxy gives us ARM64 device truth, rooted observability, and a
  place to inspect behavior the emulator smooths over

This device program is intentionally narrow in the first slice:

- one device
- one operator at a time
- one known app package
- repeatable install, launch, evidence capture, and interactive control

## Files

- `infra/galaxy-s20-agent-device/README.md`
- `infra/galaxy-s20-agent-device/scripts/bootstrap_device.sh`
- `infra/galaxy-s20-agent-device/scripts/capture_device_evidence.sh`
- `infra/galaxy-s20-agent-device/scripts/install_actions_artifact.sh`

## First-pass use cases

1. Bootstrap the device as a trusted interactive dev surface
2. Install APKs built by GitHub Actions
3. Capture rooted evidence:
   - screenshot
   - logcat
   - dumpsys
   - optional perfetto trace
4. Reproduce issues that need real-device input, GPU, or lifecycle truth

## Safety notes

- Keep host-local tunnel, pairing, and operator credentials out of the tracked
  repo state
- Treat the rooted S20 as a developer/debug device, not the only final release
  confidence surface
- Prefer USB-first bring-up before adding ADB-over-TCP

## Relationship to hosted emulation

Use the hosted interactive emulator lane first when the question is:

- reproducible remote debugging
- remote install/launch proof
- shared artifacts from a clean runner

Use the rooted S20 first when the question is:

- ARM64 hardware behavior
- real IME / input behavior
- GPU or thermal behavior
- rooted diagnostics
- “the emulator is probably hiding something”
