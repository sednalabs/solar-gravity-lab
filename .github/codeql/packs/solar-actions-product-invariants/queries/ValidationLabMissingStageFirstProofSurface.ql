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
  workflow.getName() = "validation-lab"
}

predicate workflowScriptMentions(Workflow workflow, string pattern) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().regexpMatch(pattern)
  )
}

from Workflow workflow
where
  validationLabWorkflow(workflow) and
  not (
    workflowScriptMentions(workflow, "(?s).*android[-_]validation[-_]mode.*") and
    workflowScriptMentions(workflow, "(?s).*stage_first_runtime_mirror.*") and
    workflowScriptMentions(workflow, "(?s).*preferred_gpu_backend.*")
  )
select workflow,
  "validation-lab does not appear to preserve stage-first runtime-mirror proof. Android visual or camera changes need this hosted proof route before visual claims are made."
