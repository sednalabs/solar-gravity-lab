/**
 * @name Direct secret write to workflow public channel
 * @description Secrets and private-key-like values must not be written directly to GitHub summaries or step outputs.
 * @kind problem
 * @problem.severity error
 * @precision high
 * @id actions/solar/direct-secret-public-channel-write
 * @tags security
 *       external/cwe/cwe-200
 */

import actions

from Run run, string script
where
  script = run.getScript().getRawScript() and
  (
    script.regexpMatch("(?s).*GITHUB_STEP_SUMMARY.*") or
    script.regexpMatch("(?s).*GITHUB_OUTPUT.*")
  ) and
  (
    script.regexpMatch("(?is).*\\$\\{\\{[^}]*secrets\\..*") or
    script.regexpMatch("(?s).*\\$(OPENAI_API_KEY|AWS_SECRET_ACCESS_KEY|GH_TOKEN|GITHUB_TOKEN).*") or
    script.regexpMatch("(?s).*\\$\\{(OPENAI_API_KEY|AWS_SECRET_ACCESS_KEY|GH_TOKEN|GITHUB_TOKEN)\\}.*") or
    script.regexpMatch("(?is).*private[_-]?key.*")
  ) and
  not script.regexpMatch("(?s).*credential source.*") and
  not script.regexpMatch("(?s).*redact.*")
select run,
  "This step writes a direct secret or private-key-like value to a GitHub workflow public channel. Redact or replace it with non-sensitive status metadata."
