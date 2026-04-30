/**
 * @name Java/Kotlin safety claim without recognized evidence
 * @description Java or Kotlin safe/security-context claims should have sanitizer, coercion, validation, or bounds evidence in the same callable or a directly called helper.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id java/solar/claimed-safety-without-evidence
 * @tags correctness
 *       maintainability
 *       product-invariants
 */

import java

predicate javaText(string text) {
  exists(Callable callable | text = callable.getName())
  or
  exists(StringLiteral literal | text = literal.getValue())
}

predicate safeClaimText(string text) {
  javaText(text) and
  text.regexpMatch("(?is).*(safe|safety).*") and
  text.regexpMatch("(?is).*(auth|bridge|digest|manifest|native|provenance|release|runtime|security|signed|trusted|verified|verification).*")
}

predicate safeEvidenceText(string text) {
  javaText(text) and
  text.regexpMatch("(?is).*(sanitize|sanitized|coerceIn|coerceAtLeast|validate|validated|bounds|clamp|checked).*")
}

predicate callableHasLocalSafeEvidence(Callable callable) {
  exists(Callable callee | callable.getACallee() = callee and safeEvidenceText(callee.getName()))
  or
  exists(StringLiteral literal | literal.getEnclosingCallable() = callable and safeEvidenceText(literal.getValue()))
}

predicate callableHasSafeEvidence(Callable callable) {
  callableHasLocalSafeEvidence(callable)
  or
  exists(Callable callee |
    callable.getACallee() = callee and
    callableHasLocalSafeEvidence(callee)
  )
}

from Callable callable
where safeClaimText(callable.getName()) and not callableHasSafeEvidence(callable)
select callable,
  "This Java/Kotlin callable name makes a safety claim in a trust-sensitive context without recognized sanitizer or bounds evidence in the same callable or a directly called helper. claim_class=safe missing_evidence=no_bounds_or_sanitizer_guard."
