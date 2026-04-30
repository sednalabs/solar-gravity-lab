/**
 * @name Validation planner is missing stage-first mirror proof
 * @description Android stage and camera changes need routing that can prove the stage-first runtime mirror instead of only shell or sandbox behavior.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id py/solar/validation-planner-missing-stage-first-mirror-proof
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

predicate plannerMentionsStageFirstMirror(File file) {
  exists(StringLiteral literal |
    literal.getEnclosingModule().getFile() = file and
    literal.getText().regexpMatch("(?s).*stage-first-mirror-on.*")
  )
}

from File file
where
  validationPlanner(file) and
  (
    plannerMentionsAndroid(file) or
    plannerMentionsStage(file)
  ) and
  not plannerMentionsStageFirstMirror(file)
select file,
  "The validation planner handles Android or stage surfaces but does not preserve stage-first-mirror-on proof. Static CodeQL checks cannot replace visual acceptance."
