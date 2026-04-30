/**
 * @name Python append-only claim without recognized append evidence
 * @description Python helpers that claim append-only behavior should open files in append mode or otherwise use append-only write semantics.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id py/solar/claimed-append-only-without-evidence
 * @tags correctness
 *       maintainability
 *       product-invariants
 */

import python

predicate ciHelperFile(File file) {
  file.getRelativePath().regexpMatch("\\.github/scripts/.*\\.py")
}

predicate hasAppendOnlyClaim(File file) {
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?is).*(append-only|append only).*")
  )
  or
  exists(Function function |
    file = function.getEnclosingModule().getFile() and
    function.getName().regexpMatch("(?is).*append.*")
  )
}

predicate hasAppendEvidence(File file) {
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?s).*[\"']a[\"'].*")
  )
}

from File file
where ciHelperFile(file) and hasAppendOnlyClaim(file) and not hasAppendEvidence(file)
select file,
  "This Python helper claims append-only behavior without recognized append evidence. claim_class=append_only missing_evidence=no_append_only_open_or_write_guard."
