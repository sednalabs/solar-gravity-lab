#pragma once

namespace solarlab::spacecraft {
// Shared immutable geometry format for the offline glTF asset lane.
struct MeshVertex {
    float x, y, z, nx, ny, nz, r, g, b, metallic, roughness, emissive, effect;
};
static_assert(sizeof(MeshVertex) == 52);
}  // namespace solarlab::spacecraft
