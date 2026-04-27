/**
 * @name APK artifact upload without provenance
 * @description APK upload-artifact steps should include provenance metadata so downstream jobs can verify the build source and digest.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id actions/solar/apk-artifact-without-provenance
 * @tags security
 *       provenance
 */

import actions

predicate isUploadArtifactStep(UsesStep step) {
  step.getCallee() = "actions/upload-artifact" or
  step.getCallee().regexpMatch("actions/upload-artifact@.*")
}

predicate uploadsApk(string path) {
  path.regexpMatch("(?s).*\\.apk(\\s|$).*") or
  path.regexpMatch("(?s).*/apk(/|\\s|$).*")
}

from UsesStep step, string path
where
  isUploadArtifactStep(step) and
  path = step.getArgument("path") and
  uploadsApk(path) and
  not path.regexpMatch("(?s).*provenance.*")
select step,
  "This APK artifact upload includes an APK but no provenance metadata path. Include build or release provenance with the uploaded artifact."
