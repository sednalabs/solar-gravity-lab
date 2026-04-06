# Release Channels

Solar Gravity Lab is still in an engineering-first phase, so release labels need
to stay honest about what is actually being shipped.

## Current channels

### Internal dev preview

- Version line: `0.1.0-alpha.10`
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
