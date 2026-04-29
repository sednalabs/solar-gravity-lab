/**
 * @name Validation planner is missing runtime CPU truth lane
 * @description Hardware-acceleration changes need a focused validation lane that exercises CPU truth across runtime, FFI, and Android presentation seams.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id py/solar/validation-planner-missing-runtime-cpu-truth-lane
 * @tags maintainability
 *       product-invariants
 *       hardware
 */

import python

predicate validationPlanner(File file) {
  file.getRelativePath() = ".github/scripts/resolve_validation_lab_plan.py"
}

predicate fileMentions(File file, string pattern) {
  exists(StringLiteral literal |
    literal.getEnclosingModule().getFile() = file and
    literal.getText().regexpMatch(pattern)
  )
}

from File file
where
  validationPlanner(file) and
  fileMentions(file, "(?s).*arm64.*") and
  not fileMentions(file, "(?s).*runtime-cpu-truth.*")
select file,
  "The validation planner handles Arm64 surfaces but does not expose the runtime-cpu-truth lane. Hardware truth changes need a focused proof route."
