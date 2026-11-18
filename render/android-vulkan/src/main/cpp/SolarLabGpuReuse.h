#pragma once

#include "SpacecraftMeshCatalog.h"
#include <array>
#include <cstdint>
#include <limits>
#include <vector>

namespace solarlab::rendering {

inline uint64_t SceneUploadRevision(uint64_t sceneIdentity, uint64_t cameraRevisionCounter,
    int processingModeCode, int observerModeCode, int traceLayerModeCode) {
    // The source is an unsigned scene hash, not an optional signed frame index.
    // Clamping its high-bit values to zero aliases half of all physical scenes
    // and leaves GPU poses stale when the camera settings have not changed.
    uint64_t revision = sceneIdentity;
    revision ^= (cameraRevisionCounter + 0x9E3779B97F4A7C15ULL + (revision << 6U) + (revision >> 2U));
    revision ^= (static_cast<uint64_t>(processingModeCode & 0xFFFF) << 16U);
    revision ^= (static_cast<uint64_t>(observerModeCode & 0xFFFF) << 32U);
    revision ^= (static_cast<uint64_t>(traceLayerModeCode & 0xFFFF) << 48U);
    // -1 is the upload sentinel. Fold every source bit before the final mask.
    revision &= static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
    return revision == 0 ? 1 : revision;
}

inline bool CanReuseBuffer(uint64_t capacity, uint64_t required, uint32_t usage, uint32_t requiredUsage,
    uint32_t memoryProperties, uint32_t requiredMemoryProperties) {
    return capacity >= required && usage == requiredUsage &&
        (memoryProperties & requiredMemoryProperties) == requiredMemoryProperties;
}

inline uint64_t BufferCapacity(uint64_t required) {
    // Bounded headroom avoids reallocating a growing trail on every new sample.
    uint64_t capacity = 256;
    while (capacity < required && capacity <= std::numeric_limits<uint64_t>::max() / 2) capacity *= 2;
    return capacity >= required ? capacity : required;
}

struct DrawCommandState {
    uint64_t bufferGeneration = 0;
    uint32_t vertexCount = 0;
    bool operator==(const DrawCommandState&) const = default;
};

struct ComputeCommandState {
    bool enabled = false;
    std::array<uint64_t, 6> bufferGenerations{};
    uint32_t sourceCount = 0;
    uint32_t dispatchCount = 0;
    uint32_t outputCapacity = 0;
    uint32_t tileCounterCount = 0;
    bool operator==(const ComputeCommandState&) const = default;
};

struct SceneCommandState {
    bool flightPerspective = false;
    std::array<DrawCommandState, 6 + 2 * solarlab::spacecraft::kMeshKinds.size()> draws{};
    std::array<uint32_t, solarlab::spacecraft::kMeshKinds.size()> meshInstanceCounts{};
    ComputeCommandState medium;
    ComputeCommandState far;
    std::vector<uint32_t> trailStrips;
    bool operator==(const SceneCommandState&) const = default;
};

}  // namespace solarlab::rendering
