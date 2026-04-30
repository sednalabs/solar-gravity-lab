/**
 * @name CodeQL workflow is missing PR category parity guard
 * @description PR CodeQL runs must preserve the default-branch category set before uploading SARIF, otherwise GitHub cannot compare introduced alerts reliably.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id actions/solar/codeql-category-parity-guard
 * @tags maintainability
 *       product-invariants
 *       codeql
 */

import actions

predicate codeqlWorkflow(Workflow workflow) {
  workflow.toString() = ".github/workflows/codeql.yml"
}

predicate runsPlanContractValidator(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*validate_codeql_plan_contract\\.py.*")
  )
}

predicate analyzesWithLanguageCategory(Workflow workflow) {
  exists(UsesStep step, string category |
    step.getEnclosingWorkflow() = workflow and
    step.getCallee().regexpMatch("github/codeql-action/analyze(@.*)?") and
    category = step.getArgument("category") and
    category.regexpMatch("(?s).*/language:.*matrix\\.language.*")
  )
}

from Workflow workflow
where
  codeqlWorkflow(workflow) and
  not (
    runsPlanContractValidator(workflow) and
    analyzesWithLanguageCategory(workflow)
  )
select workflow,
  "The advanced CodeQL workflow should validate its emitted plan and upload SARIF with the matrix language category, so PR scans keep category parity with the default branch."
