# Self-Hosted Android Runner Operator Checklist

This is the shortest path from the current prepared repo state to a live self-hosted Android runner pilot.

Use this together with:

- `docs/self-hosted-android-runner-evaluation.md`
- `infra/self-hosted-android-runner/README.md`

## 1. Prepare the host

On the runner machine:

1. Ensure Ubuntu Linux x64 with KVM available.
2. Ensure the host has enough free disk for:
   - the GitHub Actions runner install
   - Android SDK / NDK / emulator packages
   - one precreated AVD
   - checkout workspace growth
3. Ensure these baseline tools exist:
   - `curl`
   - `tar`
   - `git`
   - `python3`
   - `rustup`
   - Java 17
   - Android command-line tools providing `sdkmanager`, `avdmanager`, and the emulator

## 2. Create the local runner env file

Create a host-local env file from:

- `infra/self-hosted-android-runner/env/runner.env.example`

Recommended location:

- `~/.config/solarlab-actions-runner.env`

Adjust at least:

- `GITHUB_RUNNER_VERSION`
- `GITHUB_RUNNER_ROOT`
- `GITHUB_RUNNER_NAME`
- `GITHUB_RUNNER_LABELS`
- `ANDROID_SDK_ROOT`

Recommended labels:

- `self-hosted,linux,x64,sgl-android`

## 3. Install the GitHub runner binaries

Run:

```bash
GITHUB_RUNNER_ENV_FILE="$HOME/.config/solarlab-actions-runner.env" \
  infra/self-hosted-android-runner/scripts/install_actions_runner.sh
```

This downloads and extracts the pinned GitHub Actions runner release into the configured runner root.

## 4. Prewarm the Android toolchain

Run:

```bash
GITHUB_RUNNER_ENV_FILE="$HOME/.config/solarlab-actions-runner.env" \
  infra/self-hosted-android-runner/scripts/prewarm_android_toolchain.sh
```

This installs:

- `platform-tools`
- `platforms;android-36`
- `build-tools;36.0.0`
- `emulator`
- `system-images;android-35;google_apis;x86_64`
- `cmake;3.31.5`
- `ndk;27.3.13750724`
- Rust Android targets
- `cargo-ndk`
- `sccache`
- the configured AVD

## 5. Add the runner in GitHub

In the GitHub repository UI:

1. Open `sednalabs/solar-gravity-lab`
2. Go to `Settings`
3. Go to `Actions`
4. Go to `Runners`
5. Click `New self-hosted runner`
6. Choose Linux x64
7. Keep the page open; GitHub will display the time-limited registration commands

Important:

- GitHub’s registration token expires after about one hour
- do not store it long-term in a file

## 6. Configure the runner with the one-time token

On the host, export the token into the current shell only:

```bash
export GITHUB_RUNNER_REG_TOKEN='<paste temporary registration token here>'
```

Then run:

```bash
GITHUB_RUNNER_ENV_FILE="$HOME/.config/solarlab-actions-runner.env" \
  infra/self-hosted-android-runner/scripts/configure_actions_runner.sh
```

This will:

- register the runner against `https://github.com/sednalabs/solar-gravity-lab`
- assign the configured labels
- configure the work directory
- replace an existing runner with the same name if needed

Afterward:

```bash
unset GITHUB_RUNNER_REG_TOKEN
```

## 7. Install the runner as a service

Copy the provided service template:

- `infra/self-hosted-android-runner/deploy/systemd/solarlab-actions-runner.service`

To:

- `~/.config/systemd/user/solarlab-actions-runner.service`

Then reload and start:

```bash
systemctl --user daemon-reload
systemctl --user enable --now solarlab-actions-runner.service
systemctl --user status solarlab-actions-runner.service
```

The service uses the runner’s `runsvc.sh` entry point, which matches GitHub’s guidance for customized services.

## 8. Verify the runner in GitHub

Back in the GitHub UI:

1. Return to `Settings > Actions > Runners`
2. Confirm the new runner appears
3. Confirm labels include:
   - `self-hosted`
   - `linux`
   - `x64`
   - `sgl-android`
4. Confirm the runner is `Idle`

## 9. Switch the heavy Android jobs onto the runner

Set repository variable:

- `SGL_ANDROID_HEAVY_RUNS_ON_JSON`

to:

```json
["self-hosted","linux","x64","sgl-android"]
```

Important safety behavior already in the workflows:

- `pull_request` heavy Android jobs stay on `ubuntu-24.04`
- only non-PR runs will switch to the self-hosted runner

## 10. Prove the pilot

Run these two workflows on a validation branch:

1. `validation-lab`
   - `profile=targeted`
   - `lane_set=android-shell`
   - `android_test_scope=core`
   - `android_validation_mode=stage-first-mirror-on`
   - `android_artifact_mode=always`
   - `emulator_boot_strategy=snapshot-cache`

2. `prerelease-apk`
   - `build_variant=prerelease`
   - `publish_release=false`
   - `android_artifact_mode=always`
   - `emulator_boot_strategy=snapshot-cache`

Success means:

- both jobs land on the self-hosted runner
- validation output stays the same
- warm timings beat the current hosted-runner baseline

## 11. Fast rollback

If anything is wrong:

1. unset repository variable `SGL_ANDROID_HEAVY_RUNS_ON_JSON`
2. rerun the workflows

The workflows will fall back to:

- `"ubuntu-24.04"`

No workflow revert is required for the first rollback step.
