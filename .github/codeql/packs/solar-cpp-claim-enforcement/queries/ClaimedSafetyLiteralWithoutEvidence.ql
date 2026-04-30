/**
 * @name C/C++ safety string literal without recognized evidence
 * @description C/C++ safe/security-context string literals should have sanitizer, validation, bounds, or clamp evidence in the same function or a directly called helper.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id cpp/solar/claimed-safety-literal-without-evidence
 * @tags correctness
 *       maintainability
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

predicate safeClaimText(string text) {
  cppText(text) and
  text.regexpMatch("(?is).*(safe|safety).*") and
  text.regexpMatch("(?is).*(auth|digest|manifest|native|provenance|release|security|signed|trusted|verified|verification).*")
}

predicate safeEvidenceText(string text) {
  cppText(text) and
  text.regexpMatch("(?is).*(sanitize|sanitized|validate|validated|bounds|clamp|checked|min|max).*")
}

predicate functionHasLocalSafeEvidence(Function function) {
  exists(FunctionCall call | call.getEnclosingFunction() = function and safeEvidenceText(call.getTarget().getName()))
  or
  exists(StringLiteral literal | literal.getEnclosingFunction() = function and safeEvidenceText(literal.getValueText()))
}

predicate functionHasSafeEvidence(Function function) {
  functionHasLocalSafeEvidence(function)
  or
  exists(FunctionCall call, Function callee |
    call.getEnclosingFunction() = function and
    call.getTarget() = callee and
    functionHasLocalSafeEvidence(callee)
  )
}

from StringLiteral literal
where safeClaimText(literal.getValueText()) and not functionHasSafeEvidence(literal.getEnclosingFunction())
select literal,
  "This C/C++ string literal makes a safety claim in a trust-sensitive context without recognized sanitizer or bounds evidence in the same function or a directly called helper. claim_class=safe missing_evidence=no_bounds_or_sanitizer_guard."
