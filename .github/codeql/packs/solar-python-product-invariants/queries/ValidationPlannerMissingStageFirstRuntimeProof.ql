/**
 * @name Validation planner is missing Rust stage proof
 * @description Android stage and camera changes need routing that proves the canonical Rust-backed stage runtime.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id py/solar/validation-planner-missing-stage-first-runtime-proof
 * @tags maintainability
 *       product-invariants
 *       android
 */

import python

predicate validationPlanner(File file) {
  file.getRelativePath() = ".github/scripts/resolve_validation_lab_plan.py"
}

predicate plannerMentionsAndroid(File file) {
  exists(StringLiteral literal |
    literal.getEnclosingModule().getFile() = file and
    literal.getText().regexpMatch("(?s).*android.*")
  )
}

predicate plannerMentionsStage(File file) {
  exists(StringLiteral literal |
    literal.getEnclosingModule().getFile() = file and
    literal.getText().regexpMatch("(?s).*stage.*")
  )
}

predicate plannerMentionsStageFirstRuntime(File file) {
  exists(StringLiteral literal |
    literal.getEnclosingModule().getFile() = file and
    literal.getText().regexpMatch("(?s).*stage-first-runtime.*")
  )
}

from File file
where
  validationPlanner(file) and
  (
    plannerMentionsAndroid(file) or
    plannerMentionsStage(file)
  ) and
  not plannerMentionsStageFirstRuntime(file)
select file,
  "The validation planner handles Android or stage surfaces but does not preserve stage-first-runtime proof. Static CodeQL checks cannot replace visual acceptance."
