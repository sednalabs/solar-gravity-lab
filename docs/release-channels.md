# Release Channels

Solar Gravity Lab is still in an engineering-first phase, so release labels need
to stay honest about what is actually being shipped.

## Semver release identity

Use ordinary SemVer for release identity:

- Android `versionName`: `0.1.0-alpha.1`
- GitHub tag: `v0.1.0-alpha.1`
- GitHub release channel: prerelease when the version has a prerelease suffix
- Artifact name: `solar-gravity-lab-0.1.0-alpha.1-internal-dev-preview.apk`

The first phone-installable alpha uses the `prerelease` Android build type. It
is debug-signed for sideloading and keeps the `.internal` package id suffix so
device-testing builds remain separate from future stable installs.

If maintaining an existing published tag, rerun the workflow only when the
maintainer intends to update that GitHub Release's APK and provenance assets.
For a new public alpha after `v0.1.0-alpha.1`, use the next SemVer prerelease
number rather than reusing a tag.

## Cutting `0.1.0-alpha.1`

Always cut an installable release from the exact commit that passed the hosted
proof lane. The manual path is:

```bash
gh workflow run prerelease-apk.yml \
  --repo sednalabs/solar-gravity-lab \
  --ref main \
  -f ref=<validated-commit-sha> \
  -f version_name=0.1.0-alpha.1 \
  -f version_code=1 \
  -f release_channel=prerelease \
  -f build_variant=prerelease \
  -f publish_release=true \
  -f android_artifact_mode=failures-only \
  -f emulator_boot_strategy=cold \
  -f gradle_configuration_cache=disabled
```

That workflow builds the APK, runs the packaged launch smoke, runs the ARM64 ISA
proof, uploads the workflow artifact, and publishes or updates the GitHub
Prerelease only after both proof jobs pass.

## Release trailers

`prerelease-apk` also runs a lightweight gate on `main` pushes. Ordinary `main`
pushes without a release trailer are a clean no-op for the package jobs. To let a
commit or squash-merge commit request an automatic APK release, put exactly one
release trailer in the final commit message:

```text
SolarLab-Release: 0.1.0-alpha.1
SolarLab-Version-Code: 1
```

Optional trailers:

- `SolarLab-Release-Channel: prerelease` or `SolarLab-Release-Channel: stable`
- `SolarLab-Build-Variant: prerelease` or `SolarLab-Build-Variant: release`

The channel is inferred from the SemVer string when the channel trailer is
omitted. A prerelease SemVer such as `0.1.0-alpha.1` defaults to the
phone-installable `prerelease` build variant; a stable SemVer such as `0.1.0`
defaults to the `release` build variant.

Trailer validation happens before the Android build. Duplicate release trailers,
malformed versions, unsupported channels, and unsupported build variants fail in
the gate job instead of starting the heavy APK lane.

## Current channels

### Internal dev preview

- Version line: `0.1.0-alpha.1`
- Android build type: `prerelease`
- Build path: `clients/android`
- Base application id: `com.sednalabs.solarlab`
- Package id suffix: `.internal`
- Installed package id: `com.sednalabs.solarlab.internal`
- Signing: default debug key
- Intended use: sideloaded device testing and internal review

The current installable phone build is the Rust-platform Android shell under
`clients/android`. The legacy root Android app is not the packaged preview line
on canonical `main`.

### Remote validation

- Workflow: `.github/workflows/validation-lab.yml`
- Purpose: remote proof for the Rust workspace, FFI seam, and forward Android shell
- Intended use: proving the current branch or seam without burning local Orchard
  compute

The installable prerelease workflow also now runs an ARM64 ISA proof lane
(`isa-prerelease-proof`) that executes
`.github/scripts/run_arm64_isa_proof.sh` on `ubuntu-24.04-arm` before release
publication. This gate validates backend activation truth and scalar-oracle
equivalence evidence for the ARM64 CPU path.

For pre-release iteration before a package build, `validation-lab` exposes the
same proof as the focused `arm64-isa-proof` lane, plus the lighter
`arm64-capability-census` lane for inventory-only questions. Prefer the census
lane when the active question is detected capability shape, and prefer the ISA
proof lane when solver activation or scalar-oracle parity is part of the claim.

Validation runs are evidence, not release artifacts. They answer whether a
slice is green; they do not imply that the app is ready for external
distribution.

### Documentation-only changes

- Workflow: `.github/workflows/docs-sanity.yml`
- Purpose: lightweight checks for documentation updates
- Intended use: fast feedback for README/docs/workflow-doc changes without
  triggering heavier validation by default

## Naming guidance

Use wording like `internal dev preview`, `device test build`, or `engineering
preview` when talking about the current APK channel.

Avoid implying that this is already a stable public prerelease program. The
GitHub prerelease object should describe the APK as an internal/device-testing
artifact rather than a consumer-ready launch.
