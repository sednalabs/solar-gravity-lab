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

predicate expressionText(File file, string text) {
  exists(StringLiteral literal |
    file = literal.getEnclosingModule().getFile() and
    text = literal.getText()
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

predicate hasNativeImageEvidence(File file) {
  exists(string text |
    expressionText(file, text) and
    (
      text.regexpMatch("(?is).*(inputImage|input_image|native image content|native-image).*") or
      text.regexpMatch("(?is).*(android_observe|android_step).*") or
      text.regexpMatch("(?is).*(image|screenshot|bitmap|pixel).*")
    )
  )
}

from File file
where ciHelperFile(file) and hasNativeVisualClaim(file) and not hasNativeImageEvidence(file)
select file,
  "This Python helper claims native Android visual proof without recognized native-image evidence. Assert image content from android_observe/android_step before emitting visual proof claims."
