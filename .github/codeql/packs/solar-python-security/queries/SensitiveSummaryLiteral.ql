/**
 * @name Sensitive summary literal in CI helper
 * @description CI helper scripts should avoid rendering raw sensitive field names or local paths into public Markdown summaries unless they explicitly redact or indirect the detail.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id py/solar/sensitive-summary-literal
 * @tags security
 *       external/cwe/cwe-200
 */

import python

predicate ciHelperFile(File file) {
  file.getRelativePath().regexpMatch("\\.github/scripts/.*\\.py")
}

predicate summaryLiteral(StringLiteral literal) {
  ciHelperFile(literal.getFile()) and
  literal.getValue().regexpMatch("(?is).*(token|secret|password|private[_-]?key|hostname|/home/runner|~/.codex).*")
}

predicate explicitlyRedacted(StringLiteral literal) {
  literal.getValue().regexpMatch("(?is).*(redact|uploaded proof-validation artifact|credential source|not this GitHub summary).*")
}

from StringLiteral literal
where summaryLiteral(literal) and not explicitlyRedacted(literal)
select literal,
  "This CI helper contains a sensitive summary literal. Ensure rendered Markdown redacts or indirects secrets, hostnames, and local paths."
