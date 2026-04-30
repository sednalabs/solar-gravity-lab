/**
 * @name Rust safety or append-only claim without recognized evidence
 * @description Rust safe/security-context or append-only claims should have sanitizer/bounds evidence or append-open evidence nearby.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id rust/solar/claimed-safety-or-append-without-evidence
 * @tags correctness
 *       maintainability
 *       product-invariants
 */

import rust

predicate rustText(string text) {
  exists(Function function | text = function.getName().toString())
  or
  exists(StringLiteralExpr literal | text = literal.getTextValue())
}

predicate safeClaimText(string text) {
  rustText(text) and
  text.regexpMatch("(?is).*(safe|safety).*") and
  text.regexpMatch("(?is).*(auth|digest|manifest|provenance|release|security|signed|trusted|verified|verification).*")
}

predicate appendOnlyClaimText(string text) {
  rustText(text) and
  text.regexpMatch("(?is).*(append-only|append only).*")
}

predicate safeEvidenceText(string text) {
  rustText(text) and
  text.regexpMatch("(?is).*(sanitize|sanitized|validate|validated|bounds|clamp|coerce|checked).*")
}

predicate appendEvidenceText(string text) {
  rustText(text) and
  text.regexpMatch("(?is).*(append\\s*\\(\\s*true\\s*\\)|OpenOptions|append).*")
}

predicate fileHasSafeEvidence(SourceFile file) {
  exists(Function function | function.getFile() = file and safeEvidenceText(function.getName().toString()))
  or
  exists(StringLiteralExpr literal | literal.getFile() = file and safeEvidenceText(literal.getTextValue()))
}

predicate fileHasAppendEvidence(SourceFile file) {
  exists(StringLiteralExpr literal | literal.getFile() = file and appendEvidenceText(literal.getTextValue()))
}

from StringLiteralExpr literal, string claimClass, string missingEvidence
where
  (
    claimClass = "safe" and
    missingEvidence = "no_bounds_or_sanitizer_guard" and
    safeClaimText(literal.getTextValue()) and
    not fileHasSafeEvidence(literal.getFile())
  )
  or
  (
    claimClass = "append_only" and
    missingEvidence = "no_append_only_open_or_write_guard" and
    appendOnlyClaimText(literal.getTextValue()) and
    not fileHasAppendEvidence(literal.getFile())
  )
select literal,
  "This Rust safety or append-only claim lacks recognized evidence in the same file. claim_class=" +
  claimClass + " missing_evidence=" + missingEvidence + "."
