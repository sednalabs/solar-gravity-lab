// Standalone, bounded Vulkan qualification workloads. No surface or authoritative
// simulation state is involved. Unsupported features report SKIP, never PASS.
#include "../src/main/cpp/QcomVulkanCompat.h"
#include "QcomProbeFixtures.h"
#include "QcomOffscreenProbe.h"
#ifdef SOLARLAB_EMBED_QCOM_SHADERS
#include "qcom_invert_control.h"
#include "qcom_tile_invert.h"
#include "qcom_box_control.h"
#include "qcom_box_filter.h"
#endif

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sstream>
#include <mutex>
#include <vector>

namespace {
std::atomic_uint validationErrors{0};
std::mutex probeMutex;
uint64_t Checksum(const void* pointer,size_t bytes) {
    const auto* data=static_cast<const uint8_t*>(pointer);
    uint64_t value=14695981039346656037ULL;
    for(size_t i=0;i<bytes;++i)value=(value^data[i])*1099511628211ULL;
    return value;
}

void Check(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) {
        throw std::runtime_error(std::string(operation) + " returned " + std::to_string(result));
    }
}

std::string JsonString(const std::string& value) {
    std::string result = "\"";
    for (unsigned char character : value) {
        if (character == '\\' || character == '"') result += '\\';
        if (character < 32U) result += '?';
        else result += static_cast<char>(character);
    }
    return result + '"';
}

VKAPI_ATTR VkBool32 VKAPI_CALL DebugCallback(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
    VkDebugUtilsMessageTypeFlagsEXT, const VkDebugUtilsMessengerCallbackDataEXT* data, void*) {
    if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0U) {
        ++validationErrors;
        std::cerr << "validation: " << data->pMessage << '\n';
    }
    return VK_FALSE;
}

struct Buffer { VkBuffer handle = VK_NULL_HANDLE; VkDeviceMemory memory = VK_NULL_HANDLE; };
struct Image { VkImage handle = VK_NULL_HANDLE; VkDeviceMemory memory = VK_NULL_HANDLE; VkImageView view = VK_NULL_HANDLE; };

class Probe {
public:
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = UINT32_MAX;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorLayout = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkShaderModule shader = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT messenger = VK_NULL_HANDLE;
    std::vector<Buffer> buffers;
    std::vector<Image> images;
    bool validation = false;
    std::string name;
    std::string skip;
    uint32_t driverVersion = 0;
    double maximumError = 0;
    uint64_t submissionNs = 0;
    uint64_t checksum = 0;
    PFN_vkCmdPipelineBarrier2 cmdBarrier2 = nullptr;
    PFN_vkCreateRenderPass2 createRenderPass2 = nullptr;
    PFN_vkCmdBeginPerTileExecutionQCOM beginTile = nullptr;
    PFN_vkCmdEndPerTileExecutionQCOM endTile = nullptr;

    ~Probe() {
        if (device != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device);
            if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, framebuffer, nullptr);
            if (renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, renderPass, nullptr);
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
            if (shader != VK_NULL_HANDLE) vkDestroyShaderModule(device, shader, nullptr);
            if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
            if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool, nullptr);
            if (descriptorLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorLayout, nullptr);
            if (sampler != VK_NULL_HANDLE) vkDestroySampler(device, sampler, nullptr);
            if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, nullptr);
            if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, nullptr);
            for (auto& image : images) {
                if (image.view != VK_NULL_HANDLE) vkDestroyImageView(device, image.view, nullptr);
                if (image.handle != VK_NULL_HANDLE) vkDestroyImage(device, image.handle, nullptr);
                if (image.memory != VK_NULL_HANDLE) vkFreeMemory(device, image.memory, nullptr);
            }
            for (auto& buffer : buffers) {
                if (buffer.handle != VK_NULL_HANDLE) vkDestroyBuffer(device, buffer.handle, nullptr);
                if (buffer.memory != VK_NULL_HANDLE) vkFreeMemory(device, buffer.memory, nullptr);
            }
            vkDestroyDevice(device, nullptr);
        }
        if (messenger != VK_NULL_HANDLE) {
            const auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT"));
            if (destroy != nullptr) destroy(instance, messenger, nullptr);
        }
        if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
    }

    bool Initialize(const std::string& mode, bool requireValidation) {
        uint32_t loaderVersion = VK_API_VERSION_1_0;
        const auto enumerateVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
        if (enumerateVersion != nullptr) Check(enumerateVersion(&loaderVersion), "enumerate instance version");
        if (loaderVersion < VK_API_VERSION_1_3) { skip = "probe-requires-vulkan-1.3"; return false; }

        uint32_t count = 0;
        Check(vkEnumerateInstanceLayerProperties(&count, nullptr), "enumerate layers");
        std::vector<VkLayerProperties> layers(count);
        Check(vkEnumerateInstanceLayerProperties(&count, layers.data()), "read layers");
        validation = std::any_of(layers.begin(), layers.end(), [](const auto& layer) {
            return std::strcmp(layer.layerName, "VK_LAYER_KHRONOS_validation") == 0;
        });
        Check(vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr), "enumerate instance extensions");
        std::vector<VkExtensionProperties> instanceExtensions(count);
        Check(vkEnumerateInstanceExtensionProperties(nullptr, &count, instanceExtensions.data()), "read instance extensions");
        const bool debug = std::any_of(instanceExtensions.begin(), instanceExtensions.end(), [](const auto& extension) {
            return std::strcmp(extension.extensionName, VK_EXT_DEBUG_UTILS_EXTENSION_NAME) == 0;
        });
        const bool enableValidation = validation && debug;
        validation = false;
        if (requireValidation && !enableValidation) { skip = "validation-layer-or-debug-utils-absent"; return false; }

        const char* layer = "VK_LAYER_KHRONOS_validation";
        const char* extension = VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
        const VkApplicationInfo application{VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr,
            "SolarLab QCOM offscreen qualification", 1, "SolarLab", 1, VK_API_VERSION_1_3};
        const VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, nullptr, 0, &application,
            enableValidation ? 1U : 0U, enableValidation ? &layer : nullptr, debug ? 1U : 0U, debug ? &extension : nullptr};
        const VkResult instanceResult = vkCreateInstance(&instanceInfo, nullptr, &instance);
        if (instanceResult == VK_ERROR_INCOMPATIBLE_DRIVER) { skip = "vulkan-driver-unavailable"; return false; }
        Check(instanceResult, "create instance");
        if (debug) {
            const auto create = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT"));
            const VkDebugUtilsMessengerCreateInfoEXT info{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT,
                nullptr, 0, VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT,
                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT,
                DebugCallback, nullptr};
            if (create == nullptr) throw std::runtime_error("debug-utils-create-entrypoint-absent");
            Check(create(instance, &info, nullptr, &messenger), "create validation messenger");
            if (messenger == VK_NULL_HANDLE) throw std::runtime_error("debug-utils-messenger-absent");
            validation = enableValidation;
        }
        Check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "enumerate devices");
        std::vector<VkPhysicalDevice> devices(count);
        Check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "read devices");
        for (auto candidate : devices) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(candidate, &properties);
            name = properties.deviceName;
            driverVersion = properties.driverVersion;
            if (properties.apiVersion < VK_API_VERSION_1_3) continue;
            Check(vkEnumerateDeviceExtensionProperties(candidate, nullptr, &count, nullptr), "enumerate device extensions");
            std::vector<VkExtensionProperties> extensions(count);
            Check(vkEnumerateDeviceExtensionProperties(candidate, nullptr, &count, extensions.data()), "read device extensions");
            const auto has = [&](const char* wanted) {
                return std::any_of(extensions.begin(), extensions.end(), [&](const auto& item) {
                    return std::strcmp(item.extensionName, wanted) == 0;
                });
            };
            const bool tileMode = mode == "tile-shading";
            const bool imageMode = mode == "image-processing";
            if (tileMode && (!has(VK_QCOM_TILE_SHADING_EXTENSION_NAME) || !has(VK_QCOM_TILE_PROPERTIES_EXTENSION_NAME))) continue;
            if (imageMode && !has(VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME)) continue;
            VkPhysicalDeviceTileShadingFeaturesQCOM tile{};
            tile.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_SHADING_FEATURES_QCOM;
            VkPhysicalDeviceImageProcessingFeaturesQCOM image{};
            image.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_FEATURES_QCOM;
            VkPhysicalDeviceVulkan13Features core{};
            core.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
            core.pNext = tileMode ? static_cast<void*>(&tile) : imageMode ? static_cast<void*>(&image) : nullptr;
            VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &core, {}};
            vkGetPhysicalDeviceFeatures2(candidate, &features);
            if (core.synchronization2 != VK_TRUE) continue;
            if (tileMode && (!tile.tileShading || !tile.tileShadingColorAttachments || !tile.tileShadingPerTileDispatch)) continue;
            if (imageMode && !image.textureBoxFilter) continue;
            if (imageMode) {
                VkPhysicalDeviceImageProcessingPropertiesQCOM imageProperties{};
                imageProperties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_PROPERTIES_QCOM;
                VkPhysicalDeviceProperties2 properties2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &imageProperties, {}};
                vkGetPhysicalDeviceProperties2(candidate, &properties2);
                if (imageProperties.maxBoxFilterBlockSize.width < 4 || imageProperties.maxBoxFilterBlockSize.height < 4) continue;
                VkFormatProperties3 format3{VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3, nullptr, 0, 0, 0};
                VkFormatProperties2 format2{VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2, &format3, {}};
                vkGetPhysicalDeviceFormatProperties2(candidate, VK_FORMAT_R8G8B8A8_UNORM, &format2);
                if ((format3.optimalTilingFeatures & VK_FORMAT_FEATURE_2_BOX_FILTER_SAMPLED_BIT_QCOM) == 0U) continue;
            }
            const bool boxMode = imageMode || mode == "box-control";
            const VkImageUsageFlags imageUsage = boxMode
                ? VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                : VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            VkImageFormatProperties imageFormat{};
            const VkResult formatResult = vkGetPhysicalDeviceImageFormatProperties(candidate,
                VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL, imageUsage, 0, &imageFormat);
            if (formatResult == VK_ERROR_FORMAT_NOT_SUPPORTED) continue;
            Check(formatResult, "preflight image usage");
            if (imageFormat.maxExtent.width < (boxMode ? 64U : 129U) ||
                imageFormat.maxExtent.height < (boxMode ? 64U : 73U) ||
                (imageFormat.sampleCounts & VK_SAMPLE_COUNT_1_BIT) == 0U) continue;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &count, nullptr);
            std::vector<VkQueueFamilyProperties> queues(count);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &count, queues.data());
            queueFamily = UINT32_MAX;
            for (uint32_t index = 0; index < count; ++index) {
                constexpr VkQueueFlags required = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                if (queues[index].queueCount > 0 && (queues[index].queueFlags & required) == required) { queueFamily = index; break; }
            }
            if (queueFamily == UINT32_MAX) continue;
            physical = candidate;
            std::vector<const char*> enabledExtensions;
            if (tileMode) enabledExtensions = {VK_QCOM_TILE_SHADING_EXTENSION_NAME, VK_QCOM_TILE_PROPERTIES_EXTENSION_NAME};
            if (imageMode) enabledExtensions = {VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME};
            core = {};
            core.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
            core.synchronization2 = VK_TRUE;
            tile = {};
            tile.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_SHADING_FEATURES_QCOM;
            tile.tileShading = tile.tileShadingColorAttachments = tile.tileShadingPerTileDispatch = VK_TRUE;
            image = {};
            image.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_FEATURES_QCOM;
            image.textureBoxFilter = VK_TRUE;
            core.pNext = tileMode ? static_cast<void*>(&tile) : imageMode ? static_cast<void*>(&image) : nullptr;
            const float priority = 1.0f;
            const VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, nullptr, 0,
                queueFamily, 1, &priority};
            const VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, &core, 0, 1, &queueInfo,
                0, nullptr, static_cast<uint32_t>(enabledExtensions.size()), enabledExtensions.data(), nullptr};
            Check(vkCreateDevice(candidate, &deviceInfo, nullptr, &device), "create probe device");
            vkGetDeviceQueue(device, queueFamily, 0, &queue);
            cmdBarrier2 = reinterpret_cast<PFN_vkCmdPipelineBarrier2>(vkGetDeviceProcAddr(device, "vkCmdPipelineBarrier2"));
            createRenderPass2 = reinterpret_cast<PFN_vkCreateRenderPass2>(vkGetDeviceProcAddr(device, "vkCreateRenderPass2"));
            if (cmdBarrier2 == nullptr || createRenderPass2 == nullptr) throw std::runtime_error("required-vulkan-1.3-entrypoints-absent");
            if (tileMode) {
                beginTile = reinterpret_cast<PFN_vkCmdBeginPerTileExecutionQCOM>(vkGetDeviceProcAddr(device, "vkCmdBeginPerTileExecutionQCOM"));
                endTile = reinterpret_cast<PFN_vkCmdEndPerTileExecutionQCOM>(vkGetDeviceProcAddr(device, "vkCmdEndPerTileExecutionQCOM"));
                if (beginTile == nullptr || endTile == nullptr) throw std::runtime_error("enabled-tile-entrypoint-absent");
            }
            const VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr, 0, queueFamily};
            Check(vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool), "create command pool");
            const VkCommandBufferAllocateInfo commandInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr,
                commandPool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
            Check(vkAllocateCommandBuffers(device, &commandInfo, &command), "allocate command buffer");
            const VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0};
            Check(vkCreateFence(device, &fenceInfo, nullptr, &fence), "create fence");
            return true;
        }
        skip = "no-device-with-required-extension-features-format-and-queue";
        return false;
    }

    uint32_t MemoryType(uint32_t mask, VkMemoryPropertyFlags required) {
        VkPhysicalDeviceMemoryProperties properties{};
        vkGetPhysicalDeviceMemoryProperties(physical, &properties);
        for (uint32_t index = 0; index < properties.memoryTypeCount; ++index) {
            const auto& type = properties.memoryTypes[index];
            if ((mask & (1U << index)) != 0U && (type.propertyFlags & required) == required &&
                (type.propertyFlags & VK_MEMORY_PROPERTY_PROTECTED_BIT) == 0U &&
                (properties.memoryHeaps[type.heapIndex].flags & VK_MEMORY_HEAP_TILE_MEMORY_BIT_QCOM) == 0U) return index;
        }
        throw std::runtime_error("ordinary-memory-type-unavailable");
    }

    Buffer MakeBuffer(VkDeviceSize size, VkBufferUsageFlags usage) {
        buffers.emplace_back();
        auto& result = buffers.back();
        const VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, size, usage,
            VK_SHARING_MODE_EXCLUSIVE, 0, nullptr};
        Check(vkCreateBuffer(device, &info, nullptr, &result.handle), "create buffer");
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device, result.handle, &requirements);
        const VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, requirements.size,
            MemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)};
        Check(vkAllocateMemory(device, &allocation, nullptr, &result.memory), "allocate buffer");
        Check(vkBindBufferMemory(device, result.handle, result.memory, 0), "bind buffer");
        return result;
    }

    Image MakeImage(uint32_t width, uint32_t height, VkImageUsageFlags usage) {
        VkImageFormatProperties format{};
        Check(vkGetPhysicalDeviceImageFormatProperties(physical, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TYPE_2D,
            VK_IMAGE_TILING_OPTIMAL, usage, 0, &format), "query image format");
        images.emplace_back();
        auto& result = images.back();
        const VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, nullptr, 0, VK_IMAGE_TYPE_2D,
            VK_FORMAT_R8G8B8A8_UNORM, {width, height, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT,
            VK_IMAGE_TILING_OPTIMAL, usage, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr, VK_IMAGE_LAYOUT_UNDEFINED};
        Check(vkCreateImage(device, &info, nullptr, &result.handle), "create image");
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(device, result.handle, &requirements);
        const VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, requirements.size,
            MemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)};
        Check(vkAllocateMemory(device, &allocation, nullptr, &result.memory), "allocate image");
        Check(vkBindImageMemory(device, result.handle, result.memory, 0), "bind image");
        const VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, nullptr, 0, result.handle,
            VK_IMAGE_VIEW_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM, {}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        Check(vkCreateImageView(device, &viewInfo, nullptr, &result.view), "create image view");
        return result;
    }

    void MakePipeline(const std::string& shaderFile, bool box) {
        const std::array<VkDescriptorSetLayoutBinding, 2> bindings{{
            {0, box ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}}};
        const VkDescriptorSetLayoutCreateInfo descriptorInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            nullptr, 0, box ? 2U : 1U, bindings.data()};
        Check(vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &descriptorLayout), "create descriptor layout");
        const std::array<VkDescriptorPoolSize, 2> sizes{{{bindings[0].descriptorType, 1}, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1}}};
        const VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, nullptr, 0,
            1, box ? 2U : 1U, sizes.data()};
        Check(vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool), "create descriptor pool");
        const VkDescriptorSetAllocateInfo allocateInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            nullptr, descriptorPool, 1, &descriptorLayout};
        Check(vkAllocateDescriptorSets(device, &allocateInfo, &descriptorSet), "allocate descriptors");
        const VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, nullptr, 0,
            1, &descriptorLayout, 0, nullptr};
        Check(vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout), "create pipeline layout");
        std::vector<uint32_t> words;
#ifdef SOLARLAB_EMBED_QCOM_SHADERS
        const unsigned char* embedded = nullptr;
        size_t bytes = 0;
        if (shaderFile.ends_with("/invert_control.comp.spv")) { embedded = qcom_invert_control; bytes = sizeof(qcom_invert_control); }
        else if (shaderFile.ends_with("/tile_invert.comp.spv")) { embedded = qcom_tile_invert; bytes = sizeof(qcom_tile_invert); }
        else if (shaderFile.ends_with("/box_control.comp.spv")) { embedded = qcom_box_control; bytes = sizeof(qcom_box_control); }
        else if (shaderFile.ends_with("/box_filter.comp.spv")) { embedded = qcom_box_filter; bytes = sizeof(qcom_box_filter); }
        if (embedded == nullptr || bytes == 0 || bytes % 4 != 0) throw std::runtime_error("embedded-probe-shader-unavailable");
        words.resize(bytes/4);
        // These static byte arrays are sized with sizeof above. Decode exactly
        // four in-bounds bytes per destination word, without pointer casts or
        // an unchecked byte-copy API, and preserve SPIR-V's little-endian form.
        for (size_t index = 0; index < words.size(); ++index) {
            const size_t offset = index * 4;
            words[index] = static_cast<uint32_t>(embedded[offset]) |
                (static_cast<uint32_t>(embedded[offset + 1]) << 8U) |
                (static_cast<uint32_t>(embedded[offset + 2]) << 16U) |
                (static_cast<uint32_t>(embedded[offset + 3]) << 24U);
        }
#else
        std::ifstream input(shaderFile, std::ios::binary | std::ios::ate);
        if (!input) throw std::runtime_error("shader-file-unavailable: " + shaderFile);
        const auto bytes = input.tellg();
        if (bytes <= 0 || bytes % 4 != 0 || bytes > 1024 * 1024) throw std::runtime_error("invalid-shader-length");
        words.resize(static_cast<size_t>(bytes) / 4);
        input.seekg(0);
        if (!input.read(reinterpret_cast<char*>(words.data()), bytes)) throw std::runtime_error("shader-read-failed");
#endif
        if (words.front() != 0x07230203U) throw std::runtime_error("invalid-spirv-magic");
        const VkShaderModuleCreateInfo shaderInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, nullptr, 0,
            static_cast<size_t>(bytes), words.data()};
        Check(vkCreateShaderModule(device, &shaderInfo, nullptr, &shader), "create shader");
        const VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr,
            0, VK_SHADER_STAGE_COMPUTE_BIT, shader, "main", nullptr};
        const VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO, nullptr,
            0, stage, pipelineLayout, VK_NULL_HANDLE, -1};
        Check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline), "create pipeline");
    }

    void Begin() {
        const VkCommandBufferBeginInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr,
            VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, nullptr};
        Check(vkBeginCommandBuffer(command, &info), "begin command buffer");
    }

    void Submit() {
        Check(vkEndCommandBuffer(command), "end command buffer");
        const VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &command, 0, nullptr};
        const auto start = std::chrono::steady_clock::now();
        Check(vkQueueSubmit(queue, 1, &submit, fence), "submit probe");
        Check(vkWaitForFences(device, 1, &fence, VK_TRUE, 5000000000ULL), "wait probe fence");
        submissionNs = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count());
    }

    void ImageBarrier(VkImage image, VkImageLayout oldLayout, VkImageLayout newLayout,
        VkPipelineStageFlags2 sourceStage, VkAccessFlags2 sourceAccess,
        VkPipelineStageFlags2 destinationStage, VkAccessFlags2 destinationAccess) {
        const VkImageMemoryBarrier2 barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2, nullptr,
            sourceStage, sourceAccess, destinationStage, destinationAccess, oldLayout, newLayout,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        const VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO, nullptr, 0, 0, nullptr, 0, nullptr, 1, &barrier};
        cmdBarrier2(command, &dependency);
    }

    void HostBarrier(VkBuffer buffer, VkDeviceSize size, VkPipelineStageFlags2 stage, VkAccessFlags2 access) {
        const VkBufferMemoryBarrier2 barrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2, nullptr,
            stage, access, VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_READ_BIT,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, buffer, 0, size};
        const VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO, nullptr, 0, 0, nullptr, 1, &barrier, 0, nullptr};
        cmdBarrier2(command, &dependency);
    }

    void RunInvert(bool tiled, const std::string& shaderDirectory);
    void RunBox(bool dedicated, const std::string& shaderDirectory);
};

void Probe::RunInvert(bool tiled, const std::string& shaderDirectory) {
    // Odd dimensions exercise the final partial workgroup and partial GPU tile.
    constexpr uint32_t width = 129, height = 73;
    constexpr VkDeviceSize bytes = width * height * 4U;
    const Image image = MakeImage(width, height, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    const Buffer readback = MakeBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    MakePipeline(shaderDirectory + (tiled ? "/tile_invert.comp.spv" : "/invert_control.comp.spv"), false);
    const VkDescriptorImageInfo imageInfo{VK_NULL_HANDLE, image.view, VK_IMAGE_LAYOUT_GENERAL};
    const VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, descriptorSet, 0, 0, 1,
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &imageInfo, nullptr, nullptr};
    vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
    const VkClearColorValue clear{{0.25f, 0.5f, 0.75f, 1.0f}};
    Begin();
    if (tiled) {
        const VkRenderPassTileShadingCreateInfoQCOM tileInfo{VK_STRUCTURE_TYPE_RENDER_PASS_TILE_SHADING_CREATE_INFO_QCOM,
            nullptr, VK_TILE_SHADING_RENDER_PASS_ENABLE_BIT_QCOM, {0, 0}};
        const VkAttachmentDescription2 attachment{VK_STRUCTURE_TYPE_ATTACHMENT_DESCRIPTION_2, nullptr, 0,
            VK_FORMAT_R8G8B8A8_UNORM, VK_SAMPLE_COUNT_1_BIT, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE,
            VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL};
        const VkAttachmentReference2 reference{VK_STRUCTURE_TYPE_ATTACHMENT_REFERENCE_2, nullptr,
            0, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT};
        const VkSubpassDescription2 subpass{VK_STRUCTURE_TYPE_SUBPASS_DESCRIPTION_2, nullptr, 0,
            VK_PIPELINE_BIND_POINT_GRAPHICS, 0, 0, nullptr, 1, &reference, nullptr, nullptr, 0, nullptr};
        const VkMemoryBarrier2 localBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2, nullptr,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_TILE_ATTACHMENT_READ_BIT_QCOM | VK_ACCESS_2_SHADER_TILE_ATTACHMENT_WRITE_BIT_QCOM};
        const VkSubpassDependency2 selfDependency{VK_STRUCTURE_TYPE_SUBPASS_DEPENDENCY_2, &localBarrier,
            0, 0, 0, 0, 0, 0, VK_DEPENDENCY_BY_REGION_BIT, 0};
        const VkRenderPassCreateInfo2 passInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO_2, &tileInfo, 0,
            1, &attachment, 1, &subpass, 1, &selfDependency, 0, nullptr};
        Check(createRenderPass2(device, &passInfo, nullptr, &renderPass), "create tile render pass");
        const VkFramebufferCreateInfo framebufferInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, nullptr, 0,
            renderPass, 1, &image.view, width, height, 1};
        Check(vkCreateFramebuffer(device, &framebufferInfo, nullptr, &framebuffer), "create tile framebuffer");
        const VkClearValue clearValue{clear};
        const VkRenderPassBeginInfo beginInfo{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, nullptr, renderPass,
            framebuffer, {{0, 0}, {width, height}}, 1, &clearValue};
        vkCmdBeginRenderPass(command, &beginInfo, VK_SUBPASS_CONTENTS_INLINE);
        const VkPerTileBeginInfoQCOM perTileBegin{VK_STRUCTURE_TYPE_PER_TILE_BEGIN_INFO_QCOM, nullptr};
        beginTile(command, &perTileBegin);
        const VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO, nullptr, VK_DEPENDENCY_BY_REGION_BIT,
            1, &localBarrier, 0, nullptr, 0, nullptr};
        cmdBarrier2(command, &dependency);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
        vkCmdDispatch(command, 1, 1, 1); // Repeated once per tile by the enabled execution model.
        const VkPerTileEndInfoQCOM perTileEnd{VK_STRUCTURE_TYPE_PER_TILE_END_INFO_QCOM, nullptr};
        endTile(command, &perTileEnd);
        vkCmdEndRenderPass(command);
        ImageBarrier(image.handle, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_ACCESS_2_SHADER_TILE_ATTACHMENT_WRITE_BIT_QCOM | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
    } else {
        ImageBarrier(image.handle, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
        const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdClearColorImage(command, image.handle, VK_IMAGE_LAYOUT_GENERAL, &clear, 1, &range);
        ImageBarrier(image.handle, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
        vkCmdDispatch(command, (width + 7U) / 8U, (height + 7U) / 8U, 1);
        ImageBarrier(image.handle, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
    }
    const VkBufferImageCopy copy{0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {width, height, 1}};
    vkCmdCopyImageToBuffer(command, image.handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback.handle, 1, &copy);
    HostBarrier(readback.handle, bytes, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
    Submit();
    void* mapped = nullptr;
    Check(vkMapMemory(device, readback.memory, 0, bytes, 0, &mapped), "map invert readback");
    const auto* actual = static_cast<const uint8_t*>(mapped);
    checksum = Checksum(actual,bytes);
    const std::array<int, 4> expected{191, 127, 64, 255};
    for (size_t index = 0; index < bytes; ++index) {
        maximumError = std::max(maximumError, static_cast<double>(std::abs(static_cast<int>(actual[index]) - expected[index % 4])));
    }
    vkUnmapMemory(device, readback.memory);
    if (maximumError > 1.0) throw std::runtime_error("invert-cpu-oracle-mismatch");
}

void Probe::RunBox(bool dedicated, const std::string& shaderDirectory) {
    constexpr uint32_t width = 64, height = 64;
    constexpr VkDeviceSize sourceBytes = width * height * 4U;
    constexpr VkDeviceSize outputBytes = 16U * 16U * 4U * sizeof(float);
    const Image source = MakeImage(width, height, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    const Buffer upload = MakeBuffer(sourceBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    const Buffer output = MakeBuffer(outputBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    const auto pixels = solarlab::probes::BoxPixels();
    solarlab::probes::ValidateBoxFixture(pixels);
    void* mapped = nullptr;
    Check(vkMapMemory(device, upload.memory, 0, sourceBytes, 0, &mapped), "map image upload");
    std::memcpy(mapped, pixels.data(), sourceBytes);
    vkUnmapMemory(device, upload.memory);
    const VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, nullptr,
        dedicated ? VkSamplerCreateFlags{VK_SAMPLER_CREATE_IMAGE_PROCESSING_BIT_QCOM} : 0U,
        VK_FILTER_NEAREST, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST,
        VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        0.0f, VK_FALSE, 1.0f, VK_FALSE, VK_COMPARE_OP_ALWAYS, 0.0f, 0.0f, VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK, VK_FALSE};
    Check(vkCreateSampler(device, &samplerInfo, nullptr, &sampler), "create image-processing sampler");
    MakePipeline(shaderDirectory + (dedicated ? "/box_filter.comp.spv" : "/box_control.comp.spv"), true);
    const VkDescriptorImageInfo imageInfo{sampler, source.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    const VkDescriptorBufferInfo bufferInfo{output.handle, 0, outputBytes};
    const std::array<VkWriteDescriptorSet, 2> writes{{
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, descriptorSet, 0, 0, 1,
            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imageInfo, nullptr, nullptr},
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, descriptorSet, 1, 0, 1,
            VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nullptr, &bufferInfo, nullptr}}};
    vkUpdateDescriptorSets(device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    Begin();
    ImageBarrier(source.handle, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
    const VkBufferImageCopy copy{0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {width, height, 1}};
    vkCmdCopyBufferToImage(command, upload.handle, source.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    ImageBarrier(source.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    vkCmdDispatch(command, 2, 2, 1);
    HostBarrier(output.handle, outputBytes, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    Submit();
    Check(vkMapMemory(device, output.memory, 0, outputBytes, 0, &mapped), "map box readback");
    const auto* actual = static_cast<const float*>(mapped);
    checksum = Checksum(actual,outputBytes);
    bool finite = true;
    for (uint32_t y = 0; y < 16U; ++y) {
        for (uint32_t x = 0; x < 16U; ++x) {
            for (uint32_t channel = 0; channel < 4U; ++channel) {
                double expected = 0.0;
                for (uint32_t dy = 0; dy < 4U; ++dy) {
                    for (uint32_t dx = 0; dx < 4U; ++dx) {
                        expected += pixels[((y * 4U + dy) * width + x * 4U + dx) * 4U + channel] / (255.0 * 16.0);
                    }
                }
                const float value = actual[(y * 16U + x) * 4U + channel];
                finite = finite && std::isfinite(value);
                maximumError = std::max(maximumError, std::abs(static_cast<double>(value) - expected));
            }
        }
    }
    vkUnmapMemory(device, output.memory);
    // One normalised 8-bit step allows texture-unit rounding, but still rejects
    // a wrong centre, nearest-only sample or missing 4x4 averaging operation.
    if (!finite || maximumError > (1.0 / 255.0)) throw std::runtime_error("box-filter-cpu-oracle-mismatch");
}

} // namespace

solarlab::probes::QcomProbeResult solarlab::probes::RunQcomProbe(const std::string& mode,
    const std::string& shaderDirectory, bool requireValidation) {
    std::lock_guard<std::mutex> lock(probeMutex);
    const auto started = std::chrono::steady_clock::now();
    validationErrors = 0;
    QcomProbeResult result;
    if (mode != "control" && mode != "box-control" && mode != "tile-shading" && mode != "image-processing") {
        result.reason = "unknown-probe-mode";
        return result;
    }
    {
        Probe probe;
        try {
            if (!probe.Initialize(mode, requireValidation)) {
                result.status = "SKIP";
                result.reason = probe.skip;
            } else {
                if (mode == "control" || mode == "tile-shading") probe.RunInvert(mode == "tile-shading", shaderDirectory);
                else probe.RunBox(mode == "image-processing", shaderDirectory);
                result.status = "PASS";
                result.reason = "submitted-and-cpu-oracle-matched";
            }
        } catch (const std::exception& error) { result.reason = error.what(); }
        result.device = probe.name;
        result.driverVersion = probe.driverVersion;
        result.validation = probe.validation;
        result.maximumError = probe.maximumError;
        result.submissionNs = probe.submissionNs;
        result.checksum = probe.checksum;
    } // Complete destruction before assessing validation errors and printing.
    result.validationErrors = validationErrors.load();
    if (result.validationErrors != 0) { result.status = "FAIL"; result.reason = "validation-errors"; }
    result.totalNs = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-started).count());
    return result;
}

std::string solarlab::probes::QcomProbeResult::Json(const std::string& mode) const {
    std::ostringstream out;
    out << "{\"schema\":\"solarlab.vulkan-probe.v1\",\"mode\":" << JsonString(mode)
        << ",\"status\":" << JsonString(status) << ",\"reason\":" << JsonString(reason)
        << ",\"device\":" << JsonString(device) << ",\"driverVersion\":" << driverVersion
        << ",\"validationLayer\":" << (validation ? "true" : "false")
        << ",\"validationErrors\":" << validationErrors
        << ",\"submission_ns\":" << submissionNs << ",\"total_ns\":" << totalNs
        << ",\"checksum_fnv1a64\":" << JsonString(std::to_string(checksum))
        << ",\"maxAbsoluteError\":" << maximumError << "}";
    return out.str();
}

#ifndef SOLARLAB_QCOM_PROBE_LIBRARY
int main(int argc, char** argv) {
    std::string mode = "control", shaderDirectory = ".";
    bool requireValidation = false;
    int index = 1;
    while (index < argc) {
        const std::string argument = argv[index++];
        if (argument == "--mode" && index < argc) mode = argv[index++];
        else if (argument == "--shaders" && index < argc) shaderDirectory = argv[index++];
        else if (argument == "--require-validation") requireValidation = true;
        else { std::cerr << "Usage: --mode control|box-control|tile-shading|image-processing --shaders DIR [--require-validation]\n"; return 2; }
    }
    const auto result = solarlab::probes::RunQcomProbe(mode, shaderDirectory, requireValidation);
    std::cout << result.Json(mode) << '\n';
    return result.status == "FAIL" ? 1 : 0;
}
#endif
