/**
 * @name Validation workflow is missing stage-first Android proof surface
 * @description Android UI, camera, and stage-first changes need a hosted proof surface that can exercise the stage-first runtime mirror posture.
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
  workflow.getRelativePath() = ".github/workflows/validation-lab.yml"
}

predicate mentionsAndroidValidationMode(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*android[-_]validation[-_]mode.*")
  )
}

predicate mentionsStageFirstRuntimeMirror(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*stage_first_runtime_mirror.*")
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
    mentionsStageFirstRuntimeMirror(workflow) and
    mentionsPreferredGpuBackend(workflow)
  )
select workflow,
  "validation-lab does not appear to preserve stage-first runtime-mirror proof. Android visual or camera changes need this hosted proof route before visual claims are made."
