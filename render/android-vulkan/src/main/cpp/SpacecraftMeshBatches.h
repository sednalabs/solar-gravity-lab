#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace solarlab::spacecraft {
inline constexpr uint32_t kAsteriaMeshKind = 1;
inline constexpr uint32_t kRelayStationMeshKind = 2;
inline constexpr std::array<uint32_t, 2> kMeshKinds{kAsteriaMeshKind, kRelayStationMeshKind};

inline bool IsSupportedMesh(uint32_t kind) {
    return kind == kAsteriaMeshKind || kind == kRelayStationMeshKind;
}

struct MeshInstanceRange {
    uint32_t firstInstance = 0;
    uint32_t instanceCount = 0;
};

struct MeshInstancePlan {
    std::vector<uint32_t> sourceIndices;
    std::array<MeshInstanceRange, kMeshKinds.size()> ranges{};
};

// Keep the authoritative input order intact. Only GPU instance packing groups
// by immutable mesh identity, enabling one shared pipeline and one draw per mesh.
inline MeshInstancePlan PlanMeshInstances(std::span<const uint32_t> kinds) {
    MeshInstancePlan plan;
    plan.sourceIndices.reserve(kinds.size());
    for (size_t mesh = 0; mesh < kMeshKinds.size(); ++mesh) {
        auto& range = plan.ranges[mesh];
        range.firstInstance = static_cast<uint32_t>(plan.sourceIndices.size());
        for (size_t index = 0; index < kinds.size(); ++index) {
            if (kinds[index] == kMeshKinds[mesh]) {
                plan.sourceIndices.push_back(static_cast<uint32_t>(index));
                ++range.instanceCount;
            }
        }
    }
    return plan;
}
}  // namespace solarlab::spacecraft
