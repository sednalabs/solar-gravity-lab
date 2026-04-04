# ADR 0001: Rust-owned authoritative core

## Status

Accepted

## Decision

The long-lived Solar Gravity Lab core is implemented in Rust.

## Rationale

- We want one authoritative engine shared across future native clients.
- We want strong type safety and explicit ownership in a simulation-heavy codebase.
- We want portable performance without committing the whole product to one
  platform UI or one vendor toolchain.

## Consequences

- Kotlin/JVM is no longer the long-term source of truth for runtime logic.
- Client shells integrate through FFI instead of re-implementing engine logic.
- Existing Kotlin simulation code becomes migration reference material rather
  than the permanent substrate.
