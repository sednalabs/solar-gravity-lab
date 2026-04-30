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
  exists(Call call | text = call.getTargetName())
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

predicate functionHasLocalSafeEvidence(Function function) {
  safeEvidenceText(function.getName().toString())
  or
  exists(Call call |
    call.getEnclosingCallable() = function and
    safeEvidenceText(call.getTargetName())
  )
  or
  exists(StringLiteralExpr literal |
    literal.getEnclosingCallable() = function and
    safeEvidenceText(literal.getTextValue())
  )
}

predicate functionHasSafeEvidence(Function function) {
  functionHasLocalSafeEvidence(function)
  or
  exists(Call call, Function callee |
    call.getEnclosingCallable() = function and
    call.getStaticTarget() = callee and
    functionHasLocalSafeEvidence(callee)
  )
}

predicate functionHasLocalAppendEvidence(Function function) {
  exists(Call call |
    call.getEnclosingCallable() = function and
    appendEvidenceText(call.getTargetName())
  )
  or
  exists(StringLiteralExpr literal |
    literal.getEnclosingCallable() = function and
    appendEvidenceText(literal.getTextValue())
  )
}

predicate functionHasAppendEvidence(Function function) {
  functionHasLocalAppendEvidence(function)
  or
  exists(Call call, Function callee |
    call.getEnclosingCallable() = function and
    call.getStaticTarget() = callee and
    functionHasLocalAppendEvidence(callee)
  )
}

predicate fileHasSafeEvidence(File file) {
  exists(Function function | function.getFile() = file and functionHasLocalSafeEvidence(function))
  or
  exists(StringLiteralExpr literal | literal.getFile() = file and safeEvidenceText(literal.getTextValue()))
}

predicate fileHasAppendEvidence(File file) {
  exists(Function function | function.getFile() = file and functionHasLocalAppendEvidence(function))
  or
  exists(StringLiteralExpr literal | literal.getFile() = file and appendEvidenceText(literal.getTextValue()))
}

predicate literalHasSafeEvidence(StringLiteralExpr literal) {
  exists(Function function |
    literal.getEnclosingCallable() = function and
    functionHasSafeEvidence(function)
  )
  or
  (
    not exists(Callable callable | literal.getEnclosingCallable() = callable) and
    fileHasSafeEvidence(literal.getFile())
  )
}

predicate literalHasAppendEvidence(StringLiteralExpr literal) {
  exists(Function function |
    literal.getEnclosingCallable() = function and
    functionHasAppendEvidence(function)
  )
  or
  (
    not exists(Callable callable | literal.getEnclosingCallable() = callable) and
    fileHasAppendEvidence(literal.getFile())
  )
}

from StringLiteralExpr literal, string claimClass, string missingEvidence
where
  (
    claimClass = "safe" and
    missingEvidence = "no_bounds_or_sanitizer_guard" and
    safeClaimText(literal.getTextValue()) and
    not literalHasSafeEvidence(literal)
  )
  or
  (
    claimClass = "append_only" and
    missingEvidence = "no_append_only_open_or_write_guard" and
    appendOnlyClaimText(literal.getTextValue()) and
    not literalHasAppendEvidence(literal)
  )
select literal,
  "This Rust safety or append-only claim lacks recognized evidence in the same function, a directly called helper, or the file for top-level literals. claim_class=" +
  claimClass + " missing_evidence=" + missingEvidence + "."
