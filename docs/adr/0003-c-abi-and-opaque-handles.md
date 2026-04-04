# ADR 0003: C ABI with opaque handles for client integration

## Status

Accepted

## Decision

Native clients talk to the Rust engine through a stable C ABI using opaque
handles and typed commands/events.

## Rationale

- Android and future native shells need a stable integration seam.
- Opaque handles let the engine evolve internally without making every client
  own internal memory layout knowledge.
- We do not want per-frame world serialization to be the primary client/runtime
  transport.

## Consequences

- FFI stability is a first-class compatibility surface.
- Engine internals can evolve freely behind handle-based APIs.
