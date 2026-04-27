/**
 * @name Artifact download helper without digest validation
 * @description CI helpers that download reusable build artifacts should validate both manifest identity and artifact digest before exposing paths to later workflow steps.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id py/solar/artifact-download-without-digest-validation
 * @tags security
 *       provenance
 *       external/cwe/cwe-829
 */

import python

predicate ciHelperFile(File file) {
  file.getRelativePath().regexpMatch("\\.github/scripts/.*\\.py")
}

predicate functionInFile(Function function, File file, string name) {
  ciHelperFile(file) and
  file = function.getEnclosingModule().getFile() and
  function.getName() = name
}

predicate literalInFile(StringLiteral literal, File file, string text) {
  ciHelperFile(file) and
  file = literal.getEnclosingModule().getFile() and
  literal.getText().regexpMatch(text)
}

predicate downloadsArtifact(File file) {
  functionInFile(_, file, "download_artifact") or
  literalInFile(_, file, "(?s).*actions/runs/.*/artifacts.*") or
  literalInFile(_, file, "(?s).*gh.*run.*download.*")
}

predicate validatesArtifactDigest(File file) {
  functionInFile(_, file, "sha256_file") and
  literalInFile(_, file, "(?s).*apk_sha256.*") and
  literalInFile(_, file, "(?s).*SHA mismatch.*")
}

predicate validatesArtifactIdentity(File file) {
  functionInFile(_, file, "validate_manifest_matches_request") and
  literalInFile(_, file, "(?s).*commit_sha.*") and
  literalInFile(_, file, "(?s).*artifact_name.*")
}

from File file
where
  downloadsArtifact(file) and
  not (
    validatesArtifactDigest(file) and
    validatesArtifactIdentity(file)
  )
select file,
  "This CI artifact download helper does not appear to validate both manifest identity and SHA-256 digest before exposing the downloaded artifact."
