# Release Channels

Solar Gravity Lab is still in an engineering-first phase, so release labels need
to stay honest about what is actually being shipped.

## Semver release identity

Use ordinary SemVer for release identity:

- Android `versionName`: `0.2.0-alpha.1`
- GitHub tag: `v0.2.0-alpha.1`
- GitHub release channel: prerelease for an alpha/beta/rc version; stable without a suffix
- Artifact name: `solar-gravity-lab-0.2.0-alpha.1-internal-dev-preview.apk`

Phone-installable previews use the `prerelease` Android build type. That variant is
debug-signed for sideloading and keeps the `.internal` package id suffix so
device-testing builds remain separate from future stable installs.

If maintaining an existing published tag, rerun the workflow only when the
maintainer intends to update that GitHub Release's APK and provenance assets.
For each new public release, use a new SemVer number rather than reusing a tag.

## Cutting a new release

Always cut an installable release from the exact commit that passed the hosted
proof lane. A branch named `release/0.2.0-alpha.1` requests that version directly:
the gate pins checkout to the push's commit SHA, selects the prerelease channel
and Android variant, and derives a monotonic Android version code. The branch
does not require an extra release commit, so it can point at the exact qualified
feature commit. Pushes cannot replace an already published release.

An arbitrary branch such as `release/phone-review` needs a `SolarLab-Release`
trailer in its head commit. Missing versions fail before packaging; there is no
fallback to `0.1.0`.

The manual path for the same identity is:

```bash
gh workflow run prerelease-apk.yml \
  --repo sednalabs/solar-gravity-lab \
  --ref main \
  -f ref=<validated-commit-sha> \
  -f version_name=0.2.0-alpha.1 \
  -f release_channel=prerelease \
  -f build_variant=prerelease \
  -f publish_release=true \
  -f android_artifact_mode=failures-only \
  -f emulator_boot_strategy=cold \
  -f gradle_configuration_cache=disabled
```

That workflow builds the APK, runs the packaged launch smoke, runs the ARM64 ISA
proof, uploads the workflow artifact, and publishes or updates the GitHub
Release only after both proof jobs pass.

Manual dispatch requires `version_name` unless its selected ref is a versioned
release branch. Existing-release replacement remains an explicit manual-only
operation. Always use a new version for a new build intended for distribution.

### Android version codes and source identity

When no explicit code is supplied, ordinary release versions map to:

`major * 100000000 + minor * 1000000 + patch * 10000 + stage`

The stage is `1000 + N` for `alpha.N`, `2000 + N` for `beta.N`, `3000 + N` for
`rc.N`, and `9000` for a stable release. This preserves version ordering through
each prerelease stage and each patch, minor and major increment. Automatic codes
support major 0–20, minor/patch 0–99 and prerelease sequence 0–999; other SemVer
suffixes or larger fields require an explicit `version_code` input or
`SolarLab-Version-Code` trailer. Explicit codes must stay within Android's
1–2100000000 range and must exceed any installed build being replaced.

For example, `0.2.0-alpha.1` is code `2001001`, followed by `2001002` for alpha.2
and `2009000` for stable `0.2.0`. The earlier published `0.1.1-alpha.8`
[package metadata](https://github.com/sednalabs/solar-gravity-lab/releases/download/v0.1.1-alpha.8/output-metadata.json)
records code `9`, so the new release line has a higher version code.

The native Gradle build also tracks the checked-out full Git SHA as a task
input, passes it to Cargo as `SOLARLAB_BUILD_COMMIT`, and embeds it in saved
experiment metadata. A different commit invalidates the native task cache even
if its Rust source files did not change. Source exports without Git metadata can
provide the full SHA with `-Psolarlab.buildCommit` or `SOLARLAB_BUILD_COMMIT`.

## Release trailers

`prerelease-apk` also runs a lightweight gate on `main` pushes. Ordinary `main`
pushes without a release trailer are a clean no-op for the package jobs. To let a
commit or squash-merge commit request an automatic APK release, put exactly one
release trailer in the final commit message:

```text
SolarLab-Release: 0.2.0-alpha.1
SolarLab-Build-Variant: prerelease
```

Optional trailers:

- `SolarLab-Release-Channel: prerelease` or `SolarLab-Release-Channel: stable`
- `SolarLab-Build-Variant: prerelease` or `SolarLab-Build-Variant: release`
- `SolarLab-Version-Code: <positive integer>` to override the derived code

The channel is inferred from the SemVer string when the channel trailer is
omitted. A stable SemVer such as `0.1.0` defaults to the `release` build
variant; add `SolarLab-Build-Variant: prerelease` when cutting a sideloadable
phone build before stable signing is ready. A prerelease SemVer such as
`0.1.1-alpha.1` defaults to the phone-installable `prerelease` build variant.

Trailer validation happens before the Android build. Duplicate release trailers,
malformed versions, unsupported channels, and unsupported build variants fail in
the gate job instead of starting the heavy APK lane.

## Current channels

### Internal dev preview

- Resumed laboratory release line: `0.2.0-alpha.N`
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
