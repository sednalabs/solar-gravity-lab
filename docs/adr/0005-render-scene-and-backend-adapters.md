# ADR 0005: Shared render-scene contract with backend adapters

## Status

Accepted

## Decision

The engine emits a renderer-independent `RenderScene` and optional
`RenderSceneDelta`, and graphics backends adapt that scene to the target GPU API.

## Rationale

- Scientific truth should not live inside one graphics backend.
- We want to support different client platforms over time without re-inventing
  scene extraction per platform.
- A stable scene contract is a better long-term boundary than ad hoc JNI packet
  formats tied to one renderer implementation.

## Consequences

- Scene extraction is a first-class engine subsystem.
- Backend implementations can evolve independently of the authoritative runtime.
