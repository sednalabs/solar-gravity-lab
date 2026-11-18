#pragma once

#include "QcomVulkanCompat.h"

#include <string>
#include <vector>

namespace solarlab::rendering {

// A successful allocation transfers ownership to the caller. Destroy it only
// after its submission completes, like any other GPU buffer.
struct QcomTileBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize sizeBytes = 0;
    VkDeviceSize allocationBytes = 0;
    VkMemoryPropertyFlags memoryProperties = 0;
};

// Narrow allocator for far-compaction counters. Vertex/indirect buffers,
// persistent scene inputs and CPU readbacks stay in ordinary memory. Reset and
// copy out the counters in the SAME submission batch, before content expires.
class QcomTileMemory {
public:
    void Probe(VkPhysicalDevice physicalDevice, const std::vector<VkExtensionProperties>& extensions);
    bool AppendDeviceFeature(std::vector<const char*>& extensions, void*& featureChain);
    bool LoadDevice(VkDevice device);
    void ResetDevice();

    bool TryCreateCounterBuffer(VkDeviceSize bytes, QcomTileBuffer& output);
    bool BindForCommands(VkCommandBuffer commandBuffer, VkDeviceMemory memory) const;

    [[nodiscard]] bool Enabled() const { return enabled_; }
    [[nodiscard]] bool Eligible() const { return eligible_; }
    [[nodiscard]] bool QueueSubmitBoundary() const { return properties_.queueSubmitBoundary == VK_TRUE; }
    [[nodiscard]] VkDeviceSize HeapBytes() const { return heapBytes_; }
    [[nodiscard]] std::string Summary() const;

private:
    VkPhysicalDeviceMemoryProperties memoryProperties_{};
    VkPhysicalDeviceTileMemoryHeapFeaturesQCOM features_{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_MEMORY_HEAP_FEATURES_QCOM, nullptr, VK_FALSE};
    VkPhysicalDeviceTileMemoryHeapPropertiesQCOM properties_{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_MEMORY_HEAP_PROPERTIES_QCOM, nullptr, VK_FALSE, VK_FALSE};
    VkDevice device_ = VK_NULL_HANDLE;
    PFN_vkCmdBindTileMemoryQCOM cmdBindTileMemory_ = nullptr;
    VkDeviceSize heapBytes_ = 0;
    bool extensionSupported_ = false;
    bool featureSupported_ = false;
    bool eligible_ = false;
    bool requested_ = false;
    bool enabled_ = false;
    std::string reason_ = "not-probed";
};

} // namespace solarlab::rendering
