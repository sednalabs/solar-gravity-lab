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
  workflow.getFile().getRelativePath() = ".github/workflows/codeql-query-tests.yml"
}

predicate compilesActionsProductPack(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*solar-actions-product-invariants.*")
  )
}

predicate compilesPythonProductPack(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*solar-python-product-invariants.*")
  )
}

from Workflow workflow
where
  codeqlQueryTestWorkflow(workflow) and
  not (
    compilesActionsProductPack(workflow) and
    compilesPythonProductPack(workflow)
  )
select workflow,
  "The product-invariant CodeQL packs are not both covered by hosted query tests. Install, resolve, and compile them in codeql-query-tests before relying on their SARIF."
