/**
 * @name Validation workflow is missing stage-first Android proof surface
 * @description Android UI, camera, and stage-first changes need a hosted proof surface that exercises the Rust-backed stage runtime.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id actions/solar/validation-lab-missing-stage-first-proof
 * @tags maintainability
 *       product-invariants
 *       android
 */

import actions

predicate validationLabWorkflow(Workflow workflow) {
  workflow.toString() = ".github/workflows/validation-lab.yml"
}

predicate mentionsAndroidValidationMode(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*android[-_]validation[-_]mode.*")
  )
}

predicate mentionsStageFirstClient(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*debug_stage_first_client.*")
  )
}

predicate mentionsPreferredGpuBackend(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*preferred_gpu_backend.*")
  )
}

from Workflow workflow
where
  validationLabWorkflow(workflow) and
  not (
    mentionsAndroidValidationMode(workflow) and
    mentionsStageFirstClient(workflow) and
    mentionsPreferredGpuBackend(workflow)
  )
select workflow,
  "validation-lab does not appear to preserve Rust-backed stage-first proof. Android visual or camera changes need this hosted proof route before visual claims are made."
