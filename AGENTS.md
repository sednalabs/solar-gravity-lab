# Repository Guidelines

## Project Structure & Module Organization

Canonical product code lives in `engine/`, `proto/`, `clients/android/`, `render/`, `services/`, and `labs/`. The Rust workspace in `engine/` owns simulation, history, scene export, hardware capability reporting, and FFI. `clients/android/` is the forward Android shell and release surface. The root `app`, `core-*`, `feature-lab`, and `render-core` modules are legacy reference code; do not deepen them unless you are deliberately harvesting parity behavior.

## Build, Test, and Development Commands

Use the smallest relevant command for the seam you touch:

- `cargo test --workspace` checks the canonical Rust platform.
- `cargo fmt --all` formats Rust code.
- `cargo clippy --workspace --all-targets` catches lint regressions.
- `./gradlew -p clients/android :app:assembleDebug` builds the forward Android shell.
- `python3 tools/validate_parity_matrix.py labs/parity/matrix.json` validates the parity contract used by CI.

Prefer remote proof for heavy validation: `validation-lab.yml` is the main verification lane, and `prerelease-apk.yml` is the installable artifact lane.

## Coding Style & Naming Conventions

Follow Rust 2021 defaults and keep `unsafe` out of the workspace unless explicitly justified; the workspace lints already deny it. Use `snake_case` for functions/modules, `UpperCamelCase` for types, and short scoped commit subjects such as `runtime: widen checkpoint parity`. Kotlin should stay idiomatic, 4-space indented, and thin at the shell boundary.

## Testing Guidelines

Put Rust unit tests near the owning crate and prefer deterministic physics, telemetry, and scene-contract assertions over UI-only checks. When changing runtime truth, add or update Rust-native parity coverage first, then prove the change remotely. Android validation should focus on shell flows, render readiness, and command/FFI integration, not duplicate core physics coverage.

## Commit & Pull Request Guidelines

Use imperative, scoped commit messages consistent with current history, for example `scene: add backend-neutral packet metadata`. PRs should state the user-visible effect, the authoritative validation run, and any remaining gaps. Include screenshots only for shell/UI changes.

## Remote-First Workflow

Treat GitHub Actions as the primary compute and proof surface for this public repo. Use local runs for fast iteration, but rely on exact-head remote validation before promotion or prerelease cuts. Always cut prereleases from the same commit that passed the remote proof lane.
