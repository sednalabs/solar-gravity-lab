# Legacy v1 code on the v2 branch

The existing Kotlin/Android/Vulkan code still present at the repository root is
retained here only as migration reference material.

It is not the intended five-year architecture.

During the v2 build-out:

- new durable architecture work goes into `engine/`, `proto/`, `clients/`,
  `data/`, `services/`, and `labs/`
- old modules at the repository root are used as behavior references, data
  sources, benchmarks, and migration aids
- the v1 line remains releasable on its own branch, not by treating these
  modules as the long-term substrate of v2
