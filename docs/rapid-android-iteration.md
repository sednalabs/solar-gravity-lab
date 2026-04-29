# Rapid Android iteration

Solar Gravity Lab supports a rapid hosted Android development loop for UI,
interaction, visual polish, and runtime-shell work. The goal is to keep one
hosted Android environment alive while changing only the piece that actually
needs to change.

This is not a replacement for merge readiness. It is the inner loop before the
final pull request checkpoint.

Do not let pull request mechanics become the iteration clock. If a PR already
exists, CI, review comments, and merge-readiness checks may run in parallel, but
they should not block the next visual or interaction hypothesis unless they
reveal that the current observation surface is untrustworthy. Open or update a
PR only when the work has become a coherent checkpoint; otherwise keep using the
branch, snapshot refs, targeted hosted proof, and the live Android session.

## Loop tiers

Use the narrowest tier that answers the current question.

1. Visual loop
   Use an already-running interactive Android session and the native Android
   computer-use connector to inspect, tap, wait, and compare the real UI.
   This is the right loop for composition, copy, control density, collapsed vs
   expanded chrome, scenario exploration, and visual acceptance notes.

2. APK loop
   Build a reusable Android artifact with `interactive-android-build`, then
   install that artifact into the already-running interactive session. Use this
   when app code changed but the hosted emulator, tunnel, and Android provider
   are still valid.

3. Targeted proof loop
   Dispatch `validation-lab` with a narrow lane set. Use this when the current
   question is build, test, runtime bridge, lint, shell smoke, FFI, or Arm64
   truth, but not yet final PR readiness.

4. Checkpoint loop
   Run the full required PR surface, resolve review threads, and collect final
   hosted evidence only when preparing to merge or when the change is broad
   enough that targeted proof would be misleading.

## Default Android UI loop

Start with a branch or snapshot ref that contains the current work. For normal
branch work, dispatch the reusable interactive build:

```bash
gh workflow run interactive-android-build.yml \
  --ref <branch> \
  -f ref=<branch> \
  -f android_validation_mode=stage-first-mirror-on \
  -f interactive_debug_profile=hosted-debug-lite
```

For higher-fidelity visual proof, use `interactive_debug_profile=full-fidelity`.

Start or reuse the hosted session:

```bash
gh workflow run interactive-android-session.yml \
  --ref <branch> \
  -f ref=<branch> \
  -f build_source=artifact \
  -f android_validation_mode=stage-first-mirror-on \
  -f interactive_debug_profile=hosted-debug-lite \
  -f emulator_boot_strategy=snapshot-cache \
  -f session_timeout_minutes=180 \
  -f keep_session_on_failure=true
```

Readiness is the long-running `Run hosted interactive Android session` step
being `in_progress`, not the workflow reaching a terminal state. The workflow
is expected to stay alive until the timeout or the operator-created finish
sentinel.

After a later app change, do not restart the session just to see the new APK.
Build a new reusable artifact, then ask the Android provider in the live
session to install it. Submit this payload as the arguments for the provider
MCP tool named `interactive_session.install_build_from_run`; it is not a
standalone shell command:

```json
{
  "workflow_run_id": 123456789,
  "artifact_name": "interactive-android-build-stage-first-mirror-on-hosted-debug-lite",
  "launch_after_install": true
}
```

The `artifact_name` must match the artifact emitted by the build run. The
example above matches the `stage-first-mirror-on` /
`hosted-debug-lite` command shown in this runbook. The provider verifies the
artifact manifest, installs the APK, relaunches the configured
package/activity, updates `dist/interactive-session/active-build.json`, and
appends install history for the evidence bundle.

## Targeted validation choices

Prefer targeted hosted proof while iterating:

```bash
gh workflow run validation-lab.yml \
  --ref <branch> \
  -f ref=<branch> \
  -f profile=targeted \
  -f lane_set=android-shell \
  -f android_test_scope=core \
  -f android_validation_mode=stage-first-mirror-on \
  -f android_artifact_mode=failures-only \
  -f emulator_boot_strategy=snapshot-cache
```

Change only the lane and inputs needed for the question:

- `lane_set=android-unit` for Android unit or host-side behavior.
- `lane_set=android-lint` for Android lint-only questions.
- `lane_set=android-shell` with `android_validation_mode=stage-first-mirror-on`
  for hosted app build, launch, and shell smoke questions.
- `lane_set=ffi-abi` for C ABI, JNI, or Android bridge contract questions.
- `lane_set=runtime-cpu-truth` when a change crosses physics dispatch, FFI
  runtime info, and Android telemetry.
- `lane_set=arm64-isa-proof` for CPU feature reporting, Arm64 solver dispatch,
  or scalar-oracle equivalence.
- `lane_set=arm64-capability-census` for capability inventory or schema-only
  questions.

Avoid starting several `validation-lab` dispatches on the same ref at once.
The workflow concurrency group cancels older same-ref runs. If two independent
proofs must be evaluated, use separate snapshot refs to run them in parallel,
or use a bundled lane such as `runtime-cpu-truth` to check them in a single run.

## Snapshot refs

When the work is not ready to commit to the feature branch, create a disposable
remote snapshot ref and validate that:

```bash
.github/scripts/validation_snapshot_ref.sh create <unique-snapshot-name>
```

Use the reported `snapshot_ref` as the `ref` input for `validation-lab` or
`interactive-android-build`. Delete it after the loop:

```bash
.github/scripts/validation_snapshot_ref.sh delete <snapshot_ref>
```

Snapshot refs let hosted runners prove the exact staged worktree without
turning local build artifacts into the source of truth.

## Visual acceptance

`validation-lab` proves build, test, lint, shell, and runtime contracts. It
does not prove that a stage-first or immersive screen is visually good.

For Android UI, visual design, stage-first, runtime mirror, scenario-pack, or
interaction-ergonomics changes, record a native observation note that names the
surfaces inspected. At minimum, check the changed path in the relevant compact
and expanded states. If live Android observation is unavailable, say visual
proof is blocked instead of treating CI as a substitute.

## Stop-the-line rules

Keep rapid iteration moving for:

- visual polish, copy, spacing, control density, and scenario tuning
- app-only UI behavior that can be hot-swapped with a new APK
- targeted validation failures with a narrow, understood cause
- exploratory native Android observations that do not require new plumbing

Stop the line for:

- hosted session workflow, tunnel, auth, or provider lifecycle changes
- Android provider semantics or native computer-use event changes
- Codex binary changes needed to expose or render new tool semantics
- runtime/physics correctness, FFI safety, security-sensitive behavior, or
  hardware acceleration truth
- any failure that makes the current visual observation surface untrustworthy

Stop-the-line work is not failure. It is the right choice when a foundational
fix will make later rapid loops safer, faster, or more truthful.

## Evidence to record

For each iteration, record:

- branch or snapshot ref
- interactive session run id
- currently installed build run id and commit SHA
- targeted validation run ids, if any
- native Android observation surfaces inspected
- whether visual proof passed, failed, or was blocked
- the next slice or stop-the-line blocker

Pull request review comments and full PR rollup checks do not have to block
every iteration. They must be addressed before checkpoint/merge readiness.
