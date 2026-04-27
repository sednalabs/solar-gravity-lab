/**
 * @name Release publishing without promoted-ref and provenance checks
 * @description Release creation or asset upload must be gated by promoted-ref validation and must publish provenance metadata with the release assets.
 * @kind problem
 * @problem.severity error
 * @precision high
 * @id actions/solar/unvetted-release-publish
 * @tags security
 *       release
 *       external/cwe/cwe-829
 */

import actions

predicate publishesRelease(Run run, string script) {
  script = run.getScript().getRawScript() and
  (
    script.regexpMatch("(?s).*gh release create\\b.*") or
    script.regexpMatch("(?s).*gh release upload\\b.*")
  )
}

predicate hasPromotedRefGuard(Run run) {
  exists(Run guard |
    guard.getEnclosingJob() = run.getEnclosingJob() and
    guard.getScript().getRawScript().regexpMatch("(?s).*Validate promoted release target.*") and
    guard.getScript().getRawScript().regexpMatch("(?s).*refs/heads/release/.*") and
    guard.getScript().getRawScript().regexpMatch("(?s).*RELEASE_TARGET.*")
  )
}

predicate publishesProvenance(string script) {
  script.regexpMatch("(?s).*release-provenance.*") and
  script.regexpMatch("(?s).*RELEASE_TARGET.*") and
  script.regexpMatch("(?s).*sha256.*")
}

from Run run, string script
where
  publishesRelease(run, script) and
  not (
    hasPromotedRefGuard(run) and
    publishesProvenance(script)
  )
select run,
  "This release publishing step must validate that the target is a promoted ref or exact proven SHA and upload release provenance metadata with the assets."
