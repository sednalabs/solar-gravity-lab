/**
 * @name Java/Kotlin trust claim without recognized enforcement evidence
 * @description Java or Kotlin callables and string literals that claim verification, signing, attestation, approval, sealing, or trust should have nearby validation, digest, identity, provenance, or authorization evidence.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id java/solar/claimed-trust-without-evidence
 * @tags correctness
 *       maintainability
 *       provenance
 *       product-invariants
 */

import java

predicate javaText(string text) {
  exists(Callable callable | text = callable.getName())
  or
  exists(StringLiteral literal | text = literal.getValue())
}

predicate claimText(string text, string claimClass) {
  javaText(text) and
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
  javaText(text) and
  text.regexpMatch("(?is).*(artifact|auth|bridge|capability|commit|digest|manifest|native|package|provenance|release|runtime|sha256|token|update).*")
}

predicate evidenceName(string text, string claimClass) {
  javaText(text) and
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

predicate callableHasLocalEvidence(Callable callable, string claimClass) {
  exists(Callable callee |
    callable.getACallee() = callee and
    evidenceName(callee.getName(), claimClass)
  )
  or
  exists(StringLiteral literal |
    literal.getEnclosingCallable() = callable and
    evidenceName(literal.getValue(), claimClass)
  )
}

predicate hasEvidenceFor(Callable callable, string claimClass) {
  callableHasLocalEvidence(callable, claimClass)
  or
  exists(Callable callee |
    callable.getACallee() = callee and
    callableHasLocalEvidence(callee, claimClass)
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

from Callable callable, string claimClass, string missingEvidence
where
  claimText(callable.getName(), claimClass) and
  contextText(callable.getName()) and
  missingEvidence(claimClass, missingEvidence) and
  not hasEvidenceFor(callable, claimClass)
select callable,
  "This Java/Kotlin callable name makes a trust claim without recognized enforcement evidence in the same callable or a directly called helper. claim_class=" +
  claimClass + " missing_evidence=" + missingEvidence + "."
