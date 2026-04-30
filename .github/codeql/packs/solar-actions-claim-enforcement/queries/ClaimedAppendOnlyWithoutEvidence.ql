/**
 * @name Workflow append-only claim without recognized append evidence
 * @description Workflow scripts that claim append-only behavior should use append-oriented writes rather than direct replacement writes.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id actions/solar/claimed-append-only-without-evidence
 * @tags correctness
 *       maintainability
 *       product-invariants
 */

import actions

predicate hasAppendOnlyClaim(Run run) {
  exists(string script |
    script = run.getScript().getRawScript() and
    script.regexpMatch("(?is).*(append-only|append only).*")
  )
}

predicate hasAppendEvidence(Run run) {
  exists(string script |
    script = run.getScript().getRawScript() and
    (
      script.regexpMatch("(?s).*>>.*") or
      script.regexpMatch("(?is).*tee\\s+-a\\b.*") or
      script.regexpMatch("(?is).*append\\s*\\(.*") or
      script.regexpMatch("(?is).*OpenOptions.*append\\s*\\(\\s*true\\s*\\).*")
    )
  )
}

from Run run
where hasAppendOnlyClaim(run) and not hasAppendEvidence(run)
select run,
  "This workflow step claims append-only behavior without recognized append evidence. claim_class=append_only missing_evidence=no_append_only_open_or_write_guard."
