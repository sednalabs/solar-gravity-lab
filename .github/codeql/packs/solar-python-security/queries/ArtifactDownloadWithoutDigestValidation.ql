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

predicate downloadsArtifactByApi(File file) {
  ciHelperFile(file) and
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?s).*actions/runs/.*/artifacts.*")
  )
}

predicate downloadsArtifactByCli(File file) {
  ciHelperFile(file) and
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?s).*gh.*run.*download.*")
  )
}

predicate mentionsApkSha(File file) {
  ciHelperFile(file) and
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?s).*apk_sha256.*")
  )
}

predicate mentionsShaMismatch(File file) {
  ciHelperFile(file) and
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?s).*SHA mismatch.*")
  )
}

predicate mentionsCommitSha(File file) {
  ciHelperFile(file) and
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?s).*commit_sha.*")
  )
}

predicate mentionsArtifactName(File file) {
  ciHelperFile(file) and
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?s).*artifact_name.*")
  )
}

predicate downloadsArtifact(File file) {
  functionInFile(_, file, "download_artifact") or
  downloadsArtifactByApi(file) or
  downloadsArtifactByCli(file)
}

predicate validatesArtifactDigest(File file) {
  functionInFile(_, file, "sha256_file") and
  mentionsApkSha(file) and
  mentionsShaMismatch(file)
}

predicate validatesArtifactIdentity(File file) {
  functionInFile(_, file, "validate_manifest_matches_request") and
  mentionsCommitSha(file) and
  mentionsArtifactName(file)
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
