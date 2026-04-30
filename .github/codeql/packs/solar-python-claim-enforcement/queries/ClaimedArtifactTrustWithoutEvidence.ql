/**
 * @name Python CI helper trust claim without recognized enforcement evidence
 * @description CI helper files that claim artifacts, manifests, releases, or refs are verified, signed, attested, trusted, approved, or sealed should validate digest, identity, or authorization evidence.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id py/solar/claimed-artifact-trust-without-evidence
 * @tags correctness
 *       maintainability
 *       provenance
 *       product-invariants
 */

import python

predicate ciHelperFile(File file) {
  file.getRelativePath().regexpMatch("\\.github/scripts/.*\\.py")
}

predicate hasTrustClaim(File file, string claimClass) {
  claimClass = "signed" and
  (
    exists(StringLiteral literal |
      file = literal.getEnclosingModule().getFile() and
      literal.getText().regexpMatch("(?is).*(signed|signature).*")
    )
    or
    exists(Function function |
      file = function.getEnclosingModule().getFile() and
      function.getName().regexpMatch("(?is).*(signed|signature).*")
    )
    or
    file.getRelativePath().regexpMatch("(?is).*(signed|signature).*")
  )
  or
  claimClass = "attested" and
  (
    exists(StringLiteral literal |
      file = literal.getEnclosingModule().getFile() and
      literal.getText().regexpMatch("(?is).*(attested|attestation).*")
    )
    or
    exists(Function function |
      file = function.getEnclosingModule().getFile() and
      function.getName().regexpMatch("(?is).*(attested|attestation).*")
    )
    or
    file.getRelativePath().regexpMatch("(?is).*(attested|attestation).*")
  )
  or
  claimClass = "approved" and
  (
    exists(StringLiteral literal |
      file = literal.getEnclosingModule().getFile() and
      literal.getText().regexpMatch("(?is).*(approved|approval).*")
    )
    or
    exists(Function function |
      file = function.getEnclosingModule().getFile() and
      function.getName().regexpMatch("(?is).*(approved|approval).*")
    )
    or
    file.getRelativePath().regexpMatch("(?is).*(approved|approval).*")
  )
  or
  claimClass = "sealed" and
  (
    exists(StringLiteral literal |
      file = literal.getEnclosingModule().getFile() and
      literal.getText().regexpMatch("(?is).*(sealed|seal).*")
    )
    or
    exists(Function function |
      file = function.getEnclosingModule().getFile() and
      function.getName().regexpMatch("(?is).*(sealed|seal).*")
    )
    or
    file.getRelativePath().regexpMatch("(?is).*(sealed|seal).*")
  )
  or
  claimClass = "trusted" and
  (
    exists(StringLiteral literal |
      file = literal.getEnclosingModule().getFile() and
      literal.getText().regexpMatch("(?is).*(trusted|trust).*")
    )
    or
    exists(Function function |
      file = function.getEnclosingModule().getFile() and
      function.getName().regexpMatch("(?is).*(trusted|trust).*")
    )
    or
    file.getRelativePath().regexpMatch("(?is).*(trusted|trust).*")
  )
  or
  claimClass = "verified" and
  (
    exists(StringLiteral literal |
      file = literal.getEnclosingModule().getFile() and
      literal.getText().regexpMatch("(?is).*(verified|verify|verification).*")
    )
    or
    exists(Function function |
      file = function.getEnclosingModule().getFile() and
      function.getName().regexpMatch("(?is).*(verified|verify|verification).*")
    )
    or
    file.getRelativePath().regexpMatch("(?is).*(verified|verify|verification).*")
  )
}

predicate artifactOrReleaseContext(File file) {
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?is).*(artifact|apk|release|publish|provenance|manifest|digest|sha256|checksum|asset|ref|commit).*")
  )
  or
  exists(Function function |
    file = function.getEnclosingModule().getFile() and
    function.getName().regexpMatch("(?is).*(artifact|apk|release|publish|provenance|manifest|digest|sha256|checksum|asset|ref|commit).*")
  )
  or
  file.getRelativePath().regexpMatch("(?is).*(artifact|apk|release|publish|provenance|manifest|digest|sha256|checksum|asset|ref|commit).*")
}

predicate expressionText(File file, string text) {
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    text = literal.getText()
  )
  or
  exists(Function function |
    file = function.getEnclosingModule().getFile() and
    text = function.getName()
  )
  or
  exists(Call call, Name name |
    file = call.getEnclosingModule().getFile() and
    call.getFunc() = name and
    text = name.getId()
  )
  or
  exists(Call call, Attribute attribute |
    file = call.getEnclosingModule().getFile() and
    call.getFunc() = attribute and
    text = attribute.getName()
  )
  or
  exists(Name name |
    file = name.getEnclosingModule().getFile() and
    text = name.getId()
  )
  or
  exists(Attribute attribute |
    file = attribute.getEnclosingModule().getFile() and
    text = attribute.getName()
  )
}

predicate hasDigestOrManifestEvidence(File file) {
  exists(string digestText, string guardText |
    expressionText(file, digestText) and
    expressionText(file, guardText) and
    digestText.regexpMatch("(?is).*(hashlib\\.sha256|sha256|sha256_file|apk_sha256|sha256sum|checksum|digest|manifest|hexdigest).*") and
    guardText.regexpMatch("(?is).*(compare_digest|mismatch|match|verify|validat|check|expected|raise|assert|SystemExit).*")
  )
}

predicate hasIdentityGateEvidence(File file) {
  exists(string identityText, string guardText |
    expressionText(file, identityText) and
    expressionText(file, guardText) and
    identityText.regexpMatch("(?is).*(commit_sha|artifact_name|workflow_file|run_id|github_sha|target_sha|release_target).*") and
    guardText.regexpMatch("(?is).*(mismatch|must match|validate|expected|raise|assert|SystemExit).*")
  )
}

predicate hasProvenanceEvidence(File file) {
  exists(string provenanceText, string evidenceText |
    expressionText(file, provenanceText) and
    expressionText(file, evidenceText) and
    provenanceText.regexpMatch("(?is).*provenance.*") and
    evidenceText.regexpMatch("(?is).*(json|manifest|sha256|digest|commit|artifact).*")
  )
}

predicate hasEvidenceFor(File file, string claimClass) {
  claimClass = "signed" and hasDigestOrManifestEvidence(file)
  or
  claimClass = "attested" and hasProvenanceEvidence(file)
  or
  claimClass = "approved" and hasIdentityGateEvidence(file)
  or
  claimClass = "sealed" and hasDigestOrManifestEvidence(file) and hasProvenanceEvidence(file)
  or
  claimClass = "trusted" and (hasIdentityGateEvidence(file) or hasDigestOrManifestEvidence(file))
  or
  claimClass = "verified" and hasDigestOrManifestEvidence(file)
}

predicate missingEvidence(string claimClass, string missingEvidence) {
  claimClass = "signed" and missingEvidence = "no_signature_generation_or_verification"
  or
  claimClass = "attested" and missingEvidence = "no_release_or_artifact_provenance"
  or
  claimClass = "approved" and missingEvidence = "no_authz_or_identity_gate"
  or
  claimClass = "sealed" and missingEvidence = "no_release_or_artifact_provenance"
  or
  claimClass = "trusted" and missingEvidence = "no_authz_or_identity_gate"
  or
  claimClass = "verified" and missingEvidence = "no_digest_or_manifest_check"
}

from File file, string claimClass, string missingEvidence
where
  ciHelperFile(file) and
  artifactOrReleaseContext(file) and
  hasTrustClaim(file, claimClass) and
  missingEvidence(claimClass, missingEvidence) and
  not hasEvidenceFor(file, claimClass)
select file,
  "This Python CI helper makes an artifact, release, manifest, or ref trust claim without recognized enforcement evidence. claim_class=" +
  claimClass + " missing_evidence=" + missingEvidence + "."
