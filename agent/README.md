# Solar Gravity Lab agent kit

This repository now includes a small repo-local agent kit so a coding session can
start from a real Git checkout, run a predictable bootstrap/test flow, and end
with a handoff zip that another person, Git workflow, or agent can apply.

## Supported input contract

This kit intentionally supports **real Git checkouts only**.

Do not upload a plain GitHub source snapshot if you want export/apply to work
cleanly. A snapshot is fine for reading code, but it drops `.git`, which means
there is no authoritative base commit, no reliable rename detection, and no safe
way to export a real patch.

The recommended flow is:

1. prepare a clean input bundle from a real clone with
   `./agent/prepare-upload-bundle.sh`
2. unpack that bundle into the working environment
3. run `./agent/bootstrap.sh`
4. run `./agent/test.sh smoke`
5. make changes
6. run `./agent/export-handoff.sh --base <base-ref-or-sha>`

## Scripts

- `./agent/bootstrap.sh`
  Verifies that the checkout shape and local toolchain match the canonical repo
  path.
- `./agent/test.sh <lane>`
  Runs the named validation lane.
- `./agent/export-handoff.sh --base <ref>`
  Produces a zip-first handoff under `out/agent/` containing both a patch and a
  file overlay.
- `./agent/apply-handoff.sh <handoff.zip>`
  Applies the patch from a handoff zip into another Git checkout.
- `./agent/prepare-upload-bundle.sh`
  Host-side packager for creating a clean upload bundle from a real clone.

## Validation lanes

The defaults match the canonical main-line described in the root `README.md`:

- `smoke`
  `cargo test --workspace`
- `rust-workspace`
  `cargo test --workspace`
- `ffi-abi`
  `cargo test -p solarlab-ffi`
- `runtime-scene-telemetry`
  `cargo test -p solarlab-runtime -p solarlab-scene -p solarlab-vulkan-adapter`
- `android-shell`
  `./gradlew -p clients/android --no-daemon :app:assembleDebug`
- `prerelease-apk`
  `./gradlew -p clients/android --no-daemon :app:assemblePrerelease`
- `android-shell-smoke`
  `.github/scripts/run_validation_android_shell_smoke.sh`
- `full`
  Rust workspace tests followed by Android shell assembly.

## Exported handoff format

`./agent/export-handoff.sh` writes a directory and a sibling zip file under
`out/agent/`.

Each handoff contains:

- `manifest.json`
- `changes.patch`
- `changed-files.txt`
- `diffstat.txt`
- `git-status.txt`
- `base.txt`
- `head.txt`
- `overlay/` with the full contents of changed and newly-added files
- `deleted-files.txt`
- optional copied notes if `--notes-file` was supplied

That means you are not locked to patch application alone. If `git apply` is
fussy, the overlay still provides the exact final file contents.

## Preparing an upload bundle

From a clean clone:

```bash
./agent/prepare-upload-bundle.sh \
  --head HEAD \
  --base origin/main \
  --task-file /absolute/path/to/TASK.md
```

The script creates a fresh clone at the requested commit, injects upload
metadata, and zips the result under `out/agent/`.

## Dependency bundles

A separate dependency bundle is **optional**, not foundational.

The right default is:

- keep repo-specific bootstrap logic inside this repository
- keep host-side packaging inside this repository
- only add external dependency bundles for large, slow, relatively stable
  toolchains such as Android SDK/NDK layers or other heavyweight caches

If you add external bundles later, version them explicitly and keep them outside
of the correctness path. The repo-local scripts should still explain the truth
about what is required even when those cached bundles are unavailable.
