/**
 * @name Rust trust string literal without recognized enforcement evidence
 * @description Rust string literals that claim verification, signing, attestation, approval, sealing, or trust should have nearby validation, digest, identity, provenance, or authorization evidence.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id rust/solar/claimed-trust-literal-without-evidence
 * @tags correctness
 *       maintainability
 *       provenance
 *       product-invariants
 */

import rust

predicate rustText(string text) {
  exists(Function function | text = function.getName().toString())
  or
  exists(StringLiteralExpr literal | text = literal.getTextValue())
}

predicate claimText(string text, string claimClass) {
  rustText(text) and
  (
    claimClass = "signed" and text.regexpMatch("(?is).*(signed|signature).*")
    or
    claimClass = "attested" and text.regexpMatch("(?is).*(attested|attestation).*")
    or
    claimClass = "approved" and text.regexpMatch("(?is).*(approved|approval).*")
    or
    claimClass = "sealed" and text.regexpMatch("(?is).*(sealed|seal).*")
    or
    claimClass = "trusted" and text.regexpMatch("(?is).*(trusted|trust).*")
    or
    claimClass = "verified" and text.regexpMatch("(?is).*(verified|verify|verification).*")
  )
}

predicate contextText(string text) {
  rustText(text) and
  text.regexpMatch("(?is).*(artifact|auth|capability|commit|digest|manifest|package|provenance|release|sha256|token|update).*")
}

predicate evidenceText(string text, string claimClass) {
  rustText(text) and
  (
    claimClass = "signed" and text.regexpMatch("(?is).*(signature|signed|digest|sha256|validate).*")
    or
    claimClass = "attested" and text.regexpMatch("(?is).*(provenance|attestation|manifest|digest).*")
    or
    claimClass = "approved" and text.regexpMatch("(?is).*(auth|authorization|identity|token|expected|validate).*")
    or
    claimClass = "sealed" and text.regexpMatch("(?is).*(digest|manifest|provenance|validate).*")
    or
    claimClass = "trusted" and text.regexpMatch("(?is).*(auth|authorization|identity|token|digest|manifest|validate).*")
    or
    claimClass = "verified" and text.regexpMatch("(?is).*(validate|digest|manifest|matches_locator|sha256|mismatch).*")
  )
}

predicate hasEvidence(File file, string claimClass) {
  exists(Function function |
    function.getFile() = file and
    evidenceText(function.getName().toString(), claimClass)
  )
  or
  exists(StringLiteralExpr literal |
    literal.getFile() = file and
    evidenceText(literal.getTextValue(), claimClass)
  )
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

from StringLiteralExpr literal, string claimClass, string missingEvidence
where
  claimText(literal.getTextValue(), claimClass) and
  contextText(literal.getTextValue()) and
  missingEvidence(claimClass, missingEvidence) and
  not hasEvidence(literal.getFile(), claimClass)
select literal,
  "This Rust string literal makes a trust claim without recognized enforcement evidence in the same file. claim_class=" +
  claimClass + " missing_evidence=" + missingEvidence + "."
