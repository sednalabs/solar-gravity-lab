# ADR 0004: Offline-first data with signed live updates

## Status

Accepted

## Decision

Solar Gravity Lab v2 uses offline-first scientific data packages with optional
signed live updates.

## Rationale

- The product must remain scientifically useful offline.
- We still want the ability to improve seeds, catalogs, and provenance over
  time without shipping a whole new app for every dataset change.
- The architecture should not force a network dependency into the core runtime.

## Consequences

- Local caches and package verification are part of the core architecture.
- Services are optional enhancers, not runtime prerequisites.
