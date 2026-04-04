# ADR 0002: Versioned protobuf contracts

## Status

Accepted

## Decision

Persisted package, checkpoint, manifest, diagnostics, and scene contracts use
versioned protobuf schemas.

## Rationale

- The branch is meant to live for years and cross multiple clients.
- We need explicit schema evolution, strong field naming discipline, and
  language-neutral tooling.
- Offline bundles, live update manifests, and diagnostic artifacts all benefit
  from one coherent contract strategy.

## Consequences

- New persisted/runtime contracts must be designed in `proto/solarlab/v2`.
- Schema changes become reviewable API changes, not ad hoc model drift.
