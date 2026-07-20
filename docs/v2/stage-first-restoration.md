# Stage-first restoration history

The restoration track made the 3D stage the primary Android surface again,
retained the proven native Vulkan host, and moved controls into compact Compose
chrome around the rendered world.

That work has now converged on the canonical architecture documented in
[`stage-first-runtime.md`](stage-first-runtime.md): one
Rust-authoritative world, a directly bound native stage, and no alternate
managed simulation surface.

The durable restoration outcomes are:

- stage-first launch for debug, prerelease, and release builds;
- collapsed controls that leave the renderer dominant;
- native camera, selection, and Vulkan presentation;
- Rust-owned commands, observer state, history, and scene export; and
- native Android visual acceptance from an exact hosted build artifact.

Historical overlays or handoff bundles that describe a second Android world are
not product instructions and should not be reintroduced.
