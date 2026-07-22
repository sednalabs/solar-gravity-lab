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

predicate policyImplementationOrFixture(File file) {
  file.getRelativePath() = ".github/scripts/check_public_doc_safety.py"
  or file.getRelativePath() = ".github/scripts/test_interactive_session_summary.py"
}

predicate summaryLiteral(StringLiteral literal) {
  ciHelperFile(literal.getEnclosingModule().getFile()) and
  literal.getText().regexpMatch("(?is).*(token|secret|password|private[_-]?key|hostname|/home/runner|~/.codex).*")
}

predicate hardwareFeatureTokenLiteral(StringLiteral literal) {
  literal.getEnclosingModule().getFile().getRelativePath() = ".github/scripts/collect_android_capability_census.py" and
  literal.getText().regexpMatch("(?is).*(cpuinfo.*tokens?|raw_evidence_tokens|normalized_tokens|uncataloged.*tokens?|detected-tokens|feature.*tokens?).*")
}

predicate explicitlyRedacted(StringLiteral literal) {
  literal.getText().regexpMatch("(?is).*(redact|uploaded proof-validation artifact|credential source|not this GitHub summary).*")
  or policyImplementationOrFixture(literal.getEnclosingModule().getFile())
  or hardwareFeatureTokenLiteral(literal)
  or
  literal.getEnclosingModule().getFile().getRelativePath() = ".github/scripts/inventory_claim_surfaces.py" and
  literal.getText().regexpMatch("(?is).*(auth|authorization|token|identity|commit_sha|artifact_name|target_sha|release_target|GITHUB_SHA).*")
}

from StringLiteral literal
where summaryLiteral(literal) and not explicitlyRedacted(literal)
select literal,
  "This CI helper contains a sensitive summary literal. Ensure rendered Markdown redacts or indirects secrets, hostnames, and local paths."
