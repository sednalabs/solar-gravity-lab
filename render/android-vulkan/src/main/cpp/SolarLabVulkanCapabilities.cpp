#include "SolarLabVulkanCapabilities.h"

#include <algorithm>

namespace solarlab::rendering {

bool VulkanCapabilities::HasExtension(const char* name) const {
    return std::find(extensions.begin(), extensions.end(), name) != extensions.end();
}

bool VulkanCapabilities::SupportsSubgroupCompaction() const {
    constexpr auto operations = VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_BALLOT_BIT;
    return (subgroup.supportedStages & VK_SHADER_STAGE_COMPUTE_BIT) != 0 &&
        (subgroup.supportedOperations & operations) == operations;
}

void VulkanCapabilities::Probe(VkInstance instance, VkPhysicalDevice physicalDevice,
    uint32_t instanceApiVersion, uint32_t queueFamilyIndex) {
    *this = VulkanCapabilities{};
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count, nullptr) == VK_SUCCESS) {
        std::vector<VkExtensionProperties> properties(count);
        const auto result = vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count, properties.data());
        if (result == VK_SUCCESS || result == VK_INCOMPLETE) {
            for (uint32_t index = 0; index < count; ++index) extensions.emplace_back(properties[index].extensionName);
        }
    }

    VkPhysicalDeviceProperties2 properties{.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &subgroup};
    vkGetPhysicalDeviceProperties2(physicalDevice, &properties);
    apiVersion = std::min(instanceApiVersion, properties.properties.apiVersion);
    timestampPeriod = properties.properties.limits.timestampPeriod;
    timestampComputeAndGraphics = properties.properties.limits.timestampComputeAndGraphics == VK_TRUE;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &count, nullptr);
    std::vector<VkQueueFamilyProperties> queues(count);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &count, queues.data());
    if (queueFamilyIndex < count) timestampValidBits = queues[queueFamilyIndex].timestampValidBits;

    VkPhysicalDeviceFeatures2 features{.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    VkPhysicalDeviceSynchronization2FeaturesKHR sync{.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES_KHR};
    VkPhysicalDeviceTimelineSemaphoreFeaturesKHR timeline{.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES_KHR};
    VkPhysicalDeviceBufferDeviceAddressFeaturesKHR address{.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES_KHR};
    VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR executable{.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PIPELINE_EXECUTABLE_PROPERTIES_FEATURES_KHR};
    if (apiVersion >= VK_API_VERSION_1_3 || HasExtension(VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME)) {
        sync.pNext = features.pNext; features.pNext = &sync;
    }
    if (apiVersion >= VK_API_VERSION_1_2 || HasExtension(VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME)) {
        timeline.pNext = features.pNext; features.pNext = &timeline;
    }
    if (apiVersion >= VK_API_VERSION_1_2 || HasExtension(VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME)) {
        address.pNext = features.pNext; features.pNext = &address;
    }
    if (HasExtension(VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME)) {
        executable.pNext = features.pNext; features.pNext = &executable;
    }
    vkGetPhysicalDeviceFeatures2(physicalDevice, &features);
    synchronization2 = sync.synchronization2 == VK_TRUE;
    timelineSemaphore = timeline.timelineSemaphore == VK_TRUE;
    bufferDeviceAddress = address.bufferDeviceAddress == VK_TRUE;
    shaderInt64 = features.features.shaderInt64 == VK_TRUE;
    pipelineExecutableInfo = executable.pipelineExecutableInfo == VK_TRUE;

    // KHR and EXT expose identical time-domain values. Use the EXT typedef to
    // also build with NDK headers predating the KHR promotion.
    calibratedTimestampsKhr = HasExtension("VK_KHR_calibrated_timestamps");
    calibratedTimestamps = calibratedTimestampsKhr || HasExtension(VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME);
    if (calibratedTimestamps) {
        const char* command = calibratedTimestampsKhr ? "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR" : "vkGetPhysicalDeviceCalibrateableTimeDomainsEXT";
        auto getDomains = reinterpret_cast<PFN_vkGetPhysicalDeviceCalibrateableTimeDomainsEXT>(vkGetInstanceProcAddr(instance, command));
        if (getDomains != nullptr && getDomains(physicalDevice, &count, nullptr) == VK_SUCCESS) {
            std::vector<VkTimeDomainEXT> domains(count);
            const auto result = getDomains(physicalDevice, &count, domains.data());
            if (result == VK_SUCCESS || result == VK_INCOMPLETE) {
                domains.resize(count);
                monotonicCalibration = std::find(domains.begin(), domains.end(), VK_TIME_DOMAIN_DEVICE_EXT) != domains.end() &&
                    std::find(domains.begin(), domains.end(), VK_TIME_DOMAIN_CLOCK_MONOTONIC_EXT) != domains.end();
            }
        }
    }
}

void VulkanDeviceFeatures::DisableOptional() {
    synchronization2.pNext = nullptr;
    synchronization2.synchronization2 = VK_FALSE;
    executable.pNext = nullptr;
    executable.pipelineExecutableInfo = VK_FALSE;
    bufferAddress.pNext = nullptr;
    bufferAddress.bufferDeviceAddress = VK_FALSE;
    chain = nullptr;
    extensions = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    calibratedTimestamps = false;
    calibratedTimestampsKhr = false;
}

void VulkanDeviceFeatures::Prepare(const VulkanCapabilities& supported, bool capturePipelineStatistics, bool addressCompaction) {
    DisableOptional();
    if (supported.synchronization2) {
        synchronization2.synchronization2 = VK_TRUE;
        synchronization2.pNext = chain; chain = &synchronization2;
        if (supported.apiVersion < VK_API_VERSION_1_3) extensions.push_back(VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
    }
    if (supported.pipelineExecutableInfo && capturePipelineStatistics) {
        executable.pipelineExecutableInfo = VK_TRUE;
        executable.pNext = chain; chain = &executable;
        extensions.push_back(VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME);
    }
    if (addressCompaction && supported.bufferDeviceAddress && supported.shaderInt64 && supported.SupportsSubgroupCompaction()) {
        bufferAddress.bufferDeviceAddress = VK_TRUE;
        bufferAddress.pNext = chain; chain = &bufferAddress;
        if (supported.apiVersion < VK_API_VERSION_1_2) extensions.push_back(VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
    }
    if (supported.calibratedTimestamps && supported.monotonicCalibration) {
        calibratedTimestamps = true;
        calibratedTimestampsKhr = supported.calibratedTimestampsKhr;
        extensions.push_back(calibratedTimestampsKhr ? "VK_KHR_calibrated_timestamps" : VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME);
    }
    // Timeline support is measured. Enabling it without a consumer does no
    // useful work; existing one-queue resource ownership remains explicit.
}

} // namespace solarlab::rendering
