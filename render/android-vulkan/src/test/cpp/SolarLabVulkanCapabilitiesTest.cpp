// Exercise production feature-chain gating using a deliberately inconsistent
// mock driver: advertised names, returned feature bits and callable entrypoints
// are independent inputs. No hardware-performance claim follows from this test.
#include "../../main/cpp/SolarLabVulkanCapabilities.h"

#include <algorithm>
#include <cassert>
#include <cstring>
#include <iostream>

namespace {
struct Driver {
    uint32_t apiVersion = VK_API_VERSION_1_1;
    std::vector<const char*> extensions;
    bool features = false;
    bool shaderInt64 = false;
    bool monotonic = true;
    bool domainFunction = true;
    bool computeSubgroups = true;
    VkSubgroupFeatureFlags subgroupOperations = VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_BALLOT_BIT;
    uint32_t featureQueries = 0;
} driver;

VkResult VKAPI_CALL Domains(VkPhysicalDevice, uint32_t* count, VkTimeDomainEXT* domains) {
    const uint32_t available = driver.monotonic ? 2U : 1U;
    if (domains == nullptr) { *count = available; return VK_SUCCESS; }
    assert(*count >= available);
    domains[0] = VK_TIME_DOMAIN_DEVICE_EXT;
    if (driver.monotonic) domains[1] = VK_TIME_DOMAIN_CLOCK_MONOTONIC_EXT;
    *count = available;
    return VK_SUCCESS;
}
bool Has(const solarlab::rendering::VulkanDeviceFeatures& features, const char* name) {
    return std::find_if(features.extensions.begin(), features.extensions.end(),
        [name](const char* extension) { return std::strcmp(extension, name) == 0; }) != features.extensions.end();
}
}

extern "C" {
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateDeviceExtensionProperties(VkPhysicalDevice, const char*,
    uint32_t* count, VkExtensionProperties* properties) {
    if (properties == nullptr) { *count = static_cast<uint32_t>(driver.extensions.size()); return VK_SUCCESS; }
    assert(*count >= driver.extensions.size());
    *count = static_cast<uint32_t>(driver.extensions.size());
    for (uint32_t index = 0; index < *count; ++index) std::strcpy(properties[index].extensionName, driver.extensions[index]);
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceProperties2(VkPhysicalDevice, VkPhysicalDeviceProperties2* properties) {
    properties->properties.apiVersion = driver.apiVersion;
    properties->properties.limits.timestampPeriod = 3.5f;
    properties->properties.limits.timestampComputeAndGraphics = VK_TRUE;
    auto* subgroup = static_cast<VkPhysicalDeviceSubgroupProperties*>(properties->pNext);
    assert(subgroup->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES);
    subgroup->subgroupSize = 32;
    subgroup->supportedStages = driver.computeSubgroups ? VK_SHADER_STAGE_COMPUTE_BIT : VK_SHADER_STAGE_FRAGMENT_BIT;
    subgroup->supportedOperations = driver.subgroupOperations;
}
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceQueueFamilyProperties(VkPhysicalDevice,
    uint32_t* count, VkQueueFamilyProperties* properties) {
    if (properties == nullptr) { *count = 1; return; }
    assert(*count == 1);
    properties[0].timestampValidBits = 36;
}
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice, VkPhysicalDeviceFeatures2* features) {
    features->features.shaderInt64 = driver.shaderInt64;
    for (auto* entry = static_cast<VkBaseOutStructure*>(features->pNext); entry != nullptr; entry = entry->pNext) {
        ++driver.featureQueries;
        switch (entry->sType) {
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES_KHR:
                reinterpret_cast<VkPhysicalDeviceSynchronization2FeaturesKHR*>(entry)->synchronization2 = driver.features; break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES_KHR:
                reinterpret_cast<VkPhysicalDeviceTimelineSemaphoreFeaturesKHR*>(entry)->timelineSemaphore = driver.features; break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES_KHR:
                reinterpret_cast<VkPhysicalDeviceBufferDeviceAddressFeaturesKHR*>(entry)->bufferDeviceAddress = driver.features; break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PIPELINE_EXECUTABLE_PROPERTIES_FEATURES_KHR:
                reinterpret_cast<VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR*>(entry)->pipelineExecutableInfo = driver.features; break;
            default: assert(false);
        }
    }
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance, const char* name) {
    assert(std::strcmp(name, "vkGetPhysicalDeviceCalibrateableTimeDomainsEXT") == 0 ||
        std::strcmp(name, "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR") == 0);
    return driver.domainFunction ? reinterpret_cast<PFN_vkVoidFunction>(Domains) : nullptr;
}
}

int main() {
    using namespace solarlab::rendering;
    VulkanCapabilities capabilities;
    VulkanDeviceFeatures enabled;
    capabilities.Probe(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_API_VERSION_1_3, 0);
    assert(driver.featureQueries == 0 && !capabilities.synchronization2 && !capabilities.bufferDeviceAddress);
    assert(capabilities.timestampValidBits == 36 && capabilities.timestampPeriod == 3.5f);
    assert(capabilities.SupportsSubgroupCompaction());
    enabled.Prepare(capabilities, true, true);
    assert(enabled.chain == nullptr && enabled.extensions.size() == 1);

    driver.extensions = {VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME, VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
        VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME, VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME,
        VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME};
    capabilities.Probe(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_API_VERSION_1_3, 0);
    assert(driver.featureQueries == 4 && !capabilities.synchronization2 && !capabilities.pipelineExecutableInfo);
    enabled.Prepare(capabilities, true, true);
    assert(enabled.chain == nullptr); // Names alone must never enable feature bits.
    assert(enabled.calibratedTimestamps && !enabled.calibratedTimestampsKhr);

    driver.features = true;
    capabilities.Probe(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_API_VERSION_1_3, 0);
    enabled.Prepare(capabilities, false, false);
    assert(enabled.synchronization2.synchronization2 && !enabled.executable.pipelineExecutableInfo);
    assert(!enabled.bufferAddress.bufferDeviceAddress && Has(enabled, VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME));
    enabled.Prepare(capabilities, true, true);
    assert(enabled.executable.pipelineExecutableInfo && enabled.bufferAddress.bufferDeviceAddress);
    // Adreno may expose physical-storage addresses without integer64 shaders.
    // Typed pointer compaction must remain eligible in that configuration.
    assert(!capabilities.shaderInt64 && enabled.bufferAddress.bufferDeviceAddress);
    assert(Has(enabled, VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME));
    unsigned chainLength = 0;
    for (auto* node = static_cast<VkBaseOutStructure*>(enabled.chain); node; node = node->pNext) {
        assert(++chainLength <= 3); // No dangling link/cycle after repeated preparation.
    }
    assert(chainLength == 3);
    enabled.DisableOptional();
    assert(enabled.chain == nullptr && enabled.extensions.size() == 1 && !enabled.calibratedTimestamps);
    assert(!enabled.bufferAddress.bufferDeviceAddress && !enabled.executable.pipelineExecutableInfo);

    driver.apiVersion = VK_API_VERSION_1_3;
    driver.extensions = {"VK_KHR_calibrated_timestamps"};
    capabilities.Probe(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_API_VERSION_1_3, 0);
    enabled.Prepare(capabilities, true, true);
    assert(enabled.synchronization2.synchronization2 && enabled.bufferAddress.bufferDeviceAddress);
    assert(!Has(enabled, VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME) && !Has(enabled, VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME));
    assert(enabled.calibratedTimestampsKhr);
    driver.monotonic = false;
    capabilities.Probe(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_API_VERSION_1_3, 0);
    enabled.Prepare(capabilities, false, true);
    assert(capabilities.calibratedTimestamps && !enabled.calibratedTimestamps);
    driver.domainFunction = false;
    driver.monotonic = true;
    capabilities.Probe(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_API_VERSION_1_3, 0);
    assert(!capabilities.monotonicCalibration);

    driver.computeSubgroups = false;
    capabilities.Probe(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_API_VERSION_1_3, 0);
    assert(!capabilities.SupportsSubgroupCompaction());
    enabled.Prepare(capabilities, false, true);
    assert(!enabled.bufferAddress.bufferDeviceAddress);
    driver.computeSubgroups = true;
    driver.subgroupOperations = VK_SUBGROUP_FEATURE_BASIC_BIT;
    capabilities.Probe(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_API_VERSION_1_3, 0);
    assert(!capabilities.SupportsSubgroupCompaction());
    assert(TimestampDelta(10, 19, 36) == 9);
    assert(TimestampDelta((uint64_t{1} << 36) - 5, 7, 36) == 12);
    assert(TimestampDelta(UINT64_MAX - 3, 2, 64) == 6);
    assert(TimestampDelta(10, 19, 0) == 0 && TimestampDelta(10, 19, 65) == 0);
    std::cout << "Vulkan capability and timestamp contracts passed; hardware remains untested.\n";
}
