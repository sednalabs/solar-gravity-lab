/**
 * @name Product-invariant CodeQL packs are not compiled by hosted query tests
 * @description Product-invariant CodeQL packs must be installed, resolved, and compiled by codeql-query-tests before their alerts are trusted.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id actions/solar/product-invariant-packs-missing-hosted-compile
 * @tags maintainability
 *       product-invariants
 *       codeql
 */

import actions

predicate codeqlQueryTestWorkflow(Workflow workflow) {
  workflow.getName() = "codeql-query-tests"
}

predicate workflowScriptMentions(Workflow workflow, string pattern) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch(pattern)
  )
}

from Workflow workflow
where
  codeqlQueryTestWorkflow(workflow) and
  not (
    workflowScriptMentions(workflow, "(?s).*solar-actions-product-invariants.*") and
    workflowScriptMentions(workflow, "(?s).*solar-python-product-invariants.*")
  )
select workflow,
  "The product-invariant CodeQL packs are not both covered by hosted query tests. Install, resolve, and compile them in codeql-query-tests before relying on their SARIF."
