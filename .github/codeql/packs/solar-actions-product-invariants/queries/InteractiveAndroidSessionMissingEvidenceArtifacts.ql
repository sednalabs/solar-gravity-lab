/**
 * @name Interactive Android session is missing evidence artifacts
 * @description Hosted Android visual proof must preserve session artifacts and summary output so reviewers can inspect the exact interactive evidence.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id actions/solar/interactive-android-session-missing-evidence-artifacts
 * @tags maintainability
 *       product-invariants
 *       android
 */

import actions

predicate interactiveSessionWorkflow(Workflow workflow) {
  workflow.getName() = "interactive-android-session"
}

predicate uploadsInteractiveEvidence(Workflow workflow) {
  exists(UsesStep step, string path |
    step.getEnclosingWorkflow() = workflow and
    step.getCallee().regexpMatch("actions/upload-artifact(@.*)?") and
    path = step.getArgument("path") and
    path.regexpMatch("(?s).*dist/interactive-session/\\*\\*.*") and
    path.regexpMatch("(?s).*dist/interactive-session-summary/\\*\\*.*")
  )
}

from Workflow workflow
where
  interactiveSessionWorkflow(workflow) and
  not uploadsInteractiveEvidence(workflow)
select workflow,
  "The hosted interactive Android session does not upload both session and summary artifacts. Visual acceptance needs durable evidence, not only a terminal log."
