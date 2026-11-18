#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <string>
#include <vector>

namespace solarlab::rendering {

// An advertised extension is not an enabled feature or an executed workload.
struct VulkanCapabilities {
    uint32_t apiVersion = VK_API_VERSION_1_1;
    std::vector<std::string> extensions;
    VkPhysicalDeviceSubgroupProperties subgroup{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
    uint32_t timestampValidBits = 0;
    float timestampPeriod = 0.0f;
    bool timestampComputeAndGraphics = false;
    bool synchronization2 = false;
    bool timelineSemaphore = false;
    bool bufferDeviceAddress = false;
    bool shaderInt64 = false;
    bool pipelineExecutableInfo = false;
    bool calibratedTimestamps = false;
    bool calibratedTimestampsKhr = false;
    bool monotonicCalibration = false;

    bool HasExtension(const char* name) const;
    bool SupportsSubgroupCompaction() const;
    void Probe(VkInstance instance, VkPhysicalDevice physicalDevice,
        uint32_t instanceApiVersion, uint32_t queueFamilyIndex);
};

// Own the chain until vkCreateDevice returns. A copied chain would retain
// pNext links pointing at the old object's feature structures.
struct VulkanDeviceFeatures {
    VkPhysicalDeviceSynchronization2FeaturesKHR synchronization2{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES_KHR};
    VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR executable{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PIPELINE_EXECUTABLE_PROPERTIES_FEATURES_KHR};
    void* chain = nullptr;
    std::vector<const char*> extensions;
    bool calibratedTimestamps = false;
    bool calibratedTimestampsKhr = false;

    void Prepare(const VulkanCapabilities& supported, bool capturePipelineStatistics);
    void DisableOptional();
};

// Query results wrap at the queue family's timestampValidBits, not necessarily 64.
constexpr uint64_t TimestampDelta(uint64_t first, uint64_t last, uint32_t validBits) {
    if (validBits == 0 || validBits > 64) return 0;
    const uint64_t mask = validBits == 64 ? UINT64_MAX : ((uint64_t{1} << validBits) - 1U);
    return (last - first) & mask;
}

} // namespace solarlab::rendering
