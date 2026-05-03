/**
 * @name CodeQL query tests are missing the interactive Android tool contract guard
 * @description The hosted CodeQL query-test lane should run the static contract guard that protects native Android tool schema and end-to-end wiring.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id actions/solar/codeql-query-tests-missing-interactive-tool-contract-guard
 * @tags maintainability
 *       product-invariants
 *       android
 *       codeql
 */

import actions

predicate codeqlQueryTestWorkflow(Workflow workflow) {
  workflow.toString() = ".github/workflows/codeql-query-tests.yml"
}

predicate runsInteractiveToolContractGuard(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch(
      "(?s).*test_interactive_android_tool_contract\\.py.*"
    )
  )
}

from Workflow workflow
where
  codeqlQueryTestWorkflow(workflow) and
  not runsInteractiveToolContractGuard(workflow)
select workflow,
  "codeql-query-tests should run test_interactive_android_tool_contract.py so native Android tool schema and hosted wiring regressions are caught before relying on CodeQL product-invariant alerts."
