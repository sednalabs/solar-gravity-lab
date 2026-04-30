/**
 * @name Workflow native visual proof claim without native image evidence
 * @description Workflow steps that claim native Android visual proof or inline screenshots should include evidence that native image content was asserted.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id actions/solar/claimed-native-visual-proof-without-image-evidence
 * @tags correctness
 *       maintainability
 *       product-invariants
 *       computer-use
 */

import actions

predicate hasNativeVisualClaim(Run run) {
  exists(string script |
    script = run.getScript().getRawScript() and
    script.regexpMatch("(?is).*(native visual proof|visual proof|inline screenshot|inline image|model-visible image|native image output|native Android observation).*")
  )
}

predicate hasNativeImageEvidence(Job job) {
  exists(Run evidence, string script |
    evidence.getEnclosingJob() = job and
    script = evidence.getScript().getRawScript() and
    (
      script.regexpMatch("(?is).*(inputImage|input_image|native image content|native-image).*") or
      script.regexpMatch("(?is).*(android_observe|android_step).*(image|screenshot).*") or
      script.regexpMatch("(?is).*(assert|verify|require).*(image|screenshot).*")
    )
  )
}

from Run run
where hasNativeVisualClaim(run) and not hasNativeImageEvidence(run.getEnclosingJob())
select run,
  "This workflow step claims native Android visual proof without recognized native-image evidence. Record an assertion that android_observe/android_step returned image content before making visual claims."
