// Fault-injection coverage for actual production allocation/feature-chain code.
// It proves host contracts, not QCOM driver execution or hardware performance.
#include "../../main/cpp/QcomTileMemory.h"

#include <cassert>
#include <cstring>
#include <iostream>

namespace {
struct Driver {
    bool feature = true;
    bool transfers = true;
    bool entrypoint = true;
    bool allocationFails = false;
    bool bindingFails = false;
    bool dedicatedRequired = false;
    bool protectedTile = false;
    uint32_t allowedTypes = 3;
    VkDeviceSize tileSize = 4096;
    VkDeviceSize tileAlignment = 256;
    VkDeviceSize allocatedBytes = 0;
    uint32_t allocatedType = UINT32_MAX;
    VkBufferUsageFlags usage = 0;
    unsigned creates = 0;
    unsigned destroys = 0;
    unsigned frees = 0;
    unsigned binds = 0;
    bool dedicatedUsed = false;
} driver;

const auto physical = reinterpret_cast<VkPhysicalDevice>(uintptr_t{1});
const auto device = reinterpret_cast<VkDevice>(uintptr_t{2});
const auto bufferHandle = reinterpret_cast<VkBuffer>(uintptr_t{3});
const auto memoryHandle = reinterpret_cast<VkDeviceMemory>(uintptr_t{4});
const auto commandBuffer = reinterpret_cast<VkCommandBuffer>(uintptr_t{5});

void VKAPI_CALL BindTileMemory(VkCommandBuffer command, const VkTileMemoryBindInfoQCOM* binding) {
    assert(command == commandBuffer);
    assert(binding->sType == VK_STRUCTURE_TYPE_TILE_MEMORY_BIND_INFO_QCOM);
    assert(binding->memory == memoryHandle);
    ++driver.binds;
}

std::vector<VkExtensionProperties> Extensions() {
    VkExtensionProperties extension{};
    std::strcpy(extension.extensionName, VK_QCOM_TILE_MEMORY_HEAP_EXTENSION_NAME);
    extension.specVersion = 1;
    return {extension};
}

solarlab::rendering::QcomTileMemory EnabledAllocator() {
    solarlab::rendering::QcomTileMemory allocator;
    allocator.Probe(physical, Extensions());
    std::vector<const char*> extensions;
    void* chain = nullptr;
    assert(allocator.AppendDeviceFeature(extensions, chain));
    assert(extensions.size() == 1);
    const auto* feature = static_cast<const VkPhysicalDeviceTileMemoryHeapFeaturesQCOM*>(chain);
    assert(feature->tileMemoryHeap == VK_TRUE);
    assert(allocator.LoadDevice(device));
    return allocator;
}
} // namespace

extern "C" {
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice, VkPhysicalDeviceFeatures2* features) {
    auto* tile = static_cast<VkPhysicalDeviceTileMemoryHeapFeaturesQCOM*>(features->pNext);
    assert(tile->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_MEMORY_HEAP_FEATURES_QCOM);
    tile->tileMemoryHeap = driver.feature;
}
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceProperties2(VkPhysicalDevice, VkPhysicalDeviceProperties2* properties) {
    auto* tile = static_cast<VkPhysicalDeviceTileMemoryHeapPropertiesQCOM*>(properties->pNext);
    tile->queueSubmitBoundary = VK_FALSE;
    tile->tileBufferTransfers = driver.transfers;
}
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceMemoryProperties(VkPhysicalDevice, VkPhysicalDeviceMemoryProperties* properties) {
    *properties = {};
    properties->memoryHeapCount = 2;
    properties->memoryHeaps[0] = {1ULL << 30U, VK_MEMORY_HEAP_DEVICE_LOCAL_BIT};
    properties->memoryHeaps[1] = {12U * 1024U * 1024U, VK_MEMORY_HEAP_DEVICE_LOCAL_BIT | VK_MEMORY_HEAP_TILE_MEMORY_BIT_QCOM};
    properties->memoryTypeCount = 2;
    properties->memoryTypes[0] = {VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0};
    properties->memoryTypes[1] = {VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT |
        (driver.protectedTile ? VkMemoryPropertyFlags{VK_MEMORY_PROPERTY_PROTECTED_BIT} : 0U), 1};
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice, const char* name) {
    assert(std::strcmp(name, "vkCmdBindTileMemoryQCOM") == 0);
    return driver.entrypoint ? reinterpret_cast<PFN_vkVoidFunction>(BindTileMemory) : nullptr;
}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateBuffer(VkDevice, const VkBufferCreateInfo* info,
                                             const VkAllocationCallbacks*, VkBuffer* buffer) {
    driver.usage = info->usage;
    ++driver.creates;
    *buffer = bufferHandle;
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL vkGetBufferMemoryRequirements2(VkDevice,
    const VkBufferMemoryRequirementsInfo2*, VkMemoryRequirements2* requirements) {
    // Deliberately different ordinary and tile requirements catch accidental
    // allocation with ordinary sizes rather than the tile extension's values.
    requirements->memoryRequirements = {32768, 1024, driver.allowedTypes};
    auto* tile = static_cast<VkTileMemoryRequirementsQCOM*>(requirements->pNext);
    tile->size = driver.tileSize;
    tile->alignment = driver.tileAlignment;
    auto* dedicated = static_cast<VkMemoryDedicatedRequirements*>(tile->pNext);
    dedicated->requiresDedicatedAllocation = driver.dedicatedRequired;
}
VKAPI_ATTR VkResult VKAPI_CALL vkAllocateMemory(VkDevice, const VkMemoryAllocateInfo* info,
                                               const VkAllocationCallbacks*, VkDeviceMemory* memory) {
    driver.allocatedBytes = info->allocationSize;
    driver.allocatedType = info->memoryTypeIndex;
    driver.dedicatedUsed = info->pNext != nullptr;
    if (info->pNext != nullptr) {
        const auto* dedicated = static_cast<const VkMemoryDedicatedAllocateInfo*>(info->pNext);
        assert(dedicated->buffer == bufferHandle);
    }
    if (driver.allocationFails) {
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    *memory = memoryHandle;
    return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL vkBindBufferMemory(VkDevice, VkBuffer, VkDeviceMemory, VkDeviceSize offset) {
    assert(offset == 0);
    return driver.bindingFails ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL vkDestroyBuffer(VkDevice, VkBuffer, const VkAllocationCallbacks*) { ++driver.destroys; }
VKAPI_ATTR void VKAPI_CALL vkFreeMemory(VkDevice, VkDeviceMemory, const VkAllocationCallbacks*) { ++driver.frees; }
}

int main() {
    using solarlab::rendering::QcomTileBuffer;
    using solarlab::rendering::QcomTileMemory;
    for (unsigned unavailable = 0; unavailable != 3; ++unavailable) {
        driver = {};
        driver.feature = unavailable != 1;
        driver.transfers = unavailable != 2;
        QcomTileMemory allocator;
        allocator.Probe(physical, unavailable == 0 ? std::vector<VkExtensionProperties>{} : Extensions());
        assert(!allocator.Eligible());
        std::vector<const char*> extensions;
        void* chain = nullptr;
        assert(!allocator.AppendDeviceFeature(extensions, chain));
        QcomTileBuffer output;
        assert(!allocator.TryCreateCounterBuffer(128, output));
        assert(driver.creates == 0);
    }

    driver = {};
    auto allocator = EnabledAllocator();
    QcomTileBuffer output;
    assert(allocator.TryCreateCounterBuffer(128, output));
    assert(output.sizeBytes == 128 && output.allocationBytes == 4096);
    assert(driver.allocatedBytes == 4096 && driver.allocatedType == 1);
    assert(driver.usage == (VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
        VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TILE_MEMORY_BIT_QCOM));
    assert(allocator.BindForCommands(commandBuffer, output.memory));
    assert(driver.binds == 1);
    assert(!allocator.TryCreateCounterBuffer(128, output)); // Never overwrite owned handles.

    for (unsigned failure = 0; failure != 6; ++failure) {
        driver = {};
        driver.allowedTypes = failure == 0 ? 1 : 3;
        driver.protectedTile = failure == 1;
        driver.tileSize = failure == 2 ? 0 : failure == 3 ? 1024U * 1024U : 4096;
        driver.allocationFails = failure == 4;
        driver.bindingFails = failure == 5;
        auto failingAllocator = EnabledAllocator();
        QcomTileBuffer failedOutput;
        assert(!failingAllocator.TryCreateCounterBuffer(128, failedOutput));
        assert(failedOutput.buffer == VK_NULL_HANDLE && failedOutput.memory == VK_NULL_HANDLE);
        assert(driver.destroys == 1);
        assert(driver.frees == (failure == 5 ? 1U : 0U));
    }

    driver = {};
    driver.dedicatedRequired = true;
    auto dedicatedAllocator = EnabledAllocator();
    output = {};
    assert(dedicatedAllocator.TryCreateCounterBuffer(128, output));
    assert(driver.dedicatedUsed);

    driver = {};
    auto budgetAllocator = EnabledAllocator();
    output = {};
    assert(!budgetAllocator.TryCreateCounterBuffer(1024U * 1024U, output));
    assert(!budgetAllocator.TryCreateCounterBuffer(3, output));
    assert(driver.creates == 0);
    budgetAllocator.ResetDevice();
    assert(!budgetAllocator.Enabled());
    assert(!budgetAllocator.BindForCommands(commandBuffer, memoryHandle));
    std::cout << "QCOM tile-memory host contracts passed; no hardware execution claimed.\n";
}
