# Render adapters

This directory holds renderer-specific adapter crates for Solar Gravity Lab v2.

Each adapter consumes the shared `solarlab-scene::RenderScene` contract and
produces backend-facing packets or streams for one graphics API. Backends stay
out of the authoritative runtime and scene-extraction crates so scientific
truth, scene extraction, and GPU implementation can evolve independently.
