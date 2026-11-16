#pragma once

#include <array>
#include <cstdint>
#include <limits>
#include <vector>

namespace solarlab::rendering {

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
    std::array<DrawCommandState, 5> draws{};
    ComputeCommandState medium;
    ComputeCommandState far;
    std::vector<uint32_t> trailStrips;
    bool operator==(const SceneCommandState&) const = default;
};

}  // namespace solarlab::rendering
