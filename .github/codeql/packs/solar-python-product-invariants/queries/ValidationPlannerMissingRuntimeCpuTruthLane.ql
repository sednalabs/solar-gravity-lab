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

predicate plannerMentionsArm64(File file) {
  exists(StringLiteral literal |
    literal.getEnclosingModule().getFile() = file and
    literal.getText().regexpMatch("(?s).*arm64.*")
  )
}

predicate plannerMentionsRuntimeCpuTruth(File file) {
  exists(StringLiteral literal |
    literal.getEnclosingModule().getFile() = file and
    literal.getText().regexpMatch("(?s).*runtime-cpu-truth.*")
  )
}

from File file
where
  validationPlanner(file) and
  plannerMentionsArm64(file) and
  not plannerMentionsRuntimeCpuTruth(file)
select file,
  "The validation planner handles Arm64 surfaces but does not expose the runtime-cpu-truth lane. Hardware truth changes need a focused proof route."
