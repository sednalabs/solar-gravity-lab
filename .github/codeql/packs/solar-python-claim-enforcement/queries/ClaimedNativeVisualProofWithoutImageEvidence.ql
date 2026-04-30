/**
 * @name Python native visual proof claim without native image evidence
 * @description Python CI helpers that claim native Android visual proof or inline screenshots should assert native image content rather than text-only summaries.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id py/solar/claimed-native-visual-proof-without-image-evidence
 * @tags correctness
 *       maintainability
 *       product-invariants
 *       computer-use
 */

import python

predicate ciHelperFile(File file) {
  file.getRelativePath().regexpMatch("\\.github/scripts/.*\\.py")
}

predicate hasNativeVisualClaim(File file) {
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    literal.getText().regexpMatch("(?is).*(native visual proof|visual proof|inline screenshot|inline image|model-visible image|native image output|native Android observation).*")
  )
}

predicate hasNativeImageEvidence(File file) {
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    (
      literal.getText().regexpMatch("(?is).*(inputImage|input_image|native image content|native-image).*") or
      literal.getText().regexpMatch("(?is).*(android_observe|android_step).*(image|screenshot).*") or
      literal.getText().regexpMatch("(?is).*(assert|verify|require).*(image|screenshot).*")
    )
  )
}

from File file
where ciHelperFile(file) and hasNativeVisualClaim(file) and not hasNativeImageEvidence(file)
select file,
  "This Python helper claims native Android visual proof without recognized native-image evidence. Assert image content from android_observe/android_step before emitting visual proof claims."
