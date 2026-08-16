# CodeQL product invariants

Solar Gravity Lab uses CodeQL for security scanning and for a small set of
product-direction guardrails. Product-invariant queries are static early-warning
checks; they do not replace hosted validation, native Android observation, or
real-device hardware evidence.

## Current scope

The first product-invariant rollout covers the workflow and CI-helper surfaces
where CodeQL can give high-signal feedback without pretending to see runtime UI
quality:

- `solar-actions-product-invariants` checks workflow proof obligations for
  CodeQL pack compilation, stage-first Android validation, and hosted
  interactive evidence artifacts. It also guards the advanced CodeQL workflow's
  static category-matrix shape so alert comparison does not silently lose base
  branch categories.
- `solar-python-product-invariants` checks planner and summary helpers for
  runtime CPU truth lanes, Rust-backed stage-first proof, and provider-manifest
  evidence in interactive session summaries.

These packs intentionally sit beside the existing security packs rather than
inside them. The alerts are product-contract feedback, not vulnerability
claims.

## Severity policy

- `warning`: static product-direction smell. Investigate the changed path and
  decide whether the current proof route is still honest.
- `error`: reserved for narrow source-of-truth violations after a rule has
  survived a low-noise warning period.

New product-invariant rules should start as warnings unless they are highly
precise and tied directly to a single owned contract.

## Proof boundaries

CodeQL can detect missing or weakened proof routes. It cannot prove visual or
hardware claims by itself.

- Android camera, immersive runtime, and stage-first composition claims
  still require native Android observation or hosted interactive Android
  evidence.
- Active hardware acceleration claims still require hosted ARM64, runtime CPU
  truth, or real-device artifacts as appropriate.
- Query-pack changes require hosted `codeql-query-tests` and CodeQL Advanced
  proof before their SARIF should be trusted.

## Rollout checkpoint

The initial baseline established on 2026-05-06 verified the advisory warning
and error split across workflow and helper packs. Ongoing triage periodically
reviews warning noise, hardens mature invariants to errors, and adjusts query
definitions when workflow contracts evolve.
