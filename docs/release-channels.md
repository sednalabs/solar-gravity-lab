# Release Channels

Solar Gravity Lab is still in an engineering-first phase, so release labels need
to stay honest about what is actually being shipped.

## Current channels

### Internal dev preview

- Version line: `0.1.0-alpha.2`
- Android build type: `prerelease`
- Base application id: `com.sednalabs.solarlab`
- Package id suffix: `.internal`
- Installed package id: `com.sednalabs.solarlab.internal`
- Signing: default debug key
- Intended use: sideloaded device testing and internal review

This is the first installable phone build channel, but it is not a public
production release. It is best thought of as an internal dev preview that uses
conservative semver prerelease numbering instead of a polished launch label.

### Remote validation

- Workflow: `.github/workflows/validation-lab.yml`
- Purpose: remote JVM and Android proof runs on GitHub-hosted runners
- Intended use: proving the current branch or seam without burning local Orchard
  compute

Validation runs are evidence, not release artifacts. They answer whether a
slice is green; they do not imply that the app is ready for external
distribution.

### Documentation-only changes

- Workflow: `.github/workflows/docs-sanity.yml`
- Purpose: lightweight checks for documentation updates
- Intended use: fast feedback for README/docs/workflow-doc changes without
  triggering expensive Android/JVM validation by default

## Naming guidance

Use wording like `internal dev preview`, `device test build`, or `engineering
preview` when talking about the current APK channel.

Avoid implying that this is already a stable public prerelease program. The
project can still publish a GitHub prerelease object for coordination, but that
object should describe the APK as an internal/device-testing artifact rather
than a consumer-ready launch.
