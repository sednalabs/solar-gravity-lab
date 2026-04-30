/**
 * @name Workflow artifact trust claim without recognized enforcement evidence
 * @description Workflow steps that claim artifacts are verified, signed, attested, trusted, approved, or sealed should have nearby digest, identity, provenance, or permission evidence.
 * @kind problem
 * @problem.severity warning
 * @precision medium
 * @id actions/solar/claimed-artifact-trust-without-evidence
 * @tags correctness
 *       maintainability
 *       provenance
 *       product-invariants
 */

import actions

predicate usesStepText(UsesStep step, string text) {
  text = step.toString()
  or
  text = step.getCallee()
  or
  text = step.getVersion()
  or
  text = step.getArgument("artifact")
  or
  text = step.getArgument("artifact-name")
  or
  text = step.getArgument("name")
  or
  text = step.getArgument("path")
  or
  text = step.getArgument("predicate")
  or
  text = step.getArgument("ref")
  or
  text = step.getArgument("subject-digest")
  or
  text = step.getArgument("subject-path")
}

predicate hasRunClaim(Run run, string claimClass) {
  exists(string script |
    script = run.getScript().getRawScript() and
    script.regexpMatch("(?is).*(artifact|apk|release|publish|provenance|manifest|digest|sha256|checksum|asset|ref).*") and
    (
      claimClass = "signed" and script.regexpMatch("(?is).*(signed|signature).*")
      or
      claimClass = "attested" and script.regexpMatch("(?is).*(attested|attestation).*")
      or
      claimClass = "approved" and script.regexpMatch("(?is).*(approved|approval).*")
      or
      claimClass = "sealed" and script.regexpMatch("(?is).*(sealed|seal).*")
      or
      claimClass = "trusted" and script.regexpMatch("(?is).*(trusted|trust).*")
      or
      claimClass = "verified" and script.regexpMatch("(?is).*(verified|verify|verification).*")
    )
  )
}

predicate hasUsesClaim(UsesStep step, string claimClass) {
  exists(string text |
    usesStepText(step, text) and
    text.regexpMatch("(?is).*(artifact|apk|release|publish|provenance|manifest|digest|sha256|checksum|asset|ref).*") and
    (
      claimClass = "signed" and text.regexpMatch("(?is).*(signed|signature|attest-build-provenance).*")
      or
      claimClass = "attested" and text.regexpMatch("(?is).*(attested|attestation|attest-build-provenance).*")
      or
      claimClass = "approved" and text.regexpMatch("(?is).*(approved|approval).*")
      or
      claimClass = "sealed" and text.regexpMatch("(?is).*(sealed|seal).*")
      or
      claimClass = "trusted" and text.regexpMatch("(?is).*(trusted|trust).*")
      or
      claimClass = "verified" and text.regexpMatch("(?is).*(verified|verify|verification).*")
    )
  )
}

predicate hasClaim(Step step, string claimClass) {
  exists(Run run | run = step and hasRunClaim(run, claimClass))
  or
  exists(UsesStep usesStep | usesStep = step and hasUsesClaim(usesStep, claimClass))
}

predicate evidenceReachesClaim(Step evidence, Step claim) {
  evidence = claim
  or
  evidence.getAFollowingStep() = claim
}

predicate stepHasDigestOrIdentityEvidence(Step step) {
  exists(Run evidence, string script |
    evidence = step and
    script = evidence.getScript().getRawScript() and
    (
      script.regexpMatch("(?is).*sha256(sum)?\\b.*") or
      script.regexpMatch("(?is).*digest.*(match|mismatch|verify|validat|check).*") or
      script.regexpMatch("(?is).*(manifest|artifact).*identity.*") or
      script.regexpMatch("(?is).*(commit_sha|github\\.sha|GITHUB_SHA|target_sha).*")
    )
  )
  or
  exists(UsesStep evidence, string text |
    evidence = step and
    usesStepText(evidence, text) and
    (
      text.regexpMatch("(?is).*sha256(sum)?\\b.*") or
      text.regexpMatch("(?is).*digest.*(match|mismatch|verify|validat|check).*") or
      text.regexpMatch("(?is).*(manifest|artifact).*identity.*") or
      text.regexpMatch("(?is).*(commit_sha|github\\.sha|GITHUB_SHA|target_sha).*")
    )
  )
}

predicate hasDigestOrIdentityEvidence(Step claim) {
  exists(Step evidence |
    evidence.getEnclosingJob() = claim.getEnclosingJob() and
    evidenceReachesClaim(evidence, claim) and
    stepHasDigestOrIdentityEvidence(evidence)
  )
}

predicate stepHasProvenanceEvidence(Step step) {
  exists(Run evidence, string script |
    evidence = step and
    script = evidence.getScript().getRawScript() and
    script.regexpMatch("(?is).*provenance.*") and
    script.regexpMatch("(?is).*(json|upload|release|artifact|sha256|digest|commit).*")
  )
  or
  exists(UsesStep evidence, string text |
    evidence = step and
    usesStepText(evidence, text) and
    text.regexpMatch("(?is).*provenance.*") and
    text.regexpMatch("(?is).*(json|upload|release|artifact|sha256|digest|commit|attest-build-provenance).*")
  )
}

predicate hasProvenanceEvidence(Step claim) {
  exists(Step evidence |
    evidence.getEnclosingJob() = claim.getEnclosingJob() and
    evidenceReachesClaim(evidence, claim) and
    stepHasProvenanceEvidence(evidence)
  )
}

predicate hasCheckoutGuard(Step claim) {
  exists(UsesStep evidence, string ref, string persistCredentials |
    evidence.getEnclosingJob() = claim.getEnclosingJob() and
    evidenceReachesClaim(evidence, claim) and
    evidence.getCallee().regexpMatch("(?is)^actions/checkout($|@).*") and
    (
      ref = evidence.getArgument("ref") and
      (
        ref.regexpMatch("(?is).*refs/heads/.*") or
        ref.regexpMatch("(?is).*github\\.sha.*") or
        ref.regexpMatch("(?is).*GITHUB_SHA.*") or
        ref.regexpMatch("(?is).*target_sha.*")
      )
    ) and
    persistCredentials = evidence.getArgument("persist-credentials") and
    persistCredentials.regexpMatch("(?is)^false$")
  )
}

predicate hasTrustedWorkflowEvidence(Step claim) {
  exists(Workflow workflow |
    claim.getEnclosingWorkflow() = workflow and
    (
      workflow.toString().regexpMatch("(?is).*codeql.*") or
      workflow.toString().regexpMatch("(?is).*release.*") or
      workflow.toString().regexpMatch("(?is).*prerelease.*")
    )
  )
  and
  (
    exists(Run evidence, string script |
      evidence.getEnclosingJob() = claim.getEnclosingJob() and
      evidenceReachesClaim(evidence, claim) and
      script = evidence.getScript().getRawScript() and
      (
        script.regexpMatch("(?is).*refs/heads/.*") or
        script.regexpMatch("(?is).*github\\.sha.*") or
        script.regexpMatch("(?is).*GITHUB_SHA.*") or
        script.regexpMatch("(?is).*persist-credentials:\\s*false.*") or
        script.regexpMatch("(?is).*(validate|check).*target.*")
      )
    )
    or
    hasCheckoutGuard(claim)
  )
}

predicate hasEvidenceFor(Step claim, string claimClass) {
  claimClass = "signed" and hasDigestOrIdentityEvidence(claim) and hasProvenanceEvidence(claim)
  or
  claimClass = "attested" and hasProvenanceEvidence(claim)
  or
  claimClass = "approved" and hasTrustedWorkflowEvidence(claim)
  or
  claimClass = "sealed" and hasDigestOrIdentityEvidence(claim) and hasProvenanceEvidence(claim)
  or
  claimClass = "trusted" and (hasTrustedWorkflowEvidence(claim) or hasDigestOrIdentityEvidence(claim))
  or
  claimClass = "verified" and hasDigestOrIdentityEvidence(claim)
}

predicate missingEvidence(string claimClass, string missingEvidence) {
  claimClass = "signed" and missingEvidence = "no_signature_generation_or_verification"
  or
  claimClass = "attested" and missingEvidence = "no_release_or_artifact_provenance"
  or
  claimClass = "approved" and missingEvidence = "no_exact_ref_or_permission_guard"
  or
  claimClass = "sealed" and missingEvidence = "no_release_or_artifact_provenance"
  or
  claimClass = "trusted" and missingEvidence = "no_authz_or_identity_gate"
  or
  claimClass = "verified" and missingEvidence = "no_digest_or_manifest_check"
}

from Step step, string claimClass, string missingEvidence
where
  hasClaim(step, claimClass) and
  missingEvidence(claimClass, missingEvidence) and
  not hasEvidenceFor(step, claimClass)
select step,
  "This workflow step makes an artifact or release trust claim without recognized enforcement evidence. claim_class=" +
  claimClass + " missing_evidence=" + missingEvidence + "."
