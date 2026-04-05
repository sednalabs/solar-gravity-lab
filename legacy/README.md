# Legacy Kotlin reference code on canonical main

The existing Kotlin/Android/Vulkan code still present at the repository root is
retained here only as migration reference material.

It is not the canonical architecture of this branch.

On canonical `main`:

- new durable architecture work goes into `engine/`, `proto/`, `clients/`,
  `data/`, `services/`, and `labs/`
- old modules at the repository root are used as behavior references, data
  sources, benchmarks, and migration aids
- the root Gradle build intentionally does not treat those legacy modules as the
  active product surface

If you need to compare behavior against the older app, use the retained source
directly as an oracle. Do not assume the legacy modules are still the shipping
or validated line on canonical `main`.
