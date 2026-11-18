#include "QcomTileMemory.h"

#include <algorithm>
#include <cstring>
#include <sstream>

namespace solarlab::rendering {
namespace {
// Initial budget, not a measured optimum. Leave most GMEM for attachments.
constexpr VkDeviceSize kMaximumCounterReservation = 512U * 1024U;
constexpr VkDeviceSize kHeapReservationDivisor = 8U;
}

void QcomTileMemory::ResetDevice() {
    device_ = VK_NULL_HANDLE;
    cmdBindTileMemory_ = nullptr;
    requested_ = false;
    enabled_ = false;
    features_.pNext = nullptr;
}

void QcomTileMemory::Probe(VkPhysicalDevice physicalDevice, const std::vector<VkExtensionProperties>& extensions) {
    ResetDevice();
    memoryProperties_ = {};
    features_.tileMemoryHeap = VK_FALSE;
    properties_.queueSubmitBoundary = VK_FALSE;
    properties_.tileBufferTransfers = VK_FALSE;
    heapBytes_ = 0;
    eligible_ = false;
    featureSupported_ = false;
    extensionSupported_ = std::any_of(extensions.begin(), extensions.end(), [](const auto& extension) {
        return std::strcmp(extension.extensionName, VK_QCOM_TILE_MEMORY_HEAP_EXTENSION_NAME) == 0;
    });
    reason_ = "extension-absent";
    if (!extensionSupported_) {
        return;
    }

    VkPhysicalDeviceFeatures2 features2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &features_, {}};
    vkGetPhysicalDeviceFeatures2(physicalDevice, &features2);
    featureSupported_ = features_.tileMemoryHeap == VK_TRUE;
    VkPhysicalDeviceProperties2 properties2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &properties_, {}};
    vkGetPhysicalDeviceProperties2(physicalDevice, &properties2);
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties_);
    for (uint32_t index = 0; index < memoryProperties_.memoryHeapCount; ++index) {
        const auto& heap = memoryProperties_.memoryHeaps[index];
        if ((heap.flags & VK_MEMORY_HEAP_TILE_MEMORY_BIT_QCOM) != 0U) {
            heapBytes_ = std::max(heapBytes_, heap.size);
        }
    }

    eligible_ = featureSupported_ && properties_.tileBufferTransfers == VK_TRUE && heapBytes_ != 0;
    reason_ = !featureSupported_ ? "feature-absent" :
        properties_.tileBufferTransfers != VK_TRUE ? "counter-transfers-unsupported" :
        heapBytes_ == 0 ? "tile-heap-absent" : "eligible";
}

bool QcomTileMemory::AppendDeviceFeature(std::vector<const char*>& extensions, void*& featureChain) {
    if (!eligible_ || requested_) {
        return false;
    }
    extensions.push_back(VK_QCOM_TILE_MEMORY_HEAP_EXTENSION_NAME);
    features_.tileMemoryHeap = VK_TRUE;
    features_.pNext = featureChain;
    featureChain = &features_;
    requested_ = true;
    return true;
}

bool QcomTileMemory::LoadDevice(VkDevice device) {
    device_ = device;
    if (!requested_ || device == VK_NULL_HANDLE) {
        return false;
    }
    cmdBindTileMemory_ = reinterpret_cast<PFN_vkCmdBindTileMemoryQCOM>(
        vkGetDeviceProcAddr(device, "vkCmdBindTileMemoryQCOM"));
    enabled_ = cmdBindTileMemory_ != nullptr;
    reason_ = enabled_ ? "enabled-no-allocation" : "entrypoint-absent";
    return enabled_;
}

bool QcomTileMemory::TryCreateCounterBuffer(VkDeviceSize bytes, QcomTileBuffer& output) {
    if (!enabled_ || bytes == 0 || (bytes & 3U) != 0U ||
        output.buffer != VK_NULL_HANDLE || output.memory != VK_NULL_HANDLE) {
        return false;
    }
    const VkDeviceSize maximumReservation = std::min(heapBytes_ / kHeapReservationDivisor, kMaximumCounterReservation);
    if (bytes > maximumReservation) {
        reason_ = "counter-reservation-exceeds-budget";
        return false;
    }

    QcomTileBuffer candidate{};
    const VkBufferCreateInfo bufferInfo{
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .size = bytes,
        .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TILE_MEMORY_BIT_QCOM,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
    };
    if (vkCreateBuffer(device_, &bufferInfo, nullptr, &candidate.buffer) != VK_SUCCESS) {
        reason_ = "tile-buffer-create-failed";
        return false;
    }
    const auto abandon = [&]() {
        vkDestroyBuffer(device_, candidate.buffer, nullptr);
        if (candidate.memory != VK_NULL_HANDLE) {
            vkFreeMemory(device_, candidate.memory, nullptr);
        }
        return false;
    };

    VkMemoryDedicatedRequirements dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS, nullptr, VK_FALSE, VK_FALSE};
    VkTileMemoryRequirementsQCOM tile{VK_STRUCTURE_TYPE_TILE_MEMORY_REQUIREMENTS_QCOM, &dedicated, 0, 0};
    VkMemoryRequirements2 requirements{VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2, &tile, {}};
    const VkBufferMemoryRequirementsInfo2 requirementsInfo{
        VK_STRUCTURE_TYPE_BUFFER_MEMORY_REQUIREMENTS_INFO_2, nullptr, candidate.buffer};
    vkGetBufferMemoryRequirements2(device_, &requirementsInfo, &requirements);
    if (tile.size == 0 || tile.alignment == 0 || tile.size > maximumReservation) {
        reason_ = "tile-requirements-unavailable-or-over-budget";
        return abandon();
    }

    uint32_t memoryType = UINT32_MAX;
    for (uint32_t index = 0; index < memoryProperties_.memoryTypeCount; ++index) {
        const auto& type = memoryProperties_.memoryTypes[index];
        const auto& heap = memoryProperties_.memoryHeaps[type.heapIndex];
        if ((requirements.memoryRequirements.memoryTypeBits & (1U << index)) != 0U &&
            (heap.flags & VK_MEMORY_HEAP_TILE_MEMORY_BIT_QCOM) != 0U &&
            (type.propertyFlags & VK_MEMORY_PROPERTY_PROTECTED_BIT) == 0U &&
            tile.size <= std::min(heap.size / kHeapReservationDivisor, kMaximumCounterReservation)) {
            memoryType = index;
            break;
        }
    }
    if (memoryType == UINT32_MAX) {
        reason_ = "compatible-tile-memory-type-absent";
        return abandon();
    }

    const VkMemoryDedicatedAllocateInfo dedicatedInfo{
        VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, nullptr, VK_NULL_HANDLE, candidate.buffer};
    const VkMemoryAllocateInfo allocation{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = dedicated.requiresDedicatedAllocation == VK_TRUE ? &dedicatedInfo : nullptr,
        .allocationSize = tile.size,
        .memoryTypeIndex = memoryType,
    };
    if (vkAllocateMemory(device_, &allocation, nullptr, &candidate.memory) != VK_SUCCESS ||
        vkBindBufferMemory(device_, candidate.buffer, candidate.memory, 0) != VK_SUCCESS) {
        reason_ = "tile-memory-allocation-or-bind-failed";
        return abandon();
    }
    // Offset zero satisfies tile alignment. Ordinary memory requirement sizes
    // and alignments do not describe resources bound to this heap.
    candidate.sizeBytes = bytes;
    candidate.allocationBytes = tile.size;
    candidate.memoryProperties = memoryProperties_.memoryTypes[memoryType].propertyFlags;
    output = candidate;
    reason_ = "allocation-created";
    return true;
}

bool QcomTileMemory::BindForCommands(VkCommandBuffer commandBuffer, VkDeviceMemory memory) const {
    if (!enabled_ || commandBuffer == VK_NULL_HANDLE || memory == VK_NULL_HANDLE) {
        return false;
    }
    // Outside render-pass scope, before the first action command. Keep the
    // reservation bound through the final counter copy in the same batch.
    const VkTileMemoryBindInfoQCOM binding{VK_STRUCTURE_TYPE_TILE_MEMORY_BIND_INFO_QCOM, nullptr, memory};
    cmdBindTileMemory_(commandBuffer, &binding);
    return true;
}

std::string QcomTileMemory::Summary() const {
    std::ostringstream summary;
    summary << "qcomTileHeap=[advertised=" << extensionSupported_
        << ",feature=" << featureSupported_ << ",transfers=" << (properties_.tileBufferTransfers == VK_TRUE)
        << ",enabled=" << enabled_ << ",heapBytes=" << heapBytes_
        << ",scope=" << (QueueSubmitBoundary() ? "queue-submit" : "submission-batch")
        << ",state=" << reason_ << "]";
    return summary.str();
}

} // namespace solarlab::rendering
