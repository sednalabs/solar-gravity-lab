# CodeQL claim enforcement

Solar Gravity Lab uses claim-enforcement checks to find places where code or CI
claims a release, artifact, update, or safety-sensitive path is verified,
signed, attested, approved, sealed, trusted, immutable, or append-only.

These checks are advisory static analysis. They are meant to surface claims that
deserve review, not to certify the repository or replace hosted validation.

## Surfaces

The rollout has two surfaces:

- CodeQL packs for GitHub Actions, Python, Rust, Java/Kotlin, and C/C++.
- A companion inventory script for comments, docs, proto files, scripts, and
  other text that CodeQL should not pretend to prove.

CodeQL alerts look for implementation-adjacent evidence. The inventory report is
broader and records claim language even when the right next step is human review
or wording cleanup.

## Evidence model

Recognized evidence is intentionally shallow and explainable:

- Actions evidence must be on the claiming step or an earlier step in the same
  job.
- Source-language evidence must be in the claiming function/callable or in a
  directly called helper.
- Rust top-level literals keep a file-level fallback because they may not have
  an enclosing callable.

The checks deliberately avoid broad whole-program proof. Deeper data flow should
only be added when repeated real findings show the shallow model is missing a
specific, reviewable pattern.

## Baseline policy

`codeql-query-tests` writes advisory inventory artifacts for every run:

- `claim-surfaces.json`
- `claim-enforcement-baseline.json`
- PR-only base/current comparison files when a pull request is being checked

On pull requests, the workflow compares the branch inventory with the base
commit and blocks only new implementation-surface findings whose status is
`missing_evidence`.

The gate currently covers these implementation surfaces:

- `actions`
- `c-cpp`
- `java-kotlin`
- `python`
- `rust`

Docs, proto files, and plain-text surfaces stay inventory-only during this
rollout. That preserves visibility without making prose wording changes block a
PR.

## Triage

For each new missing-evidence finding, choose one of three outcomes:

- Add or expose the enforcement evidence near the claim.
- Reword the claim so it no longer promises a stronger property than the code
  enforces.
- Keep the finding advisory only if the claim belongs to plain-text inventory
  or a deliberately documented exception.

Do not weaken the lexicon just to silence one useful finding. Prefer narrow
context gates or explicit evidence vocabulary after inspecting the actual alert
set.

## Hosted proof

Query-pack changes need both hosted proof surfaces before their alerts should be
trusted for review:

- `codeql-query-tests` for pack install, query resolution, query compilation,
  planner tests, inventory tests, and baseline comparison.
- `CodeQL Advanced` for the checked-in multi-language analysis path.
