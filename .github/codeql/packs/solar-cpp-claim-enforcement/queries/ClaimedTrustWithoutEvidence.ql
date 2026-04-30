/**
 * @name C/C++ trust claim without recognized enforcement evidence
 * @description C/C++ functions and string literals that claim verification, signing, attestation, approval, sealing, or trust should have nearby validation, digest, identity, provenance, or authorization evidence.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id cpp/solar/claimed-trust-without-evidence
 * @tags correctness
 *       maintainability
 *       provenance
 *       product-invariants
 */

import cpp

predicate cppText(string text) {
  exists(Function function | text = function.getName())
  or
  exists(FunctionCall call | text = call.getTarget().getName())
  or
  exists(StringLiteral literal | text = literal.getValueText())
}

predicate claimText(string text, string claimClass) {
  cppText(text) and
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
  cppText(text) and
  text.regexpMatch("(?is).*(artifact|auth|capability|commit|digest|manifest|native|package|provenance|release|sha256|token|update).*")
}

predicate evidenceName(string text, string claimClass) {
  cppText(text) and
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
    claimClass = "verified" and text.regexpMatch("(?is).*(validate|digest|manifest|sha256|mismatch).*")
  )
}

predicate hasEvidenceFor(Function function, string claimClass) {
  exists(FunctionCall call |
    call.getEnclosingFunction() = function and
    evidenceName(call.getTarget().getName(), claimClass)
  )
  or
  exists(StringLiteral literal |
    literal.getEnclosingFunction() = function and
    evidenceName(literal.getValueText(), claimClass)
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

from Function function, string claimClass, string missingEvidence
where
  claimText(function.getName(), claimClass) and
  contextText(function.getName()) and
  missingEvidence(claimClass, missingEvidence) and
  not hasEvidenceFor(function, claimClass)
select function,
  "This C/C++ function name makes a trust claim without recognized enforcement evidence in the same function. claim_class=" +
  claimClass + " missing_evidence=" + missingEvidence + "."
