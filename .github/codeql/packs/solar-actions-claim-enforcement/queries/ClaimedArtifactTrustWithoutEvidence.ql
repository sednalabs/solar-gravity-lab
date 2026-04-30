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

predicate hasClaim(Run run, string claimClass) {
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

predicate hasDigestOrIdentityEvidence(Job job) {
  exists(Run evidence, string script |
    evidence.getEnclosingJob() = job and
    script = evidence.getScript().getRawScript() and
    (
      script.regexpMatch("(?is).*sha256(sum)?\\b.*") or
      script.regexpMatch("(?is).*digest.*(match|mismatch|verify|validat|check).*") or
      script.regexpMatch("(?is).*(manifest|artifact).*identity.*") or
      script.regexpMatch("(?is).*(commit_sha|github\\.sha|GITHUB_SHA|target_sha).*")
    )
  )
}

predicate hasProvenanceEvidence(Job job) {
  exists(Run evidence, string script |
    evidence.getEnclosingJob() = job and
    script = evidence.getScript().getRawScript() and
    script.regexpMatch("(?is).*provenance.*") and
    script.regexpMatch("(?is).*(json|upload|release|artifact|sha256|digest|commit).*")
  )
}

predicate hasTrustedWorkflowEvidence(Job job) {
  exists(Workflow workflow |
    job.getEnclosingWorkflow() = workflow and
    (
      workflow.toString().regexpMatch("(?is).*codeql.*") or
      workflow.toString().regexpMatch("(?is).*release.*") or
      workflow.toString().regexpMatch("(?is).*prerelease.*")
    )
  )
  and
  exists(Run evidence, string script |
    evidence.getEnclosingJob() = job and
    script = evidence.getScript().getRawScript() and
    (
      script.regexpMatch("(?is).*refs/heads/.*") or
      script.regexpMatch("(?is).*github\\.sha.*") or
      script.regexpMatch("(?is).*GITHUB_SHA.*") or
      script.regexpMatch("(?is).*persist-credentials:\\s*false.*") or
      script.regexpMatch("(?is).*(validate|check).*target.*")
    )
  )
}

predicate hasEvidenceFor(Job job, string claimClass) {
  claimClass = "signed" and hasDigestOrIdentityEvidence(job) and hasProvenanceEvidence(job)
  or
  claimClass = "attested" and hasProvenanceEvidence(job)
  or
  claimClass = "approved" and hasTrustedWorkflowEvidence(job)
  or
  claimClass = "sealed" and hasDigestOrIdentityEvidence(job) and hasProvenanceEvidence(job)
  or
  claimClass = "trusted" and (hasTrustedWorkflowEvidence(job) or hasDigestOrIdentityEvidence(job))
  or
  claimClass = "verified" and hasDigestOrIdentityEvidence(job)
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

from Run run, string claimClass, string missingEvidence
where
  hasClaim(run, claimClass) and
  missingEvidence(claimClass, missingEvidence) and
  not hasEvidenceFor(run.getEnclosingJob(), claimClass)
select run,
  "This workflow step makes an artifact or release trust claim without recognized enforcement evidence. claim_class=" +
  claimClass + " missing_evidence=" + missingEvidence + "."
