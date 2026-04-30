/**
 * @name CodeQL workflow is missing static category matrix guard
 * @description Advanced CodeQL should keep a static full-language PR matrix and validate that shape in query tests.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id actions/solar/codeql-static-matrix-guard
 * @tags maintainability
 *       product-invariants
 *       codeql
 */

import actions

predicate codeqlWorkflow(Workflow workflow) {
  workflow.toString() = ".github/workflows/codeql.yml"
}

predicate codeqlQueryTestWorkflow(Workflow workflow) {
  workflow.toString() = ".github/workflows/codeql-query-tests.yml"
}

predicate usesDynamicRouter(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch(
      "(?s).*(resolve_codeql_plan|validate_codeql_plan_contract|trusted PR file metadata).*"
    )
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

predicate validatesStaticWorkflowShape(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*test_codeql_static_workflow\\.py.*")
  )
}

from Workflow workflow
where
  codeqlWorkflow(workflow) and
  (
    usesDynamicRouter(workflow) or
    not analyzesWithLanguageCategory(workflow) or
    not exists(Workflow testWorkflow |
      codeqlQueryTestWorkflow(testWorkflow) and
      validatesStaticWorkflowShape(testWorkflow)
    )
  )
select workflow,
  "The advanced CodeQL workflow should use the static full-language matrix, upload SARIF with the matrix language category, and keep the static workflow shape covered by codeql-query-tests."
