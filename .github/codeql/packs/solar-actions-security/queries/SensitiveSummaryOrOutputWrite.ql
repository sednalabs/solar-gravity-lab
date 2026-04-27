/**
 * @name Sensitive workflow data written to public summaries or outputs
 * @description Workflow summaries and outputs should not receive raw tokens, secrets, passwords, private hostnames, or local runner paths.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id actions/solar/sensitive-summary-or-output-write
 * @tags security
 *       external/cwe/cwe-200
 */

import actions

predicate writesPublicChannel(string script) {
  script.regexpMatch("(?s).*GITHUB_STEP_SUMMARY.*") or
  script.regexpMatch("(?s).*GITHUB_OUTPUT.*")
}

predicate mentionsSensitiveMaterial(string script) {
  script.regexpMatch("(?is).*(token|secret|password|private[_-]?key|OPENAI_API_KEY|AWS_SECRET_ACCESS_KEY).*") or
  script.regexpMatch("(?s).*(/home/runner/|/home/\\S+|~/.codex).*")
}

from Run run, string script
where
  script = run.getScript().getRawScript() and
  writesPublicChannel(script) and
  mentionsSensitiveMaterial(script) and
  not script.regexpMatch("(?s).*credential source.*") and
  not script.regexpMatch("(?s).*see uploaded proof-validation artifact.*")
select run,
  "This step writes to a GitHub summary or output while handling sensitive material. Redact secrets, hostnames, and local paths before publishing."
