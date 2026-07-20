#include "SolarLabVulkanRenderer.h"

#include <android/asset_manager.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr const char* kLogTag = "SolarLabVulkan";
constexpr float kNearTracerAlpha = 0.58f;
constexpr float kMediumTracerAlpha = 0.42f;
constexpr float kFarTracerAlpha = 0.26f;
constexpr float kMediumTracerPointSizePx = 3.0f;
constexpr float kFarTracerPointSizePx = 1.60f;
constexpr float kTrailAlpha = 0.90f;
constexpr float kDefaultMaxPointSizePx = 64.0f;
constexpr uint32_t kComputeLocalSizeX = 64U;
constexpr uint32_t kFarTileSizePx = 16U;
constexpr uint32_t kFarTileBinCapacity = 8U;
constexpr uint32_t kBodyKindStar = 0U;
constexpr uint32_t kBodyKindPlanet = 1U;
constexpr uint32_t kBodyKindDwarfPlanet = 2U;
constexpr uint32_t kBodyKindProbe = 5U;
constexpr uint32_t kBodyKindTestObject = 6U;

VkDrawIndirectCommand MakeInitialIndirectCommand() {
    return VkDrawIndirectCommand{
        .vertexCount = 0,
        .instanceCount = 1,
        .firstVertex = 0,
        .firstInstance = 0,
    };
}

uint32_t RoundUpWorkgroups(uint32_t itemCount, uint32_t localSize) {
    if (itemCount == 0U || localSize == 0U) {
        return 0U;
    }
    return (itemCount + localSize - 1U) / localSize;
}

void LogInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message.c_str());
}

void LogError(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
}

template <typename T>
size_t ByteSize(const std::vector<T>& values) {
    return values.size() * sizeof(T);
}

uint32_t ApplyAlphaToArgb(uint32_t argb, float alphaScale) {
    const uint32_t baseAlpha = ((argb >> 24U) & 0xFFU) == 0U ? 0xFFU : ((argb >> 24U) & 0xFFU);
    const uint32_t scaledAlpha = static_cast<uint32_t>(std::clamp(std::lround(baseAlpha * alphaScale), 0l, 255l));
    return (argb & 0x00FFFFFFU) | (scaledAlpha << 24U);
}

uint32_t SafeCount3(size_t positionsCount, size_t peerCountA, size_t peerCountB, size_t peerCountC) {
    return static_cast<uint32_t>(std::min({positionsCount / 3U, peerCountA, peerCountB, peerCountC}));
}

uint32_t SafeCount3(size_t positionsCount, size_t peerCountA, size_t peerCountB, size_t peerCountC, size_t peerCountD) {
    return static_cast<uint32_t>(std::min({positionsCount / 3U, peerCountA, peerCountB, peerCountC, peerCountD}));
}

uint32_t SafeCount3(size_t positionsCount, size_t peerCountA, size_t peerCountB) {
    return static_cast<uint32_t>(std::min({positionsCount / 3U, peerCountA, peerCountB}));
}

float KindMinimumBillboardDiameterPx(uint32_t kind) {
    switch (kind) {
        case kBodyKindStar:
            return 9.0f;
        case kBodyKindPlanet:
            return 6.4f;
        case kBodyKindDwarfPlanet:
            return 5.2f;
        case kBodyKindProbe:
        case kBodyKindTestObject:
            return 4.0f;
        default:
            return 3.8f;
    }
}

VkVertexInputBindingDescription MakeBindingDescription(
    uint32_t binding,
    uint32_t stride,
    VkVertexInputRate inputRate = VK_VERTEX_INPUT_RATE_VERTEX) {
    return VkVertexInputBindingDescription{
        .binding = binding,
        .stride = stride,
        .inputRate = inputRate,
    };
}

VkVertexInputAttributeDescription MakeAttributeDescription(uint32_t location, uint32_t binding, VkFormat format, uint32_t offset) {
    return VkVertexInputAttributeDescription{
        .location = location,
        .binding = binding,
        .format = format,
        .offset = offset,
    };
}

struct Float3 {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
};

Float3 MakeFloat3(float x, float y, float z) {
    return Float3{.x = x, .y = y, .z = z};
}

Float3 Add(Float3 a, Float3 b) {
    return MakeFloat3(a.x + b.x, a.y + b.y, a.z + b.z);
}

Float3 Subtract(Float3 a, Float3 b) {
    return MakeFloat3(a.x - b.x, a.y - b.y, a.z - b.z);
}

Float3 Scale(Float3 value, float scalar) {
    return MakeFloat3(value.x * scalar, value.y * scalar, value.z * scalar);
}

float Dot(Float3 a, Float3 b) {
    return (a.x * b.x) + (a.y * b.y) + (a.z * b.z);
}

Float3 Cross(Float3 a, Float3 b) {
    return MakeFloat3(
        (a.y * b.z) - (a.z * b.y),
        (a.z * b.x) - (a.x * b.z),
        (a.x * b.y) - (a.y * b.x));
}

float Length(Float3 value) {
    return std::sqrt(Dot(value, value));
}

Float3 Normalize(Float3 value, Float3 fallback) {
    const float magnitude = Length(value);
    if (magnitude <= 1.0e-6f) {
        return fallback;
    }
    return Scale(value, 1.0f / magnitude);
}

constexpr float kOrbitMinPitchRadians = 12.0f * 0.017453292519943295769f;
constexpr float kOrbitMaxPitchRadians = 88.0f * 0.017453292519943295769f;
constexpr float kDefaultDepthExtentFactor = 48.0f;

}  // namespace

SolarLabVulkanRenderer::SolarLabVulkanRenderer() {
    backendLabelCache_ = "Vulkan SPIR-V graphics pipelines pending initial scene upload";
    sceneSummaryCache_ = "Scene not uploaded.";
}

SolarLabVulkanRenderer::~SolarLabVulkanRenderer() {
    Cleanup();
}

bool SolarLabVulkanRenderer::IsRuntimeAvailable() {
    uint32_t instanceVersion = 0;
    const auto result = vkEnumerateInstanceVersion(&instanceVersion);
    return result == VK_SUCCESS;
}

void SolarLabVulkanRenderer::SetAssetManager(AAssetManager* assetManager) {
    std::scoped_lock lock(stateMutex_);
    assetManager_ = assetManager;
}

bool SolarLabVulkanRenderer::Initialize(JNIEnv* env, jobject surface, int width, int height) {
    std::scoped_lock lock(stateMutex_);
    Cleanup();

    if (assetManager_ == nullptr) {
        SetError("AAssetManager must be set before Vulkan initialisation so compiled SPIR-V shaders can be loaded.");
        return false;
    }

    // --- Phase 1: Core Instance & Surface ---
    if (!CreateInstance()) {
        return false;
    }
    if (!CreateSurface(env, surface)) {
        return false;
    }

    // --- Phase 2: Device Selection & Logical Device ---
    if (!PickPhysicalDevice()) {
        return false;
    }
    if (!CreateDevice()) {
        return false;
    }
    if (!CreatePipelineCache()) {
        return false;
    }

    // --- Phase 3: Global Resources (Descriptors & Compute) ---
    if (!CreateDescriptorResources()) {
        return false;
    }
    if (!CreateComputePipelines()) {
        return false;
    }

    // --- Phase 4: Swapchain & Render Target ---
    if (!CreateSwapchain(width, height)) {
        return false;
    }
    if (!CreateRenderPass()) {
        return false;
    }
    if (!CreateDepthResources()) {
        return false;
    }
    if (!CreateFramebuffers()) {
        return false;
    }

    // --- Phase 5: Graphics Pipelines & Command Infrastructure ---
    if (!CreateGraphicsPipelines()) {
        return false;
    }
    if (!CreateCommandPool()) {
        return false;
    }
    if (!AllocateAndRecordCommandBuffers()) {
        return false;
    }

    // --- Phase 6: Synchronisation ---
    if (!CreateSyncObjects()) {
        return false;
    }

    backendLabelCache_ = std::string("Vulkan SPIR-V graphics pipelines") + (computeCompactionEnabled_ ? " + compute compaction" : " + direct medium/far draws");
    sceneSummaryCache_ = BuildSceneSummaryLocked();
    LogInfo("Vulkan renderer initialised with SPIR-V graphics pipelines" + std::string(computeCompactionEnabled_ ? " and compute compaction." : " without compute compaction."));
    return true;
}

bool SolarLabVulkanRenderer::Resize(JNIEnv* env, jobject surface, int width, int height) {
    std::scoped_lock lock(stateMutex_);
    if (instance_ == VK_NULL_HANDLE) {
        return Initialize(env, surface, width, height);
    }

    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
    }

    DestroySurfaceResources();

    if (!CreateSurface(env, surface)) {
        return false;
    }
    if (!CreateSwapchain(width, height)) {
        return false;
    }
    if (!CreateRenderPass()) {
        return false;
    }
    if (!CreateDepthResources()) {
        return false;
    }
    if (!CreateFramebuffers()) {
        return false;
    }
    if (!CreateGraphicsPipelines()) {
        return false;
    }
    if (computeCompactionEnabled_) {
        sceneGpuStreams_.uploadedRevision = -1;
        if (!EnsureSceneGpuStreamsLocked()) {
            return false;
        }
    }
    if (!AllocateAndRecordCommandBuffers()) {
        return false;
    }
    backendLabelCache_ = std::string("Vulkan SPIR-V graphics pipelines") + (computeCompactionEnabled_ ? " + compute compaction" : " + direct medium/far draws");
    sceneSummaryCache_ = BuildSceneSummaryLocked();
    return true;
}

void SolarLabVulkanRenderer::DestroySurface() {
    std::scoped_lock lock(stateMutex_);
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
    }
    DestroySurfaceResources();
    if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
    }
    if (nativeWindow_ != nullptr) {
        ANativeWindow_release(nativeWindow_);
        nativeWindow_ = nullptr;
    }
}

void SolarLabVulkanRenderer::SubmitScene(
    int64_t sourceRevision,
    double sceneOriginX,
    double sceneOriginY,
    double sceneOriginZ,
    std::vector<double> authoritativePositionsM,
    std::vector<double> authoritativeSourceMassesKg,
    std::vector<float> authoritativeRadiiM,
    std::vector<int32_t> authoritativeColorsArgb,
    std::vector<int32_t> authoritativeKinds,
    std::vector<double> tracerNearPositionsM,
    std::vector<float> tracerNearRadiiM,
    std::vector<int32_t> tracerNearColorsArgb,
    std::vector<int32_t> tracerNearKinds,
    std::vector<double> tracerMediumPositionsM,
    std::vector<double> tracerMediumVelocitiesMps,
    std::vector<int32_t> tracerMediumStableIds,
    std::vector<float> tracerMediumRadiiM,
    std::vector<int32_t> tracerMediumColorsArgb,
    std::vector<int32_t> tracerMediumKinds,
    std::vector<double> tracerFarPositionsM,
    std::vector<double> tracerFarVelocitiesMps,
    std::vector<int32_t> tracerFarStableIds,
    std::vector<float> tracerFarRadiiM,
    std::vector<int32_t> tracerFarColorsArgb,
    std::vector<int32_t> tracerFarKinds,
    std::vector<double> trailPositionsM,
    std::vector<int32_t> trailColorsArgb,
    std::vector<int32_t> trailVertexCounts) {
    std::scoped_lock lock(stateMutex_);
    sceneBuffers_.sourceRevision = sourceRevision;
    sceneBuffers_.sceneOriginX = sceneOriginX;
    sceneBuffers_.sceneOriginY = sceneOriginY;
    sceneBuffers_.sceneOriginZ = sceneOriginZ;
    sceneBuffers_.authoritativePositionsM = std::move(authoritativePositionsM);
    sceneBuffers_.authoritativeSourceMassesKg = std::move(authoritativeSourceMassesKg);
    sceneBuffers_.authoritativeRadiiM = std::move(authoritativeRadiiM);
    sceneBuffers_.authoritativeColorsArgb = std::move(authoritativeColorsArgb);
    sceneBuffers_.authoritativeKinds = std::move(authoritativeKinds);
    sceneBuffers_.tracerNearPositionsM = std::move(tracerNearPositionsM);
    sceneBuffers_.tracerNearRadiiM = std::move(tracerNearRadiiM);
    sceneBuffers_.tracerNearColorsArgb = std::move(tracerNearColorsArgb);
    sceneBuffers_.tracerNearKinds = std::move(tracerNearKinds);
    sceneBuffers_.tracerMediumPositionsM = std::move(tracerMediumPositionsM);
    sceneBuffers_.tracerMediumVelocitiesMps = std::move(tracerMediumVelocitiesMps);
    sceneBuffers_.tracerMediumStableIds = std::move(tracerMediumStableIds);
    sceneBuffers_.tracerMediumRadiiM = std::move(tracerMediumRadiiM);
    sceneBuffers_.tracerMediumColorsArgb = std::move(tracerMediumColorsArgb);
    sceneBuffers_.tracerMediumKinds = std::move(tracerMediumKinds);
    sceneBuffers_.tracerFarPositionsM = std::move(tracerFarPositionsM);
    sceneBuffers_.tracerFarVelocitiesMps = std::move(tracerFarVelocitiesMps);
    sceneBuffers_.tracerFarStableIds = std::move(tracerFarStableIds);
    sceneBuffers_.tracerFarRadiiM = std::move(tracerFarRadiiM);
    sceneBuffers_.tracerFarColorsArgb = std::move(tracerFarColorsArgb);
    sceneBuffers_.tracerFarKinds = std::move(tracerFarKinds);
    sceneBuffers_.trailPositionsM = std::move(trailPositionsM);
    sceneBuffers_.trailColorsArgb = std::move(trailColorsArgb);
    sceneBuffers_.trailVertexCounts = std::move(trailVertexCounts);

    if (sceneGpuStreams_.uploadedRevision != sourceRevision) {
        backendLabelCache_ = "Vulkan SPIR-V graphics pipelines pending scene upload";
        sceneSummaryCache_ = BuildSceneSummaryLocked();
        commandBuffersRevision_ = -1;
    }
}

void SolarLabVulkanRenderer::SetCamera(double centerX, double centerY, double centerZ, double viewRadiusM, double yawRadians, double pitchRadians) {
    std::scoped_lock lock(stateMutex_);
    cameraCenterX_ = centerX;
    cameraCenterY_ = centerY;
    cameraCenterZ_ = centerZ;
    cameraViewRadiusM_ = viewRadiusM;
    cameraYawRadians_ = yawRadians;
    cameraPitchRadians_ = pitchRadians;
}

bool SolarLabVulkanRenderer::Render() {
    std::scoped_lock lock(stateMutex_);
    if (device_ == VK_NULL_HANDLE || swapchain_ == VK_NULL_HANDLE || commandBuffers_.empty()) {
        SetError("Render requested before Vulkan swapchain initialisation completed.");
        return false;
    }

    const auto fenceResult = vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, std::numeric_limits<uint64_t>::max());
    if (fenceResult != VK_SUCCESS) {
        SetError("vkWaitForFences failed.");
        return false;
    }
    RefreshCompactionVisibleCountsFromReadbackLocked();
    vkResetFences(device_, 1, &inFlightFence_);

    if (!EnsureSceneGpuStreamsLocked()) {
        return false;
    }
    if (!UpdateSceneUniformBufferLocked()) {
        return false;
    }
    if (commandBuffersRevision_ != sceneGpuStreams_.uploadedRevision) {
        if (!AllocateAndRecordCommandBuffers()) {
            return false;
        }
    }

    uint32_t imageIndex = 0;
    const auto acquireResult = vkAcquireNextImageKHR(
        device_,
        swapchain_,
        std::numeric_limits<uint64_t>::max(),
        imageAvailableSemaphore_,
        VK_NULL_HANDLE,
        &imageIndex);
    if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
        SetError("Swapchain out of date; surface resize is required.");
        return false;
    }
    if (acquireResult != VK_SUCCESS && acquireResult != VK_SUBOPTIMAL_KHR) {
        SetError("vkAcquireNextImageKHR failed.");
        return false;
    }

    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    const VkSubmitInfo submitInfo{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .pNext = nullptr,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &imageAvailableSemaphore_,
        .pWaitDstStageMask = waitStages,
        .commandBufferCount = 1,
        .pCommandBuffers = &commandBuffers_[imageIndex],
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &renderFinishedSemaphore_,
    };

    const auto submitResult = vkQueueSubmit(graphicsQueue_, 1, &submitInfo, inFlightFence_);
    if (submitResult != VK_SUCCESS) {
        SetError("vkQueueSubmit failed.");
        return false;
    }

    const VkPresentInfoKHR presentInfo{
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .pNext = nullptr,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &renderFinishedSemaphore_,
        .swapchainCount = 1,
        .pSwapchains = &swapchain_,
        .pImageIndices = &imageIndex,
        .pResults = nullptr,
    };

    const auto presentResult = vkQueuePresentKHR(presentQueue_, &presentInfo);
    if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
        SetError("Swapchain present reported it is out of date.");
        return false;
    }
    if (presentResult != VK_SUCCESS) {
        SetError("vkQueuePresentKHR failed.");
        return false;
    }

    lastError_.clear();
    return true;
}

std::string SolarLabVulkanRenderer::LastError() const {
    std::scoped_lock lock(stateMutex_);
    return lastError_;
}

std::string SolarLabVulkanRenderer::BackendLabel() const {
    std::scoped_lock lock(stateMutex_);
    return backendLabelCache_;
}

std::string SolarLabVulkanRenderer::SceneSummary() const {
    std::scoped_lock lock(stateMutex_);
    return sceneSummaryCache_;
}

std::string SolarLabVulkanRenderer::HardwareSummary() const {
    std::scoped_lock lock(stateMutex_);

    if (physicalDevice_ == VK_NULL_HANDLE) {
        return "gpu=vulkan-device-unavailable";
    }

    const uint32_t apiVersion = physicalDeviceProperties_.apiVersion;
    std::ostringstream out;
    out << "gpu=" << physicalDeviceProperties_.deviceName
        << " vk="
        << VK_API_VERSION_MAJOR(apiVersion) << '.'
        << VK_API_VERSION_MINOR(apiVersion) << '.'
        << VK_API_VERSION_PATCH(apiVersion)
        << " largePoints=" << (supportedFeatures_.largePoints ? "yes" : "no")
        << " queueCompute=" << (graphicsQueueSupportsCompute_ ? "yes" : "no")
        << " compaction=" << (computeCompactionEnabled_ ? "on" : "off");
    return out.str();
}

bool SolarLabVulkanRenderer::CreateInstance() {
    const std::array<const char*, 2> instanceExtensions = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };

    const VkApplicationInfo applicationInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pNext = nullptr,
        .pApplicationName = "SolarLab",
        .applicationVersion = VK_MAKE_VERSION(0, 8, 0),
        .pEngineName = "SolarLab",
        .engineVersion = VK_MAKE_VERSION(0, 8, 0),
        .apiVersion = VK_API_VERSION_1_1,
    };

    const VkInstanceCreateInfo instanceCreateInfo{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .pApplicationInfo = &applicationInfo,
        .enabledLayerCount = 0,
        .ppEnabledLayerNames = nullptr,
        .enabledExtensionCount = static_cast<uint32_t>(instanceExtensions.size()),
        .ppEnabledExtensionNames = instanceExtensions.data(),
    };

    if (vkCreateInstance(&instanceCreateInfo, nullptr, &instance_) != VK_SUCCESS) {
        SetError("vkCreateInstance failed.");
        return false;
    }
    return true;
}

bool SolarLabVulkanRenderer::CreateSurface(JNIEnv* env, jobject surface) {
    if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
    }
    if (nativeWindow_ != nullptr) {
        ANativeWindow_release(nativeWindow_);
        nativeWindow_ = nullptr;
    }

    nativeWindow_ = ANativeWindow_fromSurface(env, surface);
    if (nativeWindow_ == nullptr) {
        SetError("ANativeWindow_fromSurface returned null.");
        return false;
    }

    const VkAndroidSurfaceCreateInfoKHR createInfo{
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .pNext = nullptr,
        .flags = 0,
        .window = nativeWindow_,
    };

    if (vkCreateAndroidSurfaceKHR(instance_, &createInfo, nullptr, &surface_) != VK_SUCCESS) {
        SetError("vkCreateAndroidSurfaceKHR failed.");
        return false;
    }
    return true;
}

bool SolarLabVulkanRenderer::PickPhysicalDevice() {
    uint32_t deviceCount = 0;
    if (vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr) != VK_SUCCESS || deviceCount == 0) {
        SetError("No Vulkan physical devices were found.");
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data());

    VkPhysicalDevice fallbackDevice = VK_NULL_HANDLE;
    uint32_t fallbackQueueIndex = UINT32_MAX;

    for (const auto& candidate : devices) {
        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, nullptr);
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, queueFamilies.data());

        for (uint32_t queueIndex = 0; queueIndex != queueFamilyCount; ++queueIndex) {
            VkBool32 supportsPresent = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(candidate, queueIndex, surface_, &supportsPresent);
            const bool supportsGraphics = (queueFamilies[queueIndex].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0;
            const bool supportsCompute = (queueFamilies[queueIndex].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0;
            if (!supportsGraphics || supportsPresent != VK_TRUE) {
                continue;
            }
            if (supportsCompute) {
                physicalDevice_ = candidate;
                graphicsQueueFamilyIndex_ = queueIndex;
                presentQueueFamilyIndex_ = queueIndex;
                graphicsQueueSupportsCompute_ = true;
                vkGetPhysicalDeviceFeatures(candidate, &supportedFeatures_);
                vkGetPhysicalDeviceProperties(candidate, &physicalDeviceProperties_);
                enabledFeatures_ = {};
                enabledFeatures_.largePoints = supportedFeatures_.largePoints;
                return true;
            }
            if (fallbackDevice == VK_NULL_HANDLE) {
                fallbackDevice = candidate;
                fallbackQueueIndex = queueIndex;
            }
        }
    }

    if (fallbackDevice != VK_NULL_HANDLE) {
        physicalDevice_ = fallbackDevice;
        graphicsQueueFamilyIndex_ = fallbackQueueIndex;
        presentQueueFamilyIndex_ = fallbackQueueIndex;
        graphicsQueueSupportsCompute_ = false;
        vkGetPhysicalDeviceFeatures(fallbackDevice, &supportedFeatures_);
        vkGetPhysicalDeviceProperties(fallbackDevice, &physicalDeviceProperties_);
        enabledFeatures_ = {};
        enabledFeatures_.largePoints = supportedFeatures_.largePoints;
        return true;
    }

    SetError("No Vulkan queue family with graphics + present support was found.");
    return false;
}

bool SolarLabVulkanRenderer::CreateDevice() {
    const float queuePriority = 1.0f;
    const VkDeviceQueueCreateInfo queueCreateInfo{
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .queueFamilyIndex = graphicsQueueFamilyIndex_,
        .queueCount = 1,
        .pQueuePriorities = &queuePriority,
    };

    const std::array<const char*, 1> deviceExtensions = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    const VkDeviceCreateInfo createInfo{
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queueCreateInfo,
        .enabledLayerCount = 0,
        .ppEnabledLayerNames = nullptr,
        .enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size()),
        .ppEnabledExtensionNames = deviceExtensions.data(),
        .pEnabledFeatures = &enabledFeatures_,
    };

    if (vkCreateDevice(physicalDevice_, &createInfo, nullptr, &device_) != VK_SUCCESS) {
        SetError("vkCreateDevice failed.");
        return false;
    }

    vkGetDeviceQueue(device_, graphicsQueueFamilyIndex_, 0, &graphicsQueue_);
    vkGetDeviceQueue(device_, presentQueueFamilyIndex_, 0, &presentQueue_);
    return true;
}

bool SolarLabVulkanRenderer::CreatePipelineCache() {
    if (device_ == VK_NULL_HANDLE) {
        SetError("Cannot create a pipeline cache before the Vulkan device exists.");
        return false;
    }
    if (pipelineCache_ != VK_NULL_HANDLE) {
        return true;
    }

    const VkPipelineCacheCreateInfo createInfo{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .initialDataSize = 0,
        .pInitialData = nullptr,
    };
    if (vkCreatePipelineCache(device_, &createInfo, nullptr, &pipelineCache_) != VK_SUCCESS) {
        SetError("vkCreatePipelineCache failed.");
        return false;
    }
    return true;
}

bool SolarLabVulkanRenderer::CreateSwapchain(int width, int height) {
    VkSurfaceCapabilitiesKHR capabilities{};
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &capabilities) != VK_SUCCESS) {
        SetError("vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed.");
        return false;
    }

    uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data());
    if (formats.empty()) {
        SetError("Surface reported no Vulkan formats.");
        return false;
    }

    uint32_t presentModeCount = 0;
    vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice_, surface_, &presentModeCount, nullptr);
    std::vector<VkPresentModeKHR> presentModes(presentModeCount);
    vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice_, surface_, &presentModeCount, presentModes.data());
    if (presentModes.empty()) {
        SetError("Surface reported no Vulkan present modes.");
        return false;
    }

    const auto chosenFormat = ChooseSurfaceFormat(formats);
    const auto presentMode = ChoosePresentMode(presentModes);
    const auto extent = ChooseExtent(capabilities, width, height);

    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0 && imageCount > capabilities.maxImageCount) {
        imageCount = capabilities.maxImageCount;
    }

    const VkSwapchainCreateInfoKHR createInfo{
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .pNext = nullptr,
        .flags = 0,
        .surface = surface_,
        .minImageCount = imageCount,
        .imageFormat = chosenFormat.format,
        .imageColorSpace = chosenFormat.colorSpace,
        .imageExtent = extent,
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
        .preTransform = capabilities.currentTransform,
        .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        .presentMode = presentMode,
        .clipped = VK_TRUE,
        .oldSwapchain = VK_NULL_HANDLE,
    };

    if (vkCreateSwapchainKHR(device_, &createInfo, nullptr, &swapchain_) != VK_SUCCESS) {
        SetError("vkCreateSwapchainKHR failed.");
        return false;
    }

    swapchainImageFormat_ = chosenFormat.format;
    swapchainExtent_ = extent;

    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr);
    swapchainImages_.resize(imageCount);
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, swapchainImages_.data());

    swapchainImageViews_.resize(swapchainImages_.size());
    for (size_t index = 0; index < swapchainImages_.size(); ++index) {
        const VkImageViewCreateInfo imageViewCreateInfo{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .image = swapchainImages_[index],
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = swapchainImageFormat_,
            .components = {
                .r = VK_COMPONENT_SWIZZLE_IDENTITY,
                .g = VK_COMPONENT_SWIZZLE_IDENTITY,
                .b = VK_COMPONENT_SWIZZLE_IDENTITY,
                .a = VK_COMPONENT_SWIZZLE_IDENTITY,
            },
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = 1,
            },
        };
        if (vkCreateImageView(device_, &imageViewCreateInfo, nullptr, &swapchainImageViews_[index]) != VK_SUCCESS) {
            SetError("vkCreateImageView failed.");
            return false;
        }
    }

    return true;
}

// Rebuilds the render pass around the active swapchain, including the depth attachment and subpass
// dependency used by both the scene and overlay pipelines.
bool SolarLabVulkanRenderer::CreateRenderPass() {
    if (physicalDevice_ == VK_NULL_HANDLE) {
        SetError("Cannot create a render pass before a physical device has been selected.");
        return false;
    }

    if (renderPass_ != VK_NULL_HANDLE) {
        vkDestroyRenderPass(device_, renderPass_, nullptr);
        renderPass_ = VK_NULL_HANDLE;
    }

    depthFormat_ = PickDepthFormat();
    if (depthFormat_ == VK_FORMAT_UNDEFINED) {
        SetError("Failed to find a supported depth attachment format.");
        return false;
    }

    // Color stays presentable after the pass; depth is transient and only needs a supported
    // attachment format for the current physical device.
    const VkAttachmentDescription colorAttachment{
        .flags = 0,
        .format = swapchainImageFormat_,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
    };
    const VkAttachmentDescription depthAttachment{
        .flags = 0,
        .format = depthFormat_,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
        .storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
    };

    const VkAttachmentReference colorAttachmentReference{
        .attachment = 0,
        .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
    };
    const VkAttachmentReference depthAttachmentReference{
        .attachment = 1,
        .layout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
    };

    const VkSubpassDescription subpassDescription{
        .flags = 0,
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
        .inputAttachmentCount = 0,
        .pInputAttachments = nullptr,
        .colorAttachmentCount = 1,
        .pColorAttachments = &colorAttachmentReference,
        .pResolveAttachments = nullptr,
        .pDepthStencilAttachment = &depthAttachmentReference,
        .preserveAttachmentCount = 0,
        .pPreserveAttachments = nullptr,
    };

    // External dependencies cover both color writes and early/late depth writes around the single
    // graphics subpass shared by the pipeline variants.
    const std::array<VkSubpassDependency, 2> dependencies = {{
        {
            .srcSubpass = VK_SUBPASS_EXTERNAL,
            .dstSubpass = 0,
            .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            .dependencyFlags = 0,
        },
        {
            .srcSubpass = 0,
            .dstSubpass = VK_SUBPASS_EXTERNAL,
            .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            .dstAccessMask = 0,
            .dependencyFlags = 0,
        },
    }};

    // Keep attachment order aligned with the references above: color at index 0, depth at 1.
    const std::array<VkAttachmentDescription, 2> attachments = {{colorAttachment, depthAttachment}};
    const VkRenderPassCreateInfo renderPassCreateInfo{
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .attachmentCount = static_cast<uint32_t>(attachments.size()),
        .pAttachments = attachments.data(),
        .subpassCount = 1,
        .pSubpasses = &subpassDescription,
        .dependencyCount = static_cast<uint32_t>(dependencies.size()),
        .pDependencies = dependencies.data(),
    };

    if (vkCreateRenderPass(device_, &renderPassCreateInfo, nullptr, &renderPass_) != VK_SUCCESS) {
        SetError("vkCreateRenderPass failed.");
        return false;
    }
    return true;
}

bool SolarLabVulkanRenderer::CreateFramebuffers() {
    if (depthImage_.view == VK_NULL_HANDLE) {
        SetError("Depth resources must exist before creating framebuffers.");
        return false;
    }
    framebuffers_.resize(swapchainImageViews_.size());
    for (size_t index = 0; index < swapchainImageViews_.size(); ++index) {
        std::array<VkImageView, 2> attachments = {swapchainImageViews_[index], depthImage_.view};
        const VkFramebufferCreateInfo framebufferCreateInfo{
            .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .renderPass = renderPass_,
            .attachmentCount = static_cast<uint32_t>(attachments.size()),
            .pAttachments = attachments.data(),
            .width = swapchainExtent_.width,
            .height = swapchainExtent_.height,
            .layers = 1,
        };
        if (vkCreateFramebuffer(device_, &framebufferCreateInfo, nullptr, &framebuffers_[index]) != VK_SUCCESS) {
            SetError("vkCreateFramebuffer failed.");
            return false;
        }
    }
    return true;
}

bool SolarLabVulkanRenderer::CreateDepthResources() {
    if (swapchainExtent_.width == 0 || swapchainExtent_.height == 0) {
        SetError("Cannot create depth resources before the swapchain extent is valid.");
        return false;
    }
    DestroyDepthResources();
    if (depthFormat_ == VK_FORMAT_UNDEFINED) {
        depthFormat_ = PickDepthFormat();
    }
    if (depthFormat_ == VK_FORMAT_UNDEFINED) {
        SetError("Failed to choose a supported depth format.");
        return false;
    }
    return CreateDepthImage(swapchainExtent_.width, swapchainExtent_.height, depthFormat_, "depth-image", depthImage_);
}

void SolarLabVulkanRenderer::DestroyDepthResources() {
    DestroyGpuImage(depthImage_);
    depthFormat_ = VK_FORMAT_UNDEFINED;
}

void SolarLabVulkanRenderer::DestroyGpuImage(GpuImage& image) {
    if (device_ == VK_NULL_HANDLE) {
        image = GpuImage{};
        return;
    }
    if (image.view != VK_NULL_HANDLE) {
        vkDestroyImageView(device_, image.view, nullptr);
    }
    if (image.image != VK_NULL_HANDLE) {
        vkDestroyImage(device_, image.image, nullptr);
    }
    if (image.memory != VK_NULL_HANDLE) {
        vkFreeMemory(device_, image.memory, nullptr);
    }
    image = GpuImage{};
}

VkFormat SolarLabVulkanRenderer::PickDepthFormat() const {
    if (physicalDevice_ == VK_NULL_HANDLE) {
        return VK_FORMAT_UNDEFINED;
    }
    const std::array<VkFormat, 3> candidates = {
        VK_FORMAT_D32_SFLOAT,
        VK_FORMAT_D32_SFLOAT_S8_UINT,
        VK_FORMAT_D24_UNORM_S8_UINT,
    };
    for (VkFormat candidate : candidates) {
        VkFormatProperties properties{};
        vkGetPhysicalDeviceFormatProperties(physicalDevice_, candidate, &properties);
        if ((properties.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
            return candidate;
        }
    }
    return VK_FORMAT_UNDEFINED;
}

bool SolarLabVulkanRenderer::CreateDepthImage(uint32_t width, uint32_t height, VkFormat format, const char* label, GpuImage& image) {
    if (device_ == VK_NULL_HANDLE || physicalDevice_ == VK_NULL_HANDLE) {
        SetError("Cannot create depth images before the Vulkan device is ready.");
        return false;
    }

    const VkImageCreateInfo imageCreateInfo{
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = {
            .width = width,
            .height = height,
            .depth = 1,
        },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (vkCreateImage(device_, &imageCreateInfo, nullptr, &image.image) != VK_SUCCESS) {
        SetError(std::string("vkCreateImage failed for ") + (label != nullptr ? label : "depth-image") + ".");
        return false;
    }

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(device_, image.image, &requirements);
    const uint32_t memoryTypeIndex = FindMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memoryTypeIndex == UINT32_MAX) {
        SetError(std::string("Failed to find device-local memory for ") + (label != nullptr ? label : "depth-image") + ".");
        DestroyGpuImage(image);
        return false;
    }

    const VkMemoryAllocateInfo allocateInfo{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = nullptr,
        .allocationSize = requirements.size,
        .memoryTypeIndex = memoryTypeIndex,
    };
    if (vkAllocateMemory(device_, &allocateInfo, nullptr, &image.memory) != VK_SUCCESS) {
        SetError(std::string("vkAllocateMemory failed for ") + (label != nullptr ? label : "depth-image") + ".");
        DestroyGpuImage(image);
        return false;
    }
    if (vkBindImageMemory(device_, image.image, image.memory, 0) != VK_SUCCESS) {
        SetError(std::string("vkBindImageMemory failed for ") + (label != nullptr ? label : "depth-image") + ".");
        DestroyGpuImage(image);
        return false;
    }

    const VkImageViewCreateInfo viewCreateInfo{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .image = image.image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .components = {
            .r = VK_COMPONENT_SWIZZLE_IDENTITY,
            .g = VK_COMPONENT_SWIZZLE_IDENTITY,
            .b = VK_COMPONENT_SWIZZLE_IDENTITY,
            .a = VK_COMPONENT_SWIZZLE_IDENTITY,
        },
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    if (vkCreateImageView(device_, &viewCreateInfo, nullptr, &image.view) != VK_SUCCESS) {
        SetError(std::string("vkCreateImageView failed for ") + (label != nullptr ? label : "depth-image") + ".");
        DestroyGpuImage(image);
        return false;
    }

    image.format = format;
    image.width = width;
    image.height = height;
    image.debugLabel = label;
    return true;
}

bool SolarLabVulkanRenderer::CreateCommandPool() {
    if (commandPool_ != VK_NULL_HANDLE) {
        return true;
    }

    const VkCommandPoolCreateInfo createInfo{
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = graphicsQueueFamilyIndex_,
    };

    if (vkCreateCommandPool(device_, &createInfo, nullptr, &commandPool_) != VK_SUCCESS) {
        SetError("vkCreateCommandPool failed.");
        return false;
    }
    return true;
}

// Allocates the descriptor layouts, pool, and uniform/scene buffers shared by graphics and compute
// passes so later pipeline setup can bind a stable descriptor surface.
bool SolarLabVulkanRenderer::CreateDescriptorResources() {
    if (device_ == VK_NULL_HANDLE) {
        SetError("Cannot create descriptor resources before the Vulkan device exists.");
        return false;
    }

    if (!EnsureHostVisibleBuffer(sizeof(SceneUniformData), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, "scene-uniform", sceneUniformBuffer_)) {
        return false;
    }

    // The graphics descriptor exposes one uniform buffer and is reused across swapchain rebuilds.
    if (sceneDescriptorSetLayout_ == VK_NULL_HANDLE) {
        const VkDescriptorSetLayoutBinding binding{
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_VERTEX_BIT,
            .pImmutableSamplers = nullptr,
        };
        const VkDescriptorSetLayoutCreateInfo layoutCreateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .bindingCount = 1,
            .pBindings = &binding,
        };
        if (vkCreateDescriptorSetLayout(device_, &layoutCreateInfo, nullptr, &sceneDescriptorSetLayout_) != VK_SUCCESS) {
            SetError("vkCreateDescriptorSetLayout failed.");
            return false;
        }
    }

    // A single descriptor set is enough for the scene uniform path; compute storage is configured
    // separately below when the queue family supports it.
    if (descriptorPool_ == VK_NULL_HANDLE) {
        const VkDescriptorPoolSize poolSize{
            .type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
            .descriptorCount = 1,
        };
        const VkDescriptorPoolCreateInfo poolCreateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .maxSets = 1,
            .poolSizeCount = 1,
            .pPoolSizes = &poolSize,
        };
        if (vkCreateDescriptorPool(device_, &poolCreateInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
            SetError("vkCreateDescriptorPool failed.");
            return false;
        }
    }

    if (sceneDescriptorSet_ == VK_NULL_HANDLE) {
        const VkDescriptorSetAllocateInfo allocateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .pNext = nullptr,
            .descriptorPool = descriptorPool_,
            .descriptorSetCount = 1,
            .pSetLayouts = &sceneDescriptorSetLayout_,
        };
        if (vkAllocateDescriptorSets(device_, &allocateInfo, &sceneDescriptorSet_) != VK_SUCCESS) {
            SetError("vkAllocateDescriptorSets failed.");
            return false;
        }
    }

    const VkDescriptorBufferInfo bufferInfo{
        .buffer = sceneUniformBuffer_.buffer,
        .offset = 0,
        .range = sizeof(SceneUniformData),
    };
    const VkWriteDescriptorSet write{
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .pNext = nullptr,
        .dstSet = sceneDescriptorSet_,
        .dstBinding = 0,
        .dstArrayElement = 0,
        .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
        .pImageInfo = nullptr,
        .pBufferInfo = &bufferInfo,
        .pTexelBufferView = nullptr,
    };
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);

    // The pipeline layout depends on descriptor shape, not descriptor contents, so it can survive
    // uniform-buffer updates.
    if (graphicsPipelineLayout_ == VK_NULL_HANDLE) {
        const VkPipelineLayoutCreateInfo layoutCreateInfo{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .setLayoutCount = 1,
            .pSetLayouts = &sceneDescriptorSetLayout_,
            .pushConstantRangeCount = 0,
            .pPushConstantRanges = nullptr,
        };
        if (vkCreatePipelineLayout(device_, &layoutCreateInfo, nullptr, &graphicsPipelineLayout_) != VK_SUCCESS) {
            SetError("vkCreatePipelineLayout failed.");
            return false;
        }
    }

    if (!graphicsQueueSupportsCompute_) {
        computeCompactionEnabled_ = false;
        return true;
    }

    if (computeDescriptorSetLayout_ == VK_NULL_HANDLE) {
        const std::array<VkDescriptorSetLayoutBinding, 5> bindings = {{
            {
                .binding = 0,
                .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                .pImmutableSamplers = nullptr,
            },
            {
                .binding = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                .pImmutableSamplers = nullptr,
            },
            {
                .binding = 2,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                .pImmutableSamplers = nullptr,
            },
            {
                .binding = 3,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                .pImmutableSamplers = nullptr,
            },
            {
                .binding = 4,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                .pImmutableSamplers = nullptr,
            },
        }};
        const VkDescriptorSetLayoutCreateInfo layoutCreateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .bindingCount = static_cast<uint32_t>(bindings.size()),
            .pBindings = bindings.data(),
        };
        if (vkCreateDescriptorSetLayout(device_, &layoutCreateInfo, nullptr, &computeDescriptorSetLayout_) != VK_SUCCESS) {
            SetError("vkCreateDescriptorSetLayout failed for compute descriptors.");
            return false;
        }
    }

    if (computeDescriptorPool_ == VK_NULL_HANDLE) {
        const std::array<VkDescriptorPoolSize, 2> poolSizes = {{
            {
                .type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                .descriptorCount = 2,
            },
            {
                .type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .descriptorCount = 8,
            },
        }};
        const VkDescriptorPoolCreateInfo poolCreateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .maxSets = 2,
            .poolSizeCount = static_cast<uint32_t>(poolSizes.size()),
            .pPoolSizes = poolSizes.data(),
        };
        if (vkCreateDescriptorPool(device_, &poolCreateInfo, nullptr, &computeDescriptorPool_) != VK_SUCCESS) {
            SetError("vkCreateDescriptorPool failed for compute descriptors.");
            return false;
        }
    }

    if (tracerMediumComputeDescriptorSet_ == VK_NULL_HANDLE || tracerFarComputeDescriptorSet_ == VK_NULL_HANDLE) {
        const std::array<VkDescriptorSetLayout, 2> layouts = {computeDescriptorSetLayout_, computeDescriptorSetLayout_};
        const VkDescriptorSetAllocateInfo allocateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .pNext = nullptr,
            .descriptorPool = computeDescriptorPool_,
            .descriptorSetCount = static_cast<uint32_t>(layouts.size()),
            .pSetLayouts = layouts.data(),
        };
        std::array<VkDescriptorSet, 2> sets{};
        if (vkAllocateDescriptorSets(device_, &allocateInfo, sets.data()) != VK_SUCCESS) {
            SetError("vkAllocateDescriptorSets failed for compute descriptors.");
            return false;
        }
        tracerMediumComputeDescriptorSet_ = sets[0];
        tracerFarComputeDescriptorSet_ = sets[1];
    }

    if (computePipelineLayout_ == VK_NULL_HANDLE) {
        const VkPushConstantRange pushConstantRange{
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            .offset = 0,
            .size = sizeof(ComputePushConstants),
        };
        const VkPipelineLayoutCreateInfo layoutCreateInfo{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .setLayoutCount = 1,
            .pSetLayouts = &computeDescriptorSetLayout_,
            .pushConstantRangeCount = 1,
            .pPushConstantRanges = &pushConstantRange,
        };
        if (vkCreatePipelineLayout(device_, &layoutCreateInfo, nullptr, &computePipelineLayout_) != VK_SUCCESS) {
            SetError("vkCreatePipelineLayout failed for compute pipeline layout.");
            return false;
        }
    }

    return true;
}

bool SolarLabVulkanRenderer::CreateGraphicsPipelines() {
    if (renderPass_ == VK_NULL_HANDLE || graphicsPipelineLayout_ == VK_NULL_HANDLE) {
        SetError("Cannot create graphics pipelines before the render pass and pipeline layout are ready.");
        return false;
    }

    DestroyGraphicsPipelines();

    const std::vector<VkVertexInputBindingDescription> billboardBindings = {
        MakeBindingDescription(0, sizeof(BillboardVertex), VK_VERTEX_INPUT_RATE_INSTANCE),
    };
    const std::vector<VkVertexInputAttributeDescription> billboardAttributes = {
        MakeAttributeDescription(0, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(BillboardVertex, x)),
        MakeAttributeDescription(1, 0, VK_FORMAT_R32_SFLOAT, offsetof(BillboardVertex, radiusM)),
        MakeAttributeDescription(2, 0, VK_FORMAT_R32_UINT, offsetof(BillboardVertex, colorArgb)),
        MakeAttributeDescription(3, 0, VK_FORMAT_R32_UINT, offsetof(BillboardVertex, kind)),
        MakeAttributeDescription(4, 0, VK_FORMAT_R32_SFLOAT, offsetof(BillboardVertex, alpha)),
        MakeAttributeDescription(5, 0, VK_FORMAT_R32_SFLOAT, offsetof(BillboardVertex, reserved)),
    };
    if (!CreateGraphicsPipeline(
            "billboard",
            "shaders/solarlab/billboard.vert.spv",
            "shaders/solarlab/billboard.frag.spv",
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
            false,
            billboardBindings,
            billboardAttributes,
            billboardPipeline_)) {
        DestroyGraphicsPipelines();
        return false;
    }

    const std::vector<VkVertexInputBindingDescription> mediumBindings = {
        MakeBindingDescription(0, sizeof(CheapPointVertex)),
    };
    const std::vector<VkVertexInputAttributeDescription> mediumAttributes = {
        MakeAttributeDescription(0, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(CheapPointVertex, x)),
        MakeAttributeDescription(1, 0, VK_FORMAT_R32_UINT, offsetof(CheapPointVertex, colorArgb)),
        MakeAttributeDescription(2, 0, VK_FORMAT_R32_SFLOAT, offsetof(CheapPointVertex, sizePx)),
    };
    if (!CreateGraphicsPipeline(
            "cheap-point",
            "shaders/solarlab/cheap_point.vert.spv",
            "shaders/solarlab/cheap_point.frag.spv",
            VK_PRIMITIVE_TOPOLOGY_POINT_LIST,
            false,
            mediumBindings,
            mediumAttributes,
            mediumPointPipeline_)) {
        DestroyGraphicsPipelines();
        return false;
    }

    const std::vector<VkVertexInputBindingDescription> densityBindings = {
        MakeBindingDescription(0, sizeof(DensityPointVertex)),
    };
    const std::vector<VkVertexInputAttributeDescription> densityAttributes = {
        MakeAttributeDescription(0, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(DensityPointVertex, x)),
        MakeAttributeDescription(1, 0, VK_FORMAT_R32_UINT, offsetof(DensityPointVertex, colorArgb)),
        MakeAttributeDescription(2, 0, VK_FORMAT_R32_UINT, offsetof(DensityPointVertex, densityWeight)),
    };
    if (!CreateGraphicsPipeline(
            "density-point",
            "shaders/solarlab/density_point.vert.spv",
            "shaders/solarlab/density_point.frag.spv",
            VK_PRIMITIVE_TOPOLOGY_POINT_LIST,
            true,
            densityBindings,
            densityAttributes,
            farDensityPipeline_)) {
        DestroyGraphicsPipelines();
        return false;
    }

    const std::vector<VkVertexInputBindingDescription> trailBindings = {
        MakeBindingDescription(0, sizeof(TrailVertex)),
    };
    const std::vector<VkVertexInputAttributeDescription> trailAttributes = {
        MakeAttributeDescription(0, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(TrailVertex, x)),
        MakeAttributeDescription(1, 0, VK_FORMAT_R32_UINT, offsetof(TrailVertex, colorArgb)),
        MakeAttributeDescription(2, 0, VK_FORMAT_R32_SFLOAT, offsetof(TrailVertex, alpha)),
    };
    if (!CreateGraphicsPipeline(
            "trail",
            "shaders/solarlab/trail.vert.spv",
            "shaders/solarlab/trail.frag.spv",
            VK_PRIMITIVE_TOPOLOGY_LINE_STRIP,
            false,
            trailBindings,
            trailAttributes,
            trailPipeline_)) {
        DestroyGraphicsPipelines();
        return false;
    }

    backendLabelCache_ = std::string("Vulkan SPIR-V graphics pipelines") + (enabledFeatures_.largePoints ? " + largePoints" : " (point sizes clamped)");
    return true;
}

bool SolarLabVulkanRenderer::CreateComputePipelines() {
    computeCompactionEnabled_ = false;
    if (!graphicsQueueSupportsCompute_ || computePipelineLayout_ == VK_NULL_HANDLE) {
        return true;
    }

    DestroyComputePipelines();

    if (!CreateComputePipeline("compact-medium", "shaders/solarlab/compact_medium.comp.spv", mediumComputePipeline_)) {
        LogError(lastError_);
        lastError_.clear();
        DestroyComputePipelines();
        return true;
    }
    if (!CreateComputePipeline("compact-far", "shaders/solarlab/compact_far.comp.spv", farComputePipeline_)) {
        LogError(lastError_);
        lastError_.clear();
        DestroyComputePipelines();
        return true;
    }

    computeCompactionEnabled_ = true;
    return true;
}

bool SolarLabVulkanRenderer::AllocateAndRecordCommandBuffers() {
    if (commandPool_ == VK_NULL_HANDLE) {
        SetError("Command pool is not available for command buffer allocation.");
        return false;
    }

    if (!commandBuffers_.empty()) {
        vkFreeCommandBuffers(device_, commandPool_, static_cast<uint32_t>(commandBuffers_.size()), commandBuffers_.data());
        commandBuffers_.clear();
    }

    commandBuffers_.resize(framebuffers_.size());
    const VkCommandBufferAllocateInfo allocateInfo{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .pNext = nullptr,
        .commandPool = commandPool_,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = static_cast<uint32_t>(commandBuffers_.size()),
    };
    if (vkAllocateCommandBuffers(device_, &allocateInfo, commandBuffers_.data()) != VK_SUCCESS) {
        SetError("vkAllocateCommandBuffers failed.");
        return false;
    }

    for (size_t index = 0; index < commandBuffers_.size(); ++index) {
        const VkCommandBufferBeginInfo beginInfo{
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .pNext = nullptr,
            .flags = 0,
            .pInheritanceInfo = nullptr,
        };
        if (vkBeginCommandBuffer(commandBuffers_[index], &beginInfo) != VK_SUCCESS) {
            SetError("vkBeginCommandBuffer failed.");
            return false;
        }

        if (!RecordComputePassLocked(commandBuffers_[index])) {
            return false;
        }

        const VkMemoryBarrier hostToGraphicsBarrier{
            .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT,
        };
        vkCmdPipelineBarrier(
            commandBuffers_[index],
            VK_PIPELINE_STAGE_HOST_BIT,
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
            0,
            1,
            &hostToGraphicsBarrier,
            0,
            nullptr,
            0,
            nullptr);

        const std::array<VkClearValue, 2> clearValues = {{
            {{{0.0f, 0.0f, 0.03f, 1.0f}}},
            {.depthStencil = {1.0f, 0U}},
        }};
        const VkRenderPassBeginInfo renderPassBeginInfo{
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .pNext = nullptr,
            .renderPass = renderPass_,
            .framebuffer = framebuffers_[index],
            .renderArea = {
                .offset = {0, 0},
                .extent = swapchainExtent_,
            },
            .clearValueCount = static_cast<uint32_t>(clearValues.size()),
            .pClearValues = clearValues.data(),
        };
        vkCmdBeginRenderPass(commandBuffers_[index], &renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);
        if (!RecordSceneBindingsLocked(commandBuffers_[index])) {
            return false;
        }
        vkCmdEndRenderPass(commandBuffers_[index]);

        if (vkEndCommandBuffer(commandBuffers_[index]) != VK_SUCCESS) {
            SetError("vkEndCommandBuffer failed.");
            return false;
        }
    }

    commandBuffersRevision_ = sceneGpuStreams_.uploadedRevision;
    return true;
}

bool SolarLabVulkanRenderer::CreateSyncObjects() {
    if (imageAvailableSemaphore_ != VK_NULL_HANDLE && renderFinishedSemaphore_ != VK_NULL_HANDLE && inFlightFence_ != VK_NULL_HANDLE) {
        return true;
    }

    const VkSemaphoreCreateInfo semaphoreCreateInfo{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
    };
    const VkFenceCreateInfo fenceCreateInfo{
        .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
        .pNext = nullptr,
        .flags = VK_FENCE_CREATE_SIGNALED_BIT,
    };

    if (vkCreateSemaphore(device_, &semaphoreCreateInfo, nullptr, &imageAvailableSemaphore_) != VK_SUCCESS ||
        vkCreateSemaphore(device_, &semaphoreCreateInfo, nullptr, &renderFinishedSemaphore_) != VK_SUCCESS ||
        vkCreateFence(device_, &fenceCreateInfo, nullptr, &inFlightFence_) != VK_SUCCESS) {
        SetError("Failed to create Vulkan synchronisation objects.");
        return false;
    }
    return true;
}

void SolarLabVulkanRenderer::DestroySurfaceResources() {
    if (device_ == VK_NULL_HANDLE) {
        return;
    }

    if (!commandBuffers_.empty() && commandPool_ != VK_NULL_HANDLE) {
        vkFreeCommandBuffers(device_, commandPool_, static_cast<uint32_t>(commandBuffers_.size()), commandBuffers_.data());
        commandBuffers_.clear();
    }
    commandBuffersRevision_ = -1;

    DestroyGraphicsPipelines();

    for (auto framebuffer : framebuffers_) {
        vkDestroyFramebuffer(device_, framebuffer, nullptr);
    }
    framebuffers_.clear();

    DestroyDepthResources();

    if (renderPass_ != VK_NULL_HANDLE) {
        vkDestroyRenderPass(device_, renderPass_, nullptr);
        renderPass_ = VK_NULL_HANDLE;
    }

    for (auto imageView : swapchainImageViews_) {
        vkDestroyImageView(device_, imageView, nullptr);
    }
    swapchainImageViews_.clear();
    swapchainImages_.clear();

    if (swapchain_ != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }
}

void SolarLabVulkanRenderer::Cleanup() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
    }

    DestroySceneGpuStreams();
    DestroySurfaceResources();
    DestroyDescriptorResources();

    if (device_ != VK_NULL_HANDLE) {
        if (pipelineCache_ != VK_NULL_HANDLE) {
            vkDestroyPipelineCache(device_, pipelineCache_, nullptr);
            pipelineCache_ = VK_NULL_HANDLE;
        }
        if (imageAvailableSemaphore_ != VK_NULL_HANDLE) {
            vkDestroySemaphore(device_, imageAvailableSemaphore_, nullptr);
            imageAvailableSemaphore_ = VK_NULL_HANDLE;
        }
        if (renderFinishedSemaphore_ != VK_NULL_HANDLE) {
            vkDestroySemaphore(device_, renderFinishedSemaphore_, nullptr);
            renderFinishedSemaphore_ = VK_NULL_HANDLE;
        }
        if (inFlightFence_ != VK_NULL_HANDLE) {
            vkDestroyFence(device_, inFlightFence_, nullptr);
            inFlightFence_ = VK_NULL_HANDLE;
        }
        if (commandPool_ != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device_, commandPool_, nullptr);
            commandPool_ = VK_NULL_HANDLE;
        }
    }

    if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
    }
    if (nativeWindow_ != nullptr) {
        ANativeWindow_release(nativeWindow_);
        nativeWindow_ = nullptr;
    }

    if (device_ != VK_NULL_HANDLE) {
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }
    pipelineCache_ = VK_NULL_HANDLE;

    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }

    physicalDevice_ = VK_NULL_HANDLE;
    graphicsQueue_ = VK_NULL_HANDLE;
    presentQueue_ = VK_NULL_HANDLE;
    graphicsQueueFamilyIndex_ = UINT32_MAX;
    presentQueueFamilyIndex_ = UINT32_MAX;
    sceneGpuStreams_.uploadedRevision = -1;
    uploadStats_ = StreamUploadStats{};
    backendLabelCache_ = "Vulkan SPIR-V graphics pipelines cleaned up";
    sceneSummaryCache_ = "Scene not uploaded.";
}

void SolarLabVulkanRenderer::SetError(const std::string& message) {
    lastError_ = message;
    LogError(message);
}

VkSurfaceFormatKHR SolarLabVulkanRenderer::ChooseSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) const {
    for (const auto& format : formats) {
        if (format.format == VK_FORMAT_B8G8R8A8_UNORM && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            return format;
        }
    }
    return formats.front();
}

VkPresentModeKHR SolarLabVulkanRenderer::ChoosePresentMode(const std::vector<VkPresentModeKHR>& presentModes) const {
    for (const auto& presentMode : presentModes) {
        if (presentMode == VK_PRESENT_MODE_MAILBOX_KHR) {
            return presentMode;
        }
    }
    return VK_PRESENT_MODE_FIFO_KHR;
}

VkExtent2D SolarLabVulkanRenderer::ChooseExtent(const VkSurfaceCapabilitiesKHR& capabilities, int width, int height) const {
    if (capabilities.currentExtent.width != std::numeric_limits<uint32_t>::max()) {
        return capabilities.currentExtent;
    }

    VkExtent2D actualExtent{
        .width = static_cast<uint32_t>(width),
        .height = static_cast<uint32_t>(height),
    };
    actualExtent.width = std::clamp(actualExtent.width, capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
    actualExtent.height = std::clamp(actualExtent.height, capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    return actualExtent;
}

bool SolarLabVulkanRenderer::EnsureSceneGpuStreamsLocked() {
    if (sceneGpuStreams_.uploadedRevision == sceneBuffers_.sourceRevision) {
        return true;
    }
    return UploadSceneGpuStreamsLocked();
}

// Repackages the latest runtime scene into GPU vertex, indirect, and compaction buffers while the
// caller holds the renderer state lock.
bool SolarLabVulkanRenderer::UploadSceneGpuStreamsLocked() {
    if (device_ == VK_NULL_HANDLE || physicalDevice_ == VK_NULL_HANDLE) {
        SetError("Cannot upload scene streams before the Vulkan device is ready.");
        return false;
    }

    DestroySceneGpuStreams();

    // Authoritative bodies feed both billboard rendering and compute influence buffers, so their
    // parallel arrays are collapsed together before any upload starts.
    std::vector<BillboardVertex> authoritativeVertices;
    const uint32_t authoritativeCount = SafeCount3(
        sceneBuffers_.authoritativePositionsM.size(),
        sceneBuffers_.authoritativeSourceMassesKg.size(),
        sceneBuffers_.authoritativeRadiiM.size(),
        sceneBuffers_.authoritativeColorsArgb.size(),
        sceneBuffers_.authoritativeKinds.size());
    std::vector<AuthoritativeInfluenceBody> authoritativeInfluences;
    authoritativeVertices.reserve(authoritativeCount);
    authoritativeInfluences.reserve(authoritativeCount);
    for (uint32_t index = 0; index < authoritativeCount; ++index) {
        const size_t base = static_cast<size_t>(index) * 3U;
        authoritativeVertices.push_back(BillboardVertex{
            .x = static_cast<float>(sceneBuffers_.authoritativePositionsM[base]),
            .y = static_cast<float>(sceneBuffers_.authoritativePositionsM[base + 1U]),
            .z = static_cast<float>(sceneBuffers_.authoritativePositionsM[base + 2U]),
            .radiusM = sceneBuffers_.authoritativeRadiiM[index],
            .colorArgb = static_cast<uint32_t>(sceneBuffers_.authoritativeColorsArgb[index]),
            .kind = static_cast<uint32_t>(sceneBuffers_.authoritativeKinds[index]),
            .alpha = 1.0f,
            .reserved = 0.0f,
        });
        authoritativeInfluences.push_back(AuthoritativeInfluenceBody{
            .x = static_cast<float>(sceneBuffers_.authoritativePositionsM[base]),
            .y = static_cast<float>(sceneBuffers_.authoritativePositionsM[base + 1U]),
            .z = static_cast<float>(sceneBuffers_.authoritativePositionsM[base + 2U]),
            .sourceMassKg = static_cast<float>(sceneBuffers_.authoritativeSourceMassesKg[index]),
        });
    }

    // Near tracers remain full billboards because they carry visible radius and kind information.
    std::vector<BillboardVertex> tracerNearVertices;
    const uint32_t tracerNearCount = SafeCount3(
        sceneBuffers_.tracerNearPositionsM.size(),
        sceneBuffers_.tracerNearRadiiM.size(),
        sceneBuffers_.tracerNearColorsArgb.size(),
        sceneBuffers_.tracerNearKinds.size());
    tracerNearVertices.reserve(tracerNearCount);
    for (uint32_t index = 0; index < tracerNearCount; ++index) {
        const size_t base = static_cast<size_t>(index) * 3U;
        tracerNearVertices.push_back(BillboardVertex{
            .x = static_cast<float>(sceneBuffers_.tracerNearPositionsM[base]),
            .y = static_cast<float>(sceneBuffers_.tracerNearPositionsM[base + 1U]),
            .z = static_cast<float>(sceneBuffers_.tracerNearPositionsM[base + 2U]),
            .radiusM = sceneBuffers_.tracerNearRadiiM[index],
            .colorArgb = static_cast<uint32_t>(sceneBuffers_.tracerNearColorsArgb[index]),
            .kind = static_cast<uint32_t>(sceneBuffers_.tracerNearKinds[index]),
            .alpha = kNearTracerAlpha,
            .reserved = 0.0f,
        });
    }

    // Medium tracers are split into draw vertices plus compute state for GPU-side compaction.
    std::vector<CheapPointVertex> tracerMediumVertices;
    const uint32_t tracerMediumCount = SafeCount3(
        sceneBuffers_.tracerMediumPositionsM.size(),
        sceneBuffers_.tracerMediumVelocitiesMps.size() / 3U,
        sceneBuffers_.tracerMediumRadiiM.size(),
        sceneBuffers_.tracerMediumColorsArgb.size(),
        sceneBuffers_.tracerMediumStableIds.size());
    std::vector<MediumTracerState> tracerMediumStates;
    tracerMediumVertices.reserve(tracerMediumCount);
    tracerMediumStates.reserve(tracerMediumCount);
    for (uint32_t index = 0; index < tracerMediumCount; ++index) {
        const size_t base = static_cast<size_t>(index) * 3U;
        const float radiusM = sceneBuffers_.tracerMediumRadiiM[index];
        const float sizePx = std::max(
            kMediumTracerPointSizePx,
            radiusM > 0.0f ? 0.75f * static_cast<float>(std::log10(radiusM + 10.0f)) : kMediumTracerPointSizePx);
        tracerMediumVertices.push_back(CheapPointVertex{
            .x = static_cast<float>(sceneBuffers_.tracerMediumPositionsM[base]),
            .y = static_cast<float>(sceneBuffers_.tracerMediumPositionsM[base + 1U]),
            .z = static_cast<float>(sceneBuffers_.tracerMediumPositionsM[base + 2U]),
            .colorArgb = ApplyAlphaToArgb(static_cast<uint32_t>(sceneBuffers_.tracerMediumColorsArgb[index]), kMediumTracerAlpha),
            .sizePx = sizePx,
        });
        tracerMediumStates.push_back(MediumTracerState{
            .x = static_cast<float>(sceneBuffers_.tracerMediumPositionsM[base]),
            .y = static_cast<float>(sceneBuffers_.tracerMediumPositionsM[base + 1U]),
            .vx = static_cast<float>(sceneBuffers_.tracerMediumVelocitiesMps[base]),
            .vy = static_cast<float>(sceneBuffers_.tracerMediumVelocitiesMps[base + 1U]),
            .colorArgb = ApplyAlphaToArgb(static_cast<uint32_t>(sceneBuffers_.tracerMediumColorsArgb[index]), kMediumTracerAlpha),
            .sizePx = sizePx,
            .stableId = static_cast<uint32_t>(sceneBuffers_.tracerMediumStableIds[index]),
            .reserved = 0U,
        });
    }

    // Far tracers use density vertices and packed state so large scenes stay within draw budgets.
    std::vector<DensityPointVertex> tracerFarVertices;
    const uint32_t tracerFarCount = SafeCount3(
        sceneBuffers_.tracerFarPositionsM.size(),
        sceneBuffers_.tracerFarVelocitiesMps.size() / 3U,
        sceneBuffers_.tracerFarRadiiM.size(),
        sceneBuffers_.tracerFarColorsArgb.size(),
        sceneBuffers_.tracerFarStableIds.size());
    std::vector<FarTracerState> tracerFarStates;
    tracerFarVertices.reserve(tracerFarCount);
    tracerFarStates.reserve(tracerFarCount);
    for (uint32_t index = 0; index < tracerFarCount; ++index) {
        const size_t base = static_cast<size_t>(index) * 3U;
        const float radiusM = sceneBuffers_.tracerFarRadiiM[index];
        const uint32_t densityWeight = static_cast<uint32_t>(std::clamp(std::lround(std::max(1.0f, radiusM > 0.0f ? std::log10(radiusM + 10.0f) : 1.0f)), 1l, 4l));
        tracerFarVertices.push_back(DensityPointVertex{
            .x = static_cast<float>(sceneBuffers_.tracerFarPositionsM[base]),
            .y = static_cast<float>(sceneBuffers_.tracerFarPositionsM[base + 1U]),
            .z = static_cast<float>(sceneBuffers_.tracerFarPositionsM[base + 2U]),
            .colorArgb = ApplyAlphaToArgb(static_cast<uint32_t>(sceneBuffers_.tracerFarColorsArgb[index]), kFarTracerAlpha),
            .densityWeight = densityWeight,
        });
        tracerFarStates.push_back(FarTracerState{
            .x = static_cast<float>(sceneBuffers_.tracerFarPositionsM[base]),
            .y = static_cast<float>(sceneBuffers_.tracerFarPositionsM[base + 1U]),
            .vx = static_cast<float>(sceneBuffers_.tracerFarVelocitiesMps[base]),
            .vy = static_cast<float>(sceneBuffers_.tracerFarVelocitiesMps[base + 1U]),
            .colorArgb = ApplyAlphaToArgb(static_cast<uint32_t>(sceneBuffers_.tracerFarColorsArgb[index]), kFarTracerAlpha),
            .densityWeight = densityWeight,
            .stableId = static_cast<uint32_t>(sceneBuffers_.tracerFarStableIds[index]),
            .reserved = 0U,
        });
    }

    // Trails stay in CPU-provided order so each span can be replayed as a separate line strip.
    std::vector<TrailVertex> trailVertices;
    const uint32_t trailPointCount = SafeCount3(
        sceneBuffers_.trailPositionsM.size(),
        sceneBuffers_.trailColorsArgb.size(),
        sceneBuffers_.trailColorsArgb.size());
    trailVertices.reserve(trailPointCount);
    for (uint32_t index = 0; index < trailPointCount; ++index) {
        const size_t base = static_cast<size_t>(index) * 3U;
        trailVertices.push_back(TrailVertex{
            .x = static_cast<float>(sceneBuffers_.trailPositionsM[base]),
            .y = static_cast<float>(sceneBuffers_.trailPositionsM[base + 1U]),
            .z = static_cast<float>(sceneBuffers_.trailPositionsM[base + 2U]),
            .colorArgb = static_cast<uint32_t>(sceneBuffers_.trailColorsArgb[index]),
            .alpha = kTrailAlpha,
        });
    }

    // Stream metadata is filled before allocation so fallback paths can share labels and usage.
    sceneGpuStreams_.authoritative.path = DrawPath::BillboardSprite;
    sceneGpuStreams_.authoritative.label = "authoritative";
    sceneGpuStreams_.authoritative.strideBytes = sizeof(BillboardVertex);
    sceneGpuStreams_.authoritative.plannedUsage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    sceneGpuStreams_.authoritative.vertexCount = authoritativeCount;

    sceneGpuStreams_.tracerNear.path = DrawPath::BillboardSprite;
    sceneGpuStreams_.tracerNear.label = "tracer-near";
    sceneGpuStreams_.tracerNear.strideBytes = sizeof(BillboardVertex);
    sceneGpuStreams_.tracerNear.plannedUsage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    sceneGpuStreams_.tracerNear.vertexCount = tracerNearCount;

    sceneGpuStreams_.tracerMedium.path = DrawPath::CheapPointSprite;
    sceneGpuStreams_.tracerMedium.label = "tracer-medium";
    sceneGpuStreams_.tracerMedium.strideBytes = sizeof(CheapPointVertex);
    sceneGpuStreams_.tracerMedium.plannedUsage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    sceneGpuStreams_.tracerMedium.vertexCount = tracerMediumCount;

    sceneGpuStreams_.tracerFar.path = DrawPath::DensityPoint;
    sceneGpuStreams_.tracerFar.label = "tracer-far";
    sceneGpuStreams_.tracerFar.strideBytes = sizeof(DensityPointVertex);
    sceneGpuStreams_.tracerFar.plannedUsage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    sceneGpuStreams_.tracerFar.vertexCount = tracerFarCount;

    sceneGpuStreams_.trails.path = DrawPath::ThinLineStrip;
    sceneGpuStreams_.trails.label = "trails";
    sceneGpuStreams_.trails.strideBytes = sizeof(TrailVertex);
    sceneGpuStreams_.trails.plannedUsage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    sceneGpuStreams_.trails.vertexCount = trailPointCount;
    sceneGpuStreams_.trailStripVertexCounts.clear();

    // Host-visible uploads are the common path for small or CPU-authored streams.
    auto uploadStream = [this](const void* data, size_t sizeBytes, VkBufferUsageFlags usage, const char* label, GpuBuffer& target) -> bool {
        if (sizeBytes == 0) {
            DestroyGpuBuffer(target);
            return true;
        }
        if (!EnsureHostVisibleBuffer(static_cast<VkDeviceSize>(sizeBytes), usage, label, target)) {
            return false;
        }
        return UploadBytes(data, sizeBytes, target);
    };

    // Tracer streams prefer device-local staging, then fall back to mapped buffers for safety.
    auto uploadTracerStream = [this, &uploadStream](const void* data, size_t sizeBytes, VkBufferUsageFlags usage, const char* label, GpuBuffer& target) -> bool {
        if (sizeBytes == 0) {
            DestroyGpuBuffer(target);
            return true;
        }
        if (TryUploadDeviceLocalWithStaging(data, sizeBytes, usage, label, target)) {
            return true;
        }
        LogInfo(std::string("Falling back to host-visible upload for ") + (label != nullptr ? label : "unnamed tracer stream") + ".");
        return uploadStream(data, sizeBytes, usage, label, target);
    };
    // Compute compaction failure is non-fatal because direct draw buffers have already uploaded.
    auto disableComputeStream = [this](ComputeDrawStreamBuffers& stream, const char* label, const char* reason) {
        if (stream.enabled) {
            LogInfo(std::string("Disabling compute-compaction path for ") + (stream.label != nullptr ? stream.label : label) + " because " + reason + "; using direct draw fallback.");
        }
        stream.enabled = false;
        stream.sourceVertexCount = 0U;
        stream.dispatchGroupCountX = 0U;
        stream.outputVertexCapacity = 0U;
        stream.tileCounterCount = 0U;
        stream.activeTileCount = 0U;
        stream.peakTileOccupancy = 0U;
        stream.tileStatsValid = false;
        stream.visibleVertexCount = 0U;
        stream.visibleVertexCountValid = false;
        DestroyGpuBuffer(stream.sourceStateBuffer);
        DestroyGpuBuffer(stream.outputVertexBuffer);
        DestroyGpuBuffer(stream.indirectCommandBuffer);
        DestroyGpuBuffer(stream.indirectReadbackBuffer);
        DestroyGpuBuffer(stream.tileCounterBuffer);
        DestroyGpuBuffer(stream.tileCounterReadbackBuffer);
    };
    auto ensureDeviceLocalComputeBuffer = [this](VkDeviceSize sizeBytes, VkBufferUsageFlags usage, const char* label, GpuBuffer& target) -> bool {
        if (sizeBytes == 0) {
            DestroyGpuBuffer(target);
            return true;
        }
        return EnsureDeviceLocalBuffer(sizeBytes, usage, label, false, target);
    };
    auto initializeComputeIndirectBuffer = [this](const VkDrawIndirectCommand& command, VkBufferUsageFlags usage, const char* label, GpuBuffer& target) -> bool {
        if (TryUploadDeviceLocalWithStaging(&command, sizeof(command), usage, label, target)) {
            return true;
        }
        return false;
    };

    // Direct draw streams are populated first so rendering can continue without compute support.
    if (!uploadStream(authoritativeVertices.data(), ByteSize(authoritativeVertices), sceneGpuStreams_.authoritative.plannedUsage, sceneGpuStreams_.authoritative.label, sceneGpuStreams_.authoritative.vertexBuffer)) {
        return false;
    }
    if (!uploadTracerStream(authoritativeInfluences.data(), ByteSize(authoritativeInfluences), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "authoritative-influence", sceneGpuStreams_.authoritativeInfluenceBuffer)) {
        return false;
    }
    if (!uploadStream(tracerNearVertices.data(), ByteSize(tracerNearVertices), sceneGpuStreams_.tracerNear.plannedUsage, sceneGpuStreams_.tracerNear.label, sceneGpuStreams_.tracerNear.vertexBuffer)) {
        return false;
    }
    if (!uploadTracerStream(tracerMediumVertices.data(), ByteSize(tracerMediumVertices), sceneGpuStreams_.tracerMedium.plannedUsage, sceneGpuStreams_.tracerMedium.label, sceneGpuStreams_.tracerMedium.vertexBuffer)) {
        return false;
    }
    if (!uploadTracerStream(tracerFarVertices.data(), ByteSize(tracerFarVertices), sceneGpuStreams_.tracerFar.plannedUsage, sceneGpuStreams_.tracerFar.label, sceneGpuStreams_.tracerFar.vertexBuffer)) {
        return false;
    }
    if (!uploadStream(trailVertices.data(), ByteSize(trailVertices), sceneGpuStreams_.trails.plannedUsage, sceneGpuStreams_.trails.label, sceneGpuStreams_.trails.vertexBuffer)) {
        return false;
    }

    // Trail strip counts are clamped to the uploaded vertex payload to avoid stale packet lengths.
    uint32_t remainingTrailVertices = trailPointCount;
    for (int32_t rawCount : sceneBuffers_.trailVertexCounts) {
        if (remainingTrailVertices < 2U) {
            break;
        }
        const uint32_t stripCount = static_cast<uint32_t>(std::max(rawCount, 0));
        if (stripCount < 2U) {
            continue;
        }
        const uint32_t clampedCount = std::min(stripCount, remainingTrailVertices);
        if (clampedCount < 2U) {
            continue;
        }
        sceneGpuStreams_.trailStripVertexCounts.push_back(clampedCount);
        remainingTrailVertices -= clampedCount;
    }

    // The compute path is enabled only after descriptors and both pipelines exist together.
    const bool canCompute = computeCompactionEnabled_ &&
        tracerMediumComputeDescriptorSet_ != VK_NULL_HANDLE &&
        tracerFarComputeDescriptorSet_ != VK_NULL_HANDLE &&
        mediumComputePipeline_ != VK_NULL_HANDLE &&
        farComputePipeline_ != VK_NULL_HANDLE;

    sceneGpuStreams_.tracerMediumCompute.enabled = canCompute && tracerMediumCount > 0U;
    sceneGpuStreams_.tracerMediumCompute.path = DrawPath::CheapPointSprite;
    sceneGpuStreams_.tracerMediumCompute.label = "tracer-medium-compute";
    sceneGpuStreams_.tracerMediumCompute.sourceVertexCount = tracerMediumCount;
    sceneGpuStreams_.tracerMediumCompute.dispatchGroupCountX = RoundUpWorkgroups(tracerMediumCount, kComputeLocalSizeX);

    sceneGpuStreams_.tracerFarCompute.enabled = canCompute && tracerFarCount > 0U;
    sceneGpuStreams_.tracerFarCompute.path = DrawPath::DensityPoint;
    sceneGpuStreams_.tracerFarCompute.label = "tracer-far-compute";
    sceneGpuStreams_.tracerFarCompute.sourceVertexCount = tracerFarCount;
    sceneGpuStreams_.tracerFarCompute.dispatchGroupCountX = RoundUpWorkgroups(tracerFarCount, kComputeLocalSizeX);
    sceneGpuStreams_.tracerFarCompute.outputVertexCapacity = 0U;

    // Far tracer compaction bins output by screen tile, so capacity follows the swapchain size.
    uint32_t farTileCounterCount = 0U;
    uint32_t farOutputVertexCapacity = 0U;
    if (sceneGpuStreams_.tracerFarCompute.enabled) {
        const uint32_t tileGridWidth = (std::max<uint32_t>(swapchainExtent_.width, 1U) + kFarTileSizePx - 1U) / kFarTileSizePx;
        const uint32_t tileGridHeight = (std::max<uint32_t>(swapchainExtent_.height, 1U) + kFarTileSizePx - 1U) / kFarTileSizePx;
        farTileCounterCount = tileGridWidth * tileGridHeight;
        farOutputVertexCapacity = farTileCounterCount * kFarTileBinCapacity;
    }

    // Medium tracers need source state, output vertices, indirect draw state, and readback.
    if (sceneGpuStreams_.tracerMediumCompute.enabled) {
        if (!TryUploadDeviceLocalWithStaging(
                tracerMediumStates.data(),
                ByteSize(tracerMediumStates),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                "tracer-medium-state",
                sceneGpuStreams_.tracerMediumCompute.sourceStateBuffer)) {
            disableComputeStream(sceneGpuStreams_.tracerMediumCompute, "tracer-medium-compute", "compute source state upload failed");
        } else if (!ensureDeviceLocalComputeBuffer(
                static_cast<VkDeviceSize>(std::max<size_t>(ByteSize(tracerMediumVertices), sizeof(CheapPointVertex))),
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                sceneGpuStreams_.tracerMediumCompute.label,
                sceneGpuStreams_.tracerMediumCompute.outputVertexBuffer)) {
            disableComputeStream(sceneGpuStreams_.tracerMediumCompute, "tracer-medium-compute", "device-local compute output allocation failed");
        } else if (!initializeComputeIndirectBuffer(
                MakeInitialIndirectCommand(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "tracer-medium-indirect",
                sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer)) {
            disableComputeStream(sceneGpuStreams_.tracerMediumCompute, "tracer-medium-compute", "compute indirect buffer upload could not be initialized");
        } else if (!EnsureHostVisibleBuffer(
                sizeof(VkDrawIndirectCommand),
                VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "tracer-medium-indirect-readback",
                sceneGpuStreams_.tracerMediumCompute.indirectReadbackBuffer)) {
            disableComputeStream(sceneGpuStreams_.tracerMediumCompute, "tracer-medium-compute", "indirect readback buffer allocation failed");
        } else {
            sceneGpuStreams_.tracerMediumCompute.visibleVertexCount = 0;
            sceneGpuStreams_.tracerMediumCompute.visibleVertexCountValid = false;
        }
    } else {
        DestroyGpuBuffer(sceneGpuStreams_.tracerMediumCompute.indirectReadbackBuffer);
        sceneGpuStreams_.tracerMediumCompute.visibleVertexCount = 0;
        sceneGpuStreams_.tracerMediumCompute.visibleVertexCountValid = false;
    }

    // Far tracers add tile counters so dense regions can be capped before draw submission.
    if (sceneGpuStreams_.tracerFarCompute.enabled) {
        if (!TryUploadDeviceLocalWithStaging(
                tracerFarStates.data(),
                ByteSize(tracerFarStates),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                "tracer-far-state",
                sceneGpuStreams_.tracerFarCompute.sourceStateBuffer)) {
            disableComputeStream(sceneGpuStreams_.tracerFarCompute, "tracer-far-compute", "compute source state upload failed");
        } else if (farTileCounterCount == 0U || farOutputVertexCapacity == 0U) {
            disableComputeStream(sceneGpuStreams_.tracerFarCompute, "tracer-far-compute", "tile counter grid was empty");
        } else if (!ensureDeviceLocalComputeBuffer(
                static_cast<VkDeviceSize>(std::max<uint32_t>(farOutputVertexCapacity, 1U)) * sizeof(DensityPointVertex),
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                sceneGpuStreams_.tracerFarCompute.label,
                sceneGpuStreams_.tracerFarCompute.outputVertexBuffer)) {
            disableComputeStream(sceneGpuStreams_.tracerFarCompute, "tracer-far-compute", "device-local compute output allocation failed");
        } else if (!initializeComputeIndirectBuffer(
                MakeInitialIndirectCommand(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "tracer-far-indirect",
                sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer)) {
            disableComputeStream(sceneGpuStreams_.tracerFarCompute, "tracer-far-compute", "compute indirect buffer upload could not be initialized");
        } else if (!EnsureHostVisibleBuffer(
                sizeof(VkDrawIndirectCommand),
                VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "tracer-far-indirect-readback",
                sceneGpuStreams_.tracerFarCompute.indirectReadbackBuffer)) {
            disableComputeStream(sceneGpuStreams_.tracerFarCompute, "tracer-far-compute", "indirect readback buffer allocation failed");
        } else {
            sceneGpuStreams_.tracerFarCompute.tileCounterCount = farTileCounterCount;
            sceneGpuStreams_.tracerFarCompute.outputVertexCapacity = farOutputVertexCapacity;
            if (!ensureDeviceLocalComputeBuffer(
                    static_cast<VkDeviceSize>(sceneGpuStreams_.tracerFarCompute.tileCounterCount + 1U) * sizeof(uint32_t),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    "tracer-far-tile-counters",
                    sceneGpuStreams_.tracerFarCompute.tileCounterBuffer)) {
                disableComputeStream(sceneGpuStreams_.tracerFarCompute, "tracer-far-compute", "tile counter buffer allocation failed");
            } else if (!EnsureHostVisibleBuffer(
                    static_cast<VkDeviceSize>(sceneGpuStreams_.tracerFarCompute.tileCounterCount + 1U) * sizeof(uint32_t),
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    "tracer-far-tile-counter-readback",
                    sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer)) {
                disableComputeStream(sceneGpuStreams_.tracerFarCompute, "tracer-far-compute", "tile counter readback buffer allocation failed");
            } else {
                sceneGpuStreams_.tracerFarCompute.activeTileCount = 0;
                sceneGpuStreams_.tracerFarCompute.peakTileOccupancy = 0;
                sceneGpuStreams_.tracerFarCompute.overflowVertexCount = 0;
                sceneGpuStreams_.tracerFarCompute.tileStatsValid = false;
                sceneGpuStreams_.tracerFarCompute.visibleVertexCount = 0;
                sceneGpuStreams_.tracerFarCompute.visibleVertexCountValid = false;
            }
        }
    } else {
        DestroyGpuBuffer(sceneGpuStreams_.tracerFarCompute.indirectReadbackBuffer);
        DestroyGpuBuffer(sceneGpuStreams_.tracerFarCompute.tileCounterBuffer);
        DestroyGpuBuffer(sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer);
        sceneGpuStreams_.tracerFarCompute.outputVertexCapacity = 0U;
        sceneGpuStreams_.tracerFarCompute.tileCounterCount = 0U;
        sceneGpuStreams_.tracerFarCompute.activeTileCount = 0;
        sceneGpuStreams_.tracerFarCompute.peakTileOccupancy = 0;
        sceneGpuStreams_.tracerFarCompute.overflowVertexCount = 0;
        sceneGpuStreams_.tracerFarCompute.tileStatsValid = false;
        sceneGpuStreams_.tracerFarCompute.visibleVertexCount = 0;
        sceneGpuStreams_.tracerFarCompute.visibleVertexCountValid = false;
    }

    if (canCompute && !UpdateComputeDescriptorSetsLocked()) {
        return false;
    }

    // Upload statistics and cached labels describe the exact buffers that survived fallbacks.
    sceneGpuStreams_.uploadedRevision = sceneBuffers_.sourceRevision;
    sceneGpuStreams_.authoritativeInfluenceCount = authoritativeCount;
    sceneGpuStreams_.totalBytes =
        sceneGpuStreams_.authoritativeInfluenceBuffer.sizeBytes +
        sceneGpuStreams_.authoritative.vertexBuffer.sizeBytes +
        sceneGpuStreams_.tracerNear.vertexBuffer.sizeBytes +
        sceneGpuStreams_.tracerMedium.vertexBuffer.sizeBytes +
        sceneGpuStreams_.tracerFar.vertexBuffer.sizeBytes +
        sceneGpuStreams_.trails.vertexBuffer.sizeBytes +
        sceneGpuStreams_.tracerMediumCompute.sourceStateBuffer.sizeBytes +
        sceneGpuStreams_.tracerMediumCompute.outputVertexBuffer.sizeBytes +
        sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer.sizeBytes +
        sceneGpuStreams_.tracerMediumCompute.indirectReadbackBuffer.sizeBytes +
        sceneGpuStreams_.tracerFarCompute.sourceStateBuffer.sizeBytes +
        sceneGpuStreams_.tracerFarCompute.outputVertexBuffer.sizeBytes +
        sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer.sizeBytes +
        sceneGpuStreams_.tracerFarCompute.indirectReadbackBuffer.sizeBytes +
        sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.sizeBytes +
        sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer.sizeBytes;

    uploadStats_.sourceRevision = sceneBuffers_.sourceRevision;
    uploadStats_.bytesUploaded = sceneGpuStreams_.totalBytes;
    uploadStats_.authoritativeCount = authoritativeCount;
    uploadStats_.tracerNearCount = tracerNearCount;
    uploadStats_.tracerMediumCount = tracerMediumCount;
    uploadStats_.tracerFarCount = tracerFarCount;
    uploadStats_.trailVertexCount = trailPointCount;
    uploadStats_.trailStripCount = static_cast<uint32_t>(sceneGpuStreams_.trailStripVertexCounts.size());

    backendLabelCache_ = std::string("Vulkan SPIR-V graphics pipelines") + (canCompute ? " + compute compaction" : (enabledFeatures_.largePoints ? " + largePoints" : " (point sizes clamped)"));
    sceneSummaryCache_ = BuildSceneSummaryLocked();
    commandBuffersRevision_ = -1;
    LogInfo("Uploaded Vulkan draw streams for revision " + std::to_string(sceneBuffers_.sourceRevision) + (canCompute ? " with compute-ready compaction buffers." : " with direct draw buffers."));
    return true;
}

bool SolarLabVulkanRenderer::UpdateSceneUniformBufferLocked() {
    if (sceneUniformBuffer_.memory == VK_NULL_HANDLE || swapchainExtent_.width == 0 || swapchainExtent_.height == 0) {
        SetError("Cannot update scene uniform buffer before descriptor resources and swapchain are ready.");
        return false;
    }

    const float widthPx = static_cast<float>(std::max<uint32_t>(swapchainExtent_.width, 1U));
    const float heightPx = static_cast<float>(std::max<uint32_t>(swapchainExtent_.height, 1U));
    const float minDimensionPx = std::max(1.0f, std::min(widthPx, heightPx));
    const float viewRadiusM = static_cast<float>(std::max(cameraViewRadiusM_, 1.0));
    const float halfSpanX = std::max(viewRadiusM * (widthPx / minDimensionPx), 1.0e-6f);
    const float halfSpanY = std::max(viewRadiusM * (heightPx / minDimensionPx), 1.0e-6f);
    const float halfDepth = std::max(viewRadiusM * kDefaultDepthExtentFactor, 1.0e-3f);
    const float metersPerPixel = std::max((2.0f * viewRadiusM) / minDimensionPx, 1.0e-6f);
    const float maxPointSizePx = enabledFeatures_.largePoints
        ? std::max(1.0f, std::min(physicalDeviceProperties_.limits.pointSizeRange[1], kDefaultMaxPointSizePx))
        : 1.0f;

    const float yawRadians = static_cast<float>(cameraYawRadians_);
    const float pitchRadians = std::clamp(static_cast<float>(cameraPitchRadians_), kOrbitMinPitchRadians, kOrbitMaxPitchRadians);
    const float cosYaw = std::cos(yawRadians);
    const float sinYaw = std::sin(yawRadians);
    const float cosPitch = std::cos(pitchRadians);
    const float sinPitch = std::sin(pitchRadians);

    const Float3 right = Normalize(MakeFloat3(cosYaw, sinYaw, 0.0f), MakeFloat3(1.0f, 0.0f, 0.0f));
    const Float3 screenUpHorizontal = Normalize(MakeFloat3(-sinYaw, cosYaw, 0.0f), MakeFloat3(0.0f, 1.0f, 0.0f));
    const Float3 forward = Normalize(
        Add(Scale(screenUpHorizontal, cosPitch), MakeFloat3(0.0f, 0.0f, -sinPitch)),
        MakeFloat3(0.0f, 0.0f, -1.0f));
    const Float3 up = Normalize(Cross(right, forward), MakeFloat3(0.0f, 0.0f, 1.0f));

    const Float3 centerRelative = MakeFloat3(
        static_cast<float>(cameraCenterX_ - sceneBuffers_.sceneOriginX),
        static_cast<float>(cameraCenterY_ - sceneBuffers_.sceneOriginY),
        static_cast<float>(cameraCenterZ_ - sceneBuffers_.sceneOriginZ));

    std::array<float, 4> primaryLightPositionRelativeAndFlags{
        0.0f,
        0.0f,
        0.0f,
        0.0f,
    };
    const size_t authoritativeBodyCount = std::min(
        sceneBuffers_.authoritativeKinds.size(),
        sceneBuffers_.authoritativePositionsM.size() / 3U);
    for (size_t index = 0; index < authoritativeBodyCount; ++index) {
        if (sceneBuffers_.authoritativeKinds[index] != 0) {
            continue;
        }
        const size_t base = index * 3U;
        primaryLightPositionRelativeAndFlags = {
            static_cast<float>(sceneBuffers_.authoritativePositionsM[base]),
            static_cast<float>(sceneBuffers_.authoritativePositionsM[base + 1U]),
            static_cast<float>(sceneBuffers_.authoritativePositionsM[base + 2U]),
            1.0f,
        };
        break;
    }

    const SceneUniformData uniformData{
        .centerRelativeAndMetrics = {
            centerRelative.x,
            centerRelative.y,
            centerRelative.z,
            metersPerPixel,
        },
        .rightAndSpan = {
            right.x,
            right.y,
            right.z,
            halfSpanX,
        },
        .upAndSpan = {
            up.x,
            up.y,
            up.z,
            halfSpanY,
        },
        .forwardAndDepth = {
            forward.x,
            forward.y,
            forward.z,
            halfDepth,
        },
        .viewport = {
            widthPx,
            heightPx,
            maxPointSizePx,
            enabledFeatures_.largePoints ? 1.0f : 0.0f,
        },
        .primaryLightPositionRelativeAndFlags = primaryLightPositionRelativeAndFlags,
    };

    return UploadBytes(&uniformData, sizeof(uniformData), sceneUniformBuffer_);
}

// Refreshes compute descriptor sets after scene-buffer replacement so compaction kernels read the
// current input/output/indirect buffers.
bool SolarLabVulkanRenderer::UpdateComputeDescriptorSetsLocked() {
    if (!computeCompactionEnabled_ || computeDescriptorSetLayout_ == VK_NULL_HANDLE) {
        return true;
    }

    // Each compute variant binds the same five-buffer layout: uniforms, source state, compacted
    // output, indirect draw command, and auxiliary counters.
    auto updateSet = [this](VkDescriptorSet set, const GpuBuffer& source, const GpuBuffer& output, const GpuBuffer& indirect, const GpuBuffer& aux) {
        const VkDescriptorBufferInfo uniformInfo{
            .buffer = sceneUniformBuffer_.buffer,
            .offset = 0,
            .range = sizeof(SceneUniformData),
        };
        const VkDescriptorBufferInfo sourceInfo{
            .buffer = source.buffer,
            .offset = 0,
            .range = source.sizeBytes,
        };
        const VkDescriptorBufferInfo outputInfo{
            .buffer = output.buffer,
            .offset = 0,
            .range = output.sizeBytes,
        };
        const VkDescriptorBufferInfo indirectInfo{
            .buffer = indirect.buffer,
            .offset = 0,
            .range = indirect.sizeBytes,
        };
        const VkDescriptorBufferInfo auxInfo{
            .buffer = aux.buffer,
            .offset = 0,
            .range = aux.sizeBytes,
        };
        const std::array<VkWriteDescriptorSet, 5> writes = {{
            {
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = set,
                .dstBinding = 0,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                .pImageInfo = nullptr,
                .pBufferInfo = &uniformInfo,
                .pTexelBufferView = nullptr,
            },
            {
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = set,
                .dstBinding = 1,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .pImageInfo = nullptr,
                .pBufferInfo = &sourceInfo,
                .pTexelBufferView = nullptr,
            },
            {
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = set,
                .dstBinding = 2,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .pImageInfo = nullptr,
                .pBufferInfo = &outputInfo,
                .pTexelBufferView = nullptr,
            },
            {
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = set,
                .dstBinding = 3,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .pImageInfo = nullptr,
                .pBufferInfo = &indirectInfo,
                .pTexelBufferView = nullptr,
            },
            {
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = set,
                .dstBinding = 4,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .pImageInfo = nullptr,
                .pBufferInfo = &auxInfo,
                .pTexelBufferView = nullptr,
            },
        }};
        vkUpdateDescriptorSets(device_, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    };

    // Medium and far tracer compaction can be enabled independently based on scene scale and
    // device capabilities.
    if (sceneGpuStreams_.tracerMediumCompute.enabled) {
        if (tracerMediumComputeDescriptorSet_ == VK_NULL_HANDLE ||
            sceneGpuStreams_.tracerMediumCompute.sourceStateBuffer.buffer == VK_NULL_HANDLE ||
            sceneGpuStreams_.tracerMediumCompute.outputVertexBuffer.buffer == VK_NULL_HANDLE ||
            sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer.buffer == VK_NULL_HANDLE) {
            SetError("Medium tracer compute descriptors could not be updated because one or more buffers were missing.");
            return false;
        }
        updateSet(
            tracerMediumComputeDescriptorSet_,
            sceneGpuStreams_.tracerMediumCompute.sourceStateBuffer,
            sceneGpuStreams_.tracerMediumCompute.outputVertexBuffer,
            sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer,
            sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer);
    }

    // Far tracers additionally bind the tile counter buffer so the density pass can bin output
    // before emitting indirect draw commands.
    if (sceneGpuStreams_.tracerFarCompute.enabled) {
        if (tracerFarComputeDescriptorSet_ == VK_NULL_HANDLE ||
            sceneGpuStreams_.tracerFarCompute.sourceStateBuffer.buffer == VK_NULL_HANDLE ||
            sceneGpuStreams_.tracerFarCompute.outputVertexBuffer.buffer == VK_NULL_HANDLE ||
            sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer.buffer == VK_NULL_HANDLE ||
            sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.buffer == VK_NULL_HANDLE) {
            SetError("Far tracer compute descriptors could not be updated because one or more buffers were missing.");
            return false;
        }
        updateSet(
            tracerFarComputeDescriptorSet_,
            sceneGpuStreams_.tracerFarCompute.sourceStateBuffer,
            sceneGpuStreams_.tracerFarCompute.outputVertexBuffer,
            sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer,
            sceneGpuStreams_.tracerFarCompute.tileCounterBuffer);
    }

    return true;
}

bool SolarLabVulkanRenderer::RecordComputePassLocked(VkCommandBuffer commandBuffer) {
    if (!computeCompactionEnabled_) {
        return true;
    }
    if (commandBuffer == VK_NULL_HANDLE) {
        SetError("Cannot record compute work into a null command buffer.");
        return false;
    }
    if (computePipelineLayout_ == VK_NULL_HANDLE) {
        return true;
    }

    const VkMemoryBarrier hostToComputeBarrier{
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
    };
    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        1,
        &hostToComputeBarrier,
        0,
        nullptr,
        0,
        nullptr);

    const bool runMedium = sceneGpuStreams_.tracerMediumCompute.enabled && mediumComputePipeline_ != VK_NULL_HANDLE;
    const bool runFar = sceneGpuStreams_.tracerFarCompute.enabled && farComputePipeline_ != VK_NULL_HANDLE;
    if (!runMedium && !runFar) {
        return true;
    }

    const auto initialIndirect = MakeInitialIndirectCommand();
    if (runMedium) {
        vkCmdUpdateBuffer(
            commandBuffer,
            sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer.buffer,
            0,
            sizeof(initialIndirect),
            &initialIndirect);
    }
    if (runFar) {
        vkCmdUpdateBuffer(
            commandBuffer,
            sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer.buffer,
            0,
            sizeof(initialIndirect),
            &initialIndirect);
        if (sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.buffer != VK_NULL_HANDLE) {
            vkCmdFillBuffer(
                commandBuffer,
                sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.buffer,
                0,
                sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.sizeBytes,
                0U);
        }
        if (sceneGpuStreams_.tracerFarCompute.outputVertexBuffer.buffer != VK_NULL_HANDLE) {
            vkCmdFillBuffer(
                commandBuffer,
                sceneGpuStreams_.tracerFarCompute.outputVertexBuffer.buffer,
                0,
                sceneGpuStreams_.tracerFarCompute.outputVertexBuffer.sizeBytes,
                0U);
        }
    }

    std::vector<VkBufferMemoryBarrier> transferToComputeBarriers;
    if (runMedium) {
        transferToComputeBarriers.push_back(VkBufferMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer.buffer,
            .offset = 0,
            .size = sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer.sizeBytes,
        });
    }
    if (runFar) {
        transferToComputeBarriers.push_back(VkBufferMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer.buffer,
            .offset = 0,
            .size = sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer.sizeBytes,
        });
        if (sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.buffer != VK_NULL_HANDLE) {
            transferToComputeBarriers.push_back(VkBufferMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .buffer = sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.buffer,
                .offset = 0,
                .size = sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.sizeBytes,
            });
        }
        if (sceneGpuStreams_.tracerFarCompute.outputVertexBuffer.buffer != VK_NULL_HANDLE) {
            transferToComputeBarriers.push_back(VkBufferMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .buffer = sceneGpuStreams_.tracerFarCompute.outputVertexBuffer.buffer,
                .offset = 0,
                .size = sceneGpuStreams_.tracerFarCompute.outputVertexBuffer.sizeBytes,
            });
        }
    }
    if (!transferToComputeBarriers.empty()) {
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            nullptr,
            static_cast<uint32_t>(transferToComputeBarriers.size()),
            transferToComputeBarriers.data(),
            0,
            nullptr);
    }

    if (runMedium) {
        const ComputePushConstants pushConstants{.sourceCount = sceneGpuStreams_.tracerMediumCompute.sourceVertexCount};
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mediumComputePipeline_);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, computePipelineLayout_, 0, 1, &tracerMediumComputeDescriptorSet_, 0, nullptr);
        vkCmdPushConstants(commandBuffer, computePipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
        vkCmdDispatch(commandBuffer, sceneGpuStreams_.tracerMediumCompute.dispatchGroupCountX, 1, 1);
    }
    if (runFar) {
        const ComputePushConstants pushConstants{
            .sourceCount = sceneGpuStreams_.tracerFarCompute.sourceVertexCount,
            .tileCounterCount = sceneGpuStreams_.tracerFarCompute.tileCounterCount,
        };
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, farComputePipeline_);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, computePipelineLayout_, 0, 1, &tracerFarComputeDescriptorSet_, 0, nullptr);
        vkCmdPushConstants(commandBuffer, computePipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants), &pushConstants);
        vkCmdDispatch(commandBuffer, sceneGpuStreams_.tracerFarCompute.dispatchGroupCountX, 1, 1);
    }

    /**
     * --- Compute-to-Graphics Synchronization ---
     * 
     * The compute shaders above write to output vertex buffers and an indirect draw
     * command buffer. We must ensure these writes are globally visible before the
     * graphics pipeline attempts to read them as vertex data or indirect parameters.
     * 
     * Pipeline Barrier:
     * - srcStage: COMPUTE_SHADER
     * - dstStage: DRAW_INDIRECT (for the command buffer) and VERTEX_INPUT (for the vertex data)
     * - srcAccess: SHADER_WRITE
     * - dstAccess: INDIRECT_COMMAND_READ and VERTEX_ATTRIBUTE_READ
     */
    const VkMemoryBarrier computeToGraphicsBarrier{
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT,
    };
    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
        0,
        1,
        &computeToGraphicsBarrier,
        0,
        nullptr,
        0,
        nullptr);

    std::vector<VkBufferMemoryBarrier> computeToTransferBarriers;
    if (runMedium && sceneGpuStreams_.tracerMediumCompute.indirectReadbackBuffer.buffer != VK_NULL_HANDLE) {
        computeToTransferBarriers.push_back(VkBufferMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer.buffer,
            .offset = 0,
            .size = sizeof(VkDrawIndirectCommand),
        });
    }
    if (runFar && sceneGpuStreams_.tracerFarCompute.indirectReadbackBuffer.buffer != VK_NULL_HANDLE) {
        computeToTransferBarriers.push_back(VkBufferMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer.buffer,
            .offset = 0,
            .size = sizeof(VkDrawIndirectCommand),
        });
    }
    if (!computeToTransferBarriers.empty()) {
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            0,
            nullptr,
            static_cast<uint32_t>(computeToTransferBarriers.size()),
            computeToTransferBarriers.data(),
            0,
            nullptr);
    }

    std::vector<VkBufferMemoryBarrier> transferToHostBarriers;
    if (runMedium && sceneGpuStreams_.tracerMediumCompute.indirectReadbackBuffer.buffer != VK_NULL_HANDLE) {
        const VkBufferCopy copyRegion{
            .srcOffset = 0,
            .dstOffset = 0,
            .size = sizeof(VkDrawIndirectCommand),
        };
        vkCmdCopyBuffer(
            commandBuffer,
            sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer.buffer,
            sceneGpuStreams_.tracerMediumCompute.indirectReadbackBuffer.buffer,
            1,
            &copyRegion);
        transferToHostBarriers.push_back(VkBufferMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = sceneGpuStreams_.tracerMediumCompute.indirectReadbackBuffer.buffer,
            .offset = 0,
            .size = sizeof(VkDrawIndirectCommand),
        });
    }
    if (runFar && sceneGpuStreams_.tracerFarCompute.indirectReadbackBuffer.buffer != VK_NULL_HANDLE) {
        const VkBufferCopy copyRegion{
            .srcOffset = 0,
            .dstOffset = 0,
            .size = sizeof(VkDrawIndirectCommand),
        };
        vkCmdCopyBuffer(
            commandBuffer,
            sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer.buffer,
            sceneGpuStreams_.tracerFarCompute.indirectReadbackBuffer.buffer,
            1,
            &copyRegion);
        transferToHostBarriers.push_back(VkBufferMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = sceneGpuStreams_.tracerFarCompute.indirectReadbackBuffer.buffer,
            .offset = 0,
            .size = sizeof(VkDrawIndirectCommand),
        });
    }
    if (runFar &&
        sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.buffer != VK_NULL_HANDLE &&
        sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer.buffer != VK_NULL_HANDLE) {
        const VkBufferCopy copyRegion{
            .srcOffset = 0,
            .dstOffset = 0,
            .size = sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.sizeBytes,
        };
        vkCmdCopyBuffer(
            commandBuffer,
            sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.buffer,
            sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer.buffer,
            1,
            &copyRegion);
        transferToHostBarriers.push_back(VkBufferMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer.buffer,
            .offset = 0,
            .size = sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer.sizeBytes,
        });
    }
    if (!transferToHostBarriers.empty()) {
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT,
            0,
            0,
            nullptr,
            static_cast<uint32_t>(transferToHostBarriers.size()),
            transferToHostBarriers.data(),
            0,
            nullptr);
    }

    return true;
}

bool SolarLabVulkanRenderer::RecordSceneBindingsLocked(VkCommandBuffer commandBuffer) {
    if (commandBuffer == VK_NULL_HANDLE) {
        SetError("Cannot record scene bindings into a null command buffer.");
        return false;
    }
    if (graphicsPipelineLayout_ == VK_NULL_HANDLE || sceneDescriptorSet_ == VK_NULL_HANDLE) {
        SetError("Graphics pipelines cannot be recorded before descriptor resources are ready.");
        return false;
    }

    const VkViewport viewport{
        .x = 0.0f,
        .y = 0.0f,
        .width = static_cast<float>(swapchainExtent_.width),
        .height = static_cast<float>(swapchainExtent_.height),
        .minDepth = 0.0f,
        .maxDepth = 1.0f,
    };
    const VkRect2D scissor{
        .offset = {0, 0},
        .extent = swapchainExtent_,
    };
    vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
    vkCmdSetScissor(commandBuffer, 0, 1, &scissor);

    auto bindAndDraw = [this, commandBuffer](VkPipeline pipeline, const DrawStreamBuffers& stream) {
        if (pipeline == VK_NULL_HANDLE || stream.vertexCount == 0 || stream.vertexBuffer.buffer == VK_NULL_HANDLE) {
            return;
        }
        const VkBuffer buffer = stream.vertexBuffer.buffer;
        const VkDeviceSize offset = 0;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipelineLayout_, 0, 1, &sceneDescriptorSet_, 0, nullptr);
        vkCmdBindVertexBuffers(commandBuffer, 0, 1, &buffer, &offset);
        vkCmdDraw(commandBuffer, stream.vertexCount, 1, 0, 0);
    };

    auto bindAndDrawBillboards = [this, commandBuffer](const DrawStreamBuffers& stream) {
        if (billboardPipeline_ == VK_NULL_HANDLE ||
            stream.vertexCount == 0 ||
            stream.vertexBuffer.buffer == VK_NULL_HANDLE) {
            return;
        }
        constexpr uint32_t kVerticesPerBillboard = 6U;
        const VkBuffer buffer = stream.vertexBuffer.buffer;
        const VkDeviceSize offset = 0;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, billboardPipeline_);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            graphicsPipelineLayout_,
            0,
            1,
            &sceneDescriptorSet_,
            0,
            nullptr);
        vkCmdBindVertexBuffers(commandBuffer, 0, 1, &buffer, &offset);
        vkCmdDraw(commandBuffer, kVerticesPerBillboard, stream.vertexCount, 0, 0);
    };

    auto bindAndDrawIndirect = [this, commandBuffer](VkPipeline pipeline, const ComputeDrawStreamBuffers& stream) {
        if (pipeline == VK_NULL_HANDLE || !stream.enabled || stream.outputVertexBuffer.buffer == VK_NULL_HANDLE || stream.indirectCommandBuffer.buffer == VK_NULL_HANDLE) {
            return;
        }
        const VkBuffer buffer = stream.outputVertexBuffer.buffer;
        const VkDeviceSize offset = 0;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipelineLayout_, 0, 1, &sceneDescriptorSet_, 0, nullptr);
        vkCmdBindVertexBuffers(commandBuffer, 0, 1, &buffer, &offset);
        vkCmdDrawIndirect(commandBuffer, stream.indirectCommandBuffer.buffer, 0, 1, sizeof(VkDrawIndirectCommand));
    };

    auto bindAndDrawFixedCount = [this, commandBuffer](VkPipeline pipeline, const ComputeDrawStreamBuffers& stream) {
        if (pipeline == VK_NULL_HANDLE || !stream.enabled || stream.outputVertexBuffer.buffer == VK_NULL_HANDLE || stream.outputVertexCapacity == 0U) {
            return;
        }
        const VkBuffer buffer = stream.outputVertexBuffer.buffer;
        const VkDeviceSize offset = 0;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipelineLayout_, 0, 1, &sceneDescriptorSet_, 0, nullptr);
        vkCmdBindVertexBuffers(commandBuffer, 0, 1, &buffer, &offset);
        vkCmdDraw(commandBuffer, stream.outputVertexCapacity, 1, 0, 0);
    };

    // Draw each distance band once, back to front. A historical merge left
    // authoritative, near, and medium streams duplicated around the far pass;
    // with depth writes enabled that only repeated vertex/fragment work and
    // compounded translucent edges.
    if (sceneGpuStreams_.tracerFarCompute.enabled) {
        bindAndDrawFixedCount(farDensityPipeline_, sceneGpuStreams_.tracerFarCompute);
    } else {
        bindAndDraw(farDensityPipeline_, sceneGpuStreams_.tracerFar);
    }
    if (sceneGpuStreams_.tracerMediumCompute.enabled) {
        bindAndDrawIndirect(mediumPointPipeline_, sceneGpuStreams_.tracerMediumCompute);
    } else {
        bindAndDraw(mediumPointPipeline_, sceneGpuStreams_.tracerMedium);
    }
    bindAndDrawBillboards(sceneGpuStreams_.tracerNear);
    bindAndDrawBillboards(sceneGpuStreams_.authoritative);

    if (trailPipeline_ != VK_NULL_HANDLE && sceneGpuStreams_.trails.vertexCount > 1 && sceneGpuStreams_.trails.vertexBuffer.buffer != VK_NULL_HANDLE) {
        const VkBuffer trailBuffer = sceneGpuStreams_.trails.vertexBuffer.buffer;
        const VkDeviceSize trailOffset = 0;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, trailPipeline_);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipelineLayout_, 0, 1, &sceneDescriptorSet_, 0, nullptr);
        vkCmdBindVertexBuffers(commandBuffer, 0, 1, &trailBuffer, &trailOffset);
        uint32_t firstVertex = 0;
        for (uint32_t stripVertexCount : sceneGpuStreams_.trailStripVertexCounts) {
            if (stripVertexCount >= 2U) {
                vkCmdDraw(commandBuffer, stripVertexCount, 1, firstVertex, 0);
                firstVertex += stripVertexCount;
            }
        }
    }

    return true;
}

void SolarLabVulkanRenderer::DestroyGpuBuffer(GpuBuffer& buffer) {
    if (device_ == VK_NULL_HANDLE) {
        buffer = GpuBuffer{};
        return;
    }
    if (buffer.buffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, buffer.buffer, nullptr);
    }
    if (buffer.memory != VK_NULL_HANDLE) {
        vkFreeMemory(device_, buffer.memory, nullptr);
    }
    buffer = GpuBuffer{};
}

void SolarLabVulkanRenderer::DestroySceneGpuStreams() {
    DestroyGpuBuffer(sceneGpuStreams_.authoritativeInfluenceBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.authoritative.vertexBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerNear.vertexBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerMedium.vertexBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerFar.vertexBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.trails.vertexBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerMediumCompute.sourceStateBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerMediumCompute.outputVertexBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerMediumCompute.indirectCommandBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerMediumCompute.indirectReadbackBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerFarCompute.sourceStateBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerFarCompute.outputVertexBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerFarCompute.indirectCommandBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerFarCompute.indirectReadbackBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerFarCompute.tileCounterBuffer);
    DestroyGpuBuffer(sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer);
    sceneGpuStreams_ = SceneGpuStreams{};
    commandBuffersRevision_ = -1;
}

void SolarLabVulkanRenderer::RefreshCompactionVisibleCountsFromReadbackLocked() {
    auto refreshStream = [this](ComputeDrawStreamBuffers& stream) {
        stream.visibleVertexCount = 0;
        stream.visibleVertexCountValid = false;
        if (!stream.enabled || stream.indirectReadbackBuffer.memory == VK_NULL_HANDLE || device_ == VK_NULL_HANDLE) {
            return;
        }
        if (stream.indirectReadbackBuffer.sizeBytes < sizeof(VkDrawIndirectCommand)) {
            return;
        }

        void* mapped = nullptr;
        if (vkMapMemory(device_, stream.indirectReadbackBuffer.memory, 0, sizeof(VkDrawIndirectCommand), 0, &mapped) != VK_SUCCESS || mapped == nullptr) {
            return;
        }
        const auto command = *static_cast<const VkDrawIndirectCommand*>(mapped);
        vkUnmapMemory(device_, stream.indirectReadbackBuffer.memory);

        stream.visibleVertexCount = command.vertexCount;
        stream.visibleVertexCountValid = true;
    };

    refreshStream(sceneGpuStreams_.tracerMediumCompute);
    refreshStream(sceneGpuStreams_.tracerFarCompute);

    sceneGpuStreams_.tracerFarCompute.activeTileCount = 0;
    sceneGpuStreams_.tracerFarCompute.peakTileOccupancy = 0;
    sceneGpuStreams_.tracerFarCompute.overflowVertexCount = 0;
    sceneGpuStreams_.tracerFarCompute.tileStatsValid = false;
    if (sceneGpuStreams_.tracerFarCompute.enabled &&
        sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer.memory != VK_NULL_HANDLE &&
        sceneGpuStreams_.tracerFarCompute.tileCounterCount > 0 &&
        device_ != VK_NULL_HANDLE) {
        const size_t tileCount = static_cast<size_t>(sceneGpuStreams_.tracerFarCompute.tileCounterCount);
        const size_t readbackWords = tileCount + 1U;
        const size_t readbackBytes = readbackWords * sizeof(uint32_t);
        if (sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer.sizeBytes >= readbackBytes) {
            void* mapped = nullptr;
            if (vkMapMemory(device_, sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer.memory, 0, readbackBytes, 0, &mapped) == VK_SUCCESS && mapped != nullptr) {
                const auto* counters = static_cast<const uint32_t*>(mapped);
                uint32_t activeTileCount = 0;
                uint32_t peakTileOccupancy = 0;
                for (size_t index = 0; index < tileCount; ++index) {
                    const uint32_t counter = counters[index];
                    if (counter > 0U) {
                        ++activeTileCount;
                        peakTileOccupancy = std::max(peakTileOccupancy, counter);
                    }
                }
                const uint32_t overflowVertexCount = counters[tileCount];
                vkUnmapMemory(device_, sceneGpuStreams_.tracerFarCompute.tileCounterReadbackBuffer.memory);
                sceneGpuStreams_.tracerFarCompute.activeTileCount = activeTileCount;
                sceneGpuStreams_.tracerFarCompute.peakTileOccupancy = peakTileOccupancy;
                sceneGpuStreams_.tracerFarCompute.overflowVertexCount = overflowVertexCount;
                sceneGpuStreams_.tracerFarCompute.tileStatsValid = true;
            }
        }
    }

    sceneSummaryCache_ = BuildSceneSummaryLocked();
}

void SolarLabVulkanRenderer::DestroyGraphicsPipelines() {
    if (device_ == VK_NULL_HANDLE) {
        billboardPipeline_ = VK_NULL_HANDLE;
        mediumPointPipeline_ = VK_NULL_HANDLE;
        farDensityPipeline_ = VK_NULL_HANDLE;
        trailPipeline_ = VK_NULL_HANDLE;
        return;
    }
    if (billboardPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device_, billboardPipeline_, nullptr);
        billboardPipeline_ = VK_NULL_HANDLE;
    }
    if (mediumPointPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device_, mediumPointPipeline_, nullptr);
        mediumPointPipeline_ = VK_NULL_HANDLE;
    }
    if (farDensityPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device_, farDensityPipeline_, nullptr);
        farDensityPipeline_ = VK_NULL_HANDLE;
    }
    if (trailPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device_, trailPipeline_, nullptr);
        trailPipeline_ = VK_NULL_HANDLE;
    }
}

void SolarLabVulkanRenderer::DestroyComputePipelines() {
    computeCompactionEnabled_ = false;
    if (device_ == VK_NULL_HANDLE) {
        mediumComputePipeline_ = VK_NULL_HANDLE;
        farComputePipeline_ = VK_NULL_HANDLE;
        return;
    }
    if (mediumComputePipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device_, mediumComputePipeline_, nullptr);
        mediumComputePipeline_ = VK_NULL_HANDLE;
    }
    if (farComputePipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device_, farComputePipeline_, nullptr);
        farComputePipeline_ = VK_NULL_HANDLE;
    }
}

void SolarLabVulkanRenderer::DestroyDescriptorResources() {
    DestroyGraphicsPipelines();
    DestroyComputePipelines();
    DestroyGpuBuffer(sceneUniformBuffer_);

    if (device_ == VK_NULL_HANDLE) {
        sceneDescriptorSet_ = VK_NULL_HANDLE;
        descriptorPool_ = VK_NULL_HANDLE;
        sceneDescriptorSetLayout_ = VK_NULL_HANDLE;
        graphicsPipelineLayout_ = VK_NULL_HANDLE;
        tracerMediumComputeDescriptorSet_ = VK_NULL_HANDLE;
        tracerFarComputeDescriptorSet_ = VK_NULL_HANDLE;
        computeDescriptorPool_ = VK_NULL_HANDLE;
        computeDescriptorSetLayout_ = VK_NULL_HANDLE;
        computePipelineLayout_ = VK_NULL_HANDLE;
        return;
    }

    if (computePipelineLayout_ != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device_, computePipelineLayout_, nullptr);
        computePipelineLayout_ = VK_NULL_HANDLE;
    }
    if (computeDescriptorPool_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(device_, computeDescriptorPool_, nullptr);
        computeDescriptorPool_ = VK_NULL_HANDLE;
    }
    if (computeDescriptorSetLayout_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device_, computeDescriptorSetLayout_, nullptr);
        computeDescriptorSetLayout_ = VK_NULL_HANDLE;
    }
    tracerMediumComputeDescriptorSet_ = VK_NULL_HANDLE;
    tracerFarComputeDescriptorSet_ = VK_NULL_HANDLE;

    if (graphicsPipelineLayout_ != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device_, graphicsPipelineLayout_, nullptr);
        graphicsPipelineLayout_ = VK_NULL_HANDLE;
    }
    if (descriptorPool_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
        descriptorPool_ = VK_NULL_HANDLE;
    }
    if (sceneDescriptorSetLayout_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device_, sceneDescriptorSetLayout_, nullptr);
        sceneDescriptorSetLayout_ = VK_NULL_HANDLE;
    }
    sceneDescriptorSet_ = VK_NULL_HANDLE;
}

bool SolarLabVulkanRenderer::EnsureBufferWithMemoryProperties(
    VkDeviceSize sizeBytes,
    VkBufferUsageFlags usage,
    VkMemoryPropertyFlags memoryProperties,
    const char* label,
    bool reportErrors,
    GpuBuffer& buffer) {
    if (buffer.buffer != VK_NULL_HANDLE && buffer.sizeBytes >= sizeBytes && buffer.usage == usage) {
        return true;
    }

    DestroyGpuBuffer(buffer);

    const VkBufferCreateInfo bufferCreateInfo{
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .size = sizeBytes,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
    };
    if (vkCreateBuffer(device_, &bufferCreateInfo, nullptr, &buffer.buffer) != VK_SUCCESS) {
        if (reportErrors) {
            SetError(std::string("vkCreateBuffer failed for ") + (label != nullptr ? label : "unnamed stream") + ".");
        }
        return false;
    }

    VkMemoryRequirements memoryRequirements{};
    vkGetBufferMemoryRequirements(device_, buffer.buffer, &memoryRequirements);

    const uint32_t memoryTypeIndex = FindMemoryType(memoryRequirements.memoryTypeBits, memoryProperties);
    if (memoryTypeIndex == UINT32_MAX) {
        if (reportErrors) {
            SetError(std::string("No Vulkan memory type with required properties found for ") + (label != nullptr ? label : "unnamed stream") + ".");
        }
        DestroyGpuBuffer(buffer);
        return false;
    }

    const VkMemoryAllocateInfo allocateInfo{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = nullptr,
        .allocationSize = memoryRequirements.size,
        .memoryTypeIndex = memoryTypeIndex,
    };
    if (vkAllocateMemory(device_, &allocateInfo, nullptr, &buffer.memory) != VK_SUCCESS) {
        if (reportErrors) {
            SetError(std::string("vkAllocateMemory failed for ") + (label != nullptr ? label : "unnamed stream") + ".");
        }
        DestroyGpuBuffer(buffer);
        return false;
    }

    if (vkBindBufferMemory(device_, buffer.buffer, buffer.memory, 0) != VK_SUCCESS) {
        if (reportErrors) {
            SetError(std::string("vkBindBufferMemory failed for ") + (label != nullptr ? label : "unnamed stream") + ".");
        }
        DestroyGpuBuffer(buffer);
        return false;
    }

    buffer.sizeBytes = sizeBytes;
    buffer.usage = usage;
    buffer.debugLabel = label;
    return true;
}

bool SolarLabVulkanRenderer::EnsureHostVisibleBuffer(VkDeviceSize sizeBytes, VkBufferUsageFlags usage, const char* label, GpuBuffer& buffer) {
    return EnsureBufferWithMemoryProperties(
        sizeBytes,
        usage,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        label,
        true,
        buffer);
}

bool SolarLabVulkanRenderer::EnsureDeviceLocalBuffer(VkDeviceSize sizeBytes, VkBufferUsageFlags usage, const char* label, bool reportErrors, GpuBuffer& buffer) {
    return EnsureBufferWithMemoryProperties(
        sizeBytes,
        usage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        label,
        reportErrors,
        buffer);
}

// Copies staged bytes into device-local buffers with a one-shot command buffer, optionally
// surfacing detailed Vulkan errors to callers that are in user-visible setup paths.
bool SolarLabVulkanRenderer::CopyBufferBytes(const GpuBuffer& source, const GpuBuffer& target, VkDeviceSize sizeBytes, bool reportErrors) {
    if (sizeBytes == 0) {
        return true;
    }
    if (commandPool_ == VK_NULL_HANDLE || graphicsQueue_ == VK_NULL_HANDLE || source.buffer == VK_NULL_HANDLE || target.buffer == VK_NULL_HANDLE) {
        return false;
    }

    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    // Use a transient primary command buffer so uploads do not depend on frame command buffers or
    // swapchain lifetime.
    const VkCommandBufferAllocateInfo allocateInfo{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .pNext = nullptr,
        .commandPool = commandPool_,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    if (vkAllocateCommandBuffers(device_, &allocateInfo, &commandBuffer) != VK_SUCCESS || commandBuffer == VK_NULL_HANDLE) {
        if (reportErrors) {
            SetError("vkAllocateCommandBuffers failed for staged tracer upload copy.");
        }
        return false;
    }

    const VkCommandBufferBeginInfo beginInfo{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
        .pInheritanceInfo = nullptr,
    };
    if (vkBeginCommandBuffer(commandBuffer, &beginInfo) != VK_SUCCESS) {
        vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
        if (reportErrors) {
            SetError("vkBeginCommandBuffer failed for staged tracer upload copy.");
        }
        return false;
    }

    const VkBufferCopy copyRegion{
        .srcOffset = 0,
        .dstOffset = 0,
        .size = sizeBytes,
    };
    vkCmdCopyBuffer(commandBuffer, source.buffer, target.buffer, 1, &copyRegion);

    // Make transfer writes visible to vertex, graphics shader, and compute readers that may
    // consume the uploaded buffer in the next draw or compaction pass.
    const VkBufferMemoryBarrier transferBarrier{
        .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_SHADER_READ_BIT,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .buffer = target.buffer,
        .offset = 0,
        .size = sizeBytes,
    };
    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        1,
        &transferBarrier,
        0,
        nullptr);

    if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
        vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
        if (reportErrors) {
            SetError("vkEndCommandBuffer failed for staged tracer upload copy.");
        }
        return false;
    }

    // Submit synchronously because these uploads run during resource refresh; callers can safely
    // use the target buffer after this function returns.
    const VkSubmitInfo submitInfo{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .pNext = nullptr,
        .waitSemaphoreCount = 0,
        .pWaitSemaphores = nullptr,
        .pWaitDstStageMask = nullptr,
        .commandBufferCount = 1,
        .pCommandBuffers = &commandBuffer,
        .signalSemaphoreCount = 0,
        .pSignalSemaphores = nullptr,
    };
    const VkResult submitResult = vkQueueSubmit(graphicsQueue_, 1, &submitInfo, VK_NULL_HANDLE);
    if (submitResult == VK_SUCCESS) {
        const VkResult waitResult = vkQueueWaitIdle(graphicsQueue_);
        if (waitResult != VK_SUCCESS && reportErrors) {
            SetError("vkQueueWaitIdle failed for staged tracer upload copy.");
        }
        if (waitResult != VK_SUCCESS) {
            vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
            return false;
        }
    } else {
        if (reportErrors) {
            SetError("vkQueueSubmit failed for staged tracer upload copy.");
        }
        vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
        return false;
    }

    vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
    return true;
}

bool SolarLabVulkanRenderer::TryUploadDeviceLocalWithStaging(const void* data, size_t sizeBytes, VkBufferUsageFlags usage, const char* label, GpuBuffer& target) {
    if (sizeBytes == 0) {
        DestroyGpuBuffer(target);
        return true;
    }
    if (commandPool_ == VK_NULL_HANDLE || graphicsQueue_ == VK_NULL_HANDLE) {
        return false;
    }

    if (!EnsureDeviceLocalBuffer(
            static_cast<VkDeviceSize>(sizeBytes),
            usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            label,
            false,
            target)) {
        return false;
    }

    GpuBuffer stagingBuffer;
    if (!EnsureBufferWithMemoryProperties(
            static_cast<VkDeviceSize>(sizeBytes),
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            label,
            false,
            stagingBuffer)) {
        DestroyGpuBuffer(target);
        return false;
    }

    const bool uploaded = UploadBytesInternal(data, sizeBytes, stagingBuffer, false);
    const bool copied = uploaded && CopyBufferBytes(stagingBuffer, target, static_cast<VkDeviceSize>(sizeBytes), false);
    DestroyGpuBuffer(stagingBuffer);
    if (!copied) {
        DestroyGpuBuffer(target);
    }
    return copied;
}

bool SolarLabVulkanRenderer::UploadBytesInternal(const void* data, size_t sizeBytes, const GpuBuffer& buffer, bool reportErrors) {
    if (sizeBytes == 0) {
        return true;
    }
    if (buffer.memory == VK_NULL_HANDLE || buffer.sizeBytes < sizeBytes) {
        if (reportErrors) {
            SetError("Upload requested for an invalid or undersized Vulkan buffer.");
        }
        return false;
    }

    void* mapped = nullptr;
    if (vkMapMemory(device_, buffer.memory, 0, static_cast<VkDeviceSize>(sizeBytes), 0, &mapped) != VK_SUCCESS || mapped == nullptr) {
        if (reportErrors) {
            SetError(std::string("vkMapMemory failed for ") + (buffer.debugLabel != nullptr ? buffer.debugLabel : "unnamed stream") + ".");
        }
        return false;
    }
    std::memcpy(mapped, data, sizeBytes);
    vkUnmapMemory(device_, buffer.memory);
    return true;
}

bool SolarLabVulkanRenderer::UploadBytes(const void* data, size_t sizeBytes, GpuBuffer& buffer) {
    return UploadBytesInternal(data, sizeBytes, buffer, true);
}

uint32_t SolarLabVulkanRenderer::FindMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memoryProperties);
    for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
        const bool matchesType = (typeFilter & (1U << index)) != 0U;
        const bool matchesProperties = (memoryProperties.memoryTypes[index].propertyFlags & properties) == properties;
        if (matchesType && matchesProperties) {
            return index;
        }
    }
    return UINT32_MAX;
}

std::string SolarLabVulkanRenderer::BuildSceneSummaryLocked() const {
    std::ostringstream out;
    out << "rev=" << uploadStats_.sourceRevision
        << " A=" << uploadStats_.authoritativeCount
        << "/AI=" << sceneGpuStreams_.authoritativeInfluenceCount
        << " TN=" << uploadStats_.tracerNearCount
        << " TM=" << uploadStats_.tracerMediumCount
        << " TF=" << uploadStats_.tracerFarCount
        << " TL=" << uploadStats_.trailVertexCount
        << '/' << uploadStats_.trailStripCount
        << " bytes=" << uploadStats_.bytesUploaded
        << " paths=["
        << DrawPathName(sceneGpuStreams_.authoritative.path) << ','
        << DrawPathName(sceneGpuStreams_.tracerNear.path) << ','
        << DrawPathName(sceneGpuStreams_.tracerMedium.path) << ','
        << DrawPathName(sceneGpuStreams_.tracerFar.path) << ','
        << DrawPathName(sceneGpuStreams_.trails.path) << ']'
        << " compute=["
        << (sceneGpuStreams_.tracerMediumCompute.enabled ? "TM:" : "TM:-")
        << sceneGpuStreams_.tracerMediumCompute.dispatchGroupCountX
        << "/src="
        << (sceneGpuStreams_.tracerMediumCompute.sourceStateBuffer.buffer != VK_NULL_HANDLE ? "state" : "none")
        << "/vis="
        << (sceneGpuStreams_.tracerMediumCompute.visibleVertexCountValid ? std::to_string(sceneGpuStreams_.tracerMediumCompute.visibleVertexCount) : std::string("-"))
        << ','
        << (sceneGpuStreams_.tracerFarCompute.enabled ? "TF:" : "TF:-")
        << sceneGpuStreams_.tracerFarCompute.dispatchGroupCountX
        << "/src="
        << (sceneGpuStreams_.tracerFarCompute.sourceStateBuffer.buffer != VK_NULL_HANDLE ? "state" : "none")
        << "/cap="
        << sceneGpuStreams_.tracerFarCompute.outputVertexCapacity
        << "/tiles="
        << sceneGpuStreams_.tracerFarCompute.tileCounterCount
        << "/active="
        << (sceneGpuStreams_.tracerFarCompute.tileStatsValid ? std::to_string(sceneGpuStreams_.tracerFarCompute.activeTileCount) : std::string("-"))
        << "/peak="
        << (sceneGpuStreams_.tracerFarCompute.tileStatsValid ? std::to_string(sceneGpuStreams_.tracerFarCompute.peakTileOccupancy) : std::string("-"))
        << "/drop="
        << (sceneGpuStreams_.tracerFarCompute.tileStatsValid ? std::to_string(sceneGpuStreams_.tracerFarCompute.overflowVertexCount) : std::string("-"))
        << "/vis="
        << (sceneGpuStreams_.tracerFarCompute.visibleVertexCountValid ? std::to_string(sceneGpuStreams_.tracerFarCompute.visibleVertexCount) : std::string("-"))
        << ']'
        << " gp=["
        << (billboardPipeline_ != VK_NULL_HANDLE ? "bb" : "--") << ','
        << (mediumPointPipeline_ != VK_NULL_HANDLE ? "mp" : "--") << ','
        << (farDensityPipeline_ != VK_NULL_HANDLE ? "fp" : "--") << ','
        << (trailPipeline_ != VK_NULL_HANDLE ? "tr" : "--") << ']'
        << " cp=["
        << (mediumComputePipeline_ != VK_NULL_HANDLE ? "mc" : "--") << ','
        << (farComputePipeline_ != VK_NULL_HANDLE ? "fc" : "--") << ']'
        << " cam=[r=" << cameraViewRadiusM_
        << " yaw=" << cameraYawRadians_
        << " pitch=" << cameraPitchRadians_
        << "] origin=["
        << sceneBuffers_.sceneOriginX << ','
        << sceneBuffers_.sceneOriginY << ','
        << sceneBuffers_.sceneOriginZ << ']';
    return out.str();
}

const char* SolarLabVulkanRenderer::DrawPathName(DrawPath path) {
    switch (path) {
        case DrawPath::BillboardSprite:
            return "sprite";
        case DrawPath::CheapPointSprite:
            return "cheap-point";
        case DrawPath::DensityPoint:
            return "density-point";
        case DrawPath::ThinLineStrip:
            return "thin-line";
        case DrawPath::None:
        default:
            return "none";
    }
}

bool SolarLabVulkanRenderer::LoadShaderModuleFromAssets(const char* assetPath, VkShaderModule& shaderModule) {
    shaderModule = VK_NULL_HANDLE;
    if (assetManager_ == nullptr) {
        SetError("Cannot load shaders because the AAssetManager is unavailable.");
        return false;
    }

    AAsset* asset = AAssetManager_open(assetManager_, assetPath, AASSET_MODE_BUFFER);
    if (asset == nullptr) {
        SetError(std::string("Failed to open SPIR-V asset: ") + assetPath);
        return false;
    }

    const off_t length = AAsset_getLength(asset);
    if (length <= 0) {
        AAsset_close(asset);
        SetError(std::string("SPIR-V asset was empty: ") + assetPath);
        return false;
    }
    if ((length % 4) != 0) {
        AAsset_close(asset);
        SetError(std::string("SPIR-V asset length was not a multiple of 4 bytes: ") + assetPath);
        return false;
    }

    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    const int bytesRead = AAsset_read(asset, bytes.data(), length);
    AAsset_close(asset);
    if (bytesRead != length) {
        SetError(std::string("Failed to fully read SPIR-V asset: ") + assetPath);
        return false;
    }

    std::vector<uint32_t> words(bytes.size() / sizeof(uint32_t));
    std::memcpy(words.data(), bytes.data(), bytes.size());

    const VkShaderModuleCreateInfo createInfo{
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .codeSize = bytes.size(),
        .pCode = words.data(),
    };
    if (vkCreateShaderModule(device_, &createInfo, nullptr, &shaderModule) != VK_SUCCESS) {
        SetError(std::string("vkCreateShaderModule failed for asset: ") + assetPath);
        return false;
    }
    return true;
}

// Creates one graphics pipeline variant from packaged shader assets and caller-provided vertex
// layout, preserving a common viewport, blending, depth, and render-pass configuration.
bool SolarLabVulkanRenderer::CreateGraphicsPipeline(
    const char* label,
    const char* vertexShaderAssetPath,
    const char* fragmentShaderAssetPath,
    VkPrimitiveTopology topology,
    bool additiveBlending,
    const std::vector<VkVertexInputBindingDescription>& bindings,
    const std::vector<VkVertexInputAttributeDescription>& attributes,
    VkPipeline& pipeline) {
    VkShaderModule vertexShader = VK_NULL_HANDLE;
    VkShaderModule fragmentShader = VK_NULL_HANDLE;
    // Shader modules are short-lived construction inputs; the pipeline owns compiled state after
    // vkCreateGraphicsPipelines returns.
    if (!LoadShaderModuleFromAssets(vertexShaderAssetPath, vertexShader)) {
        return false;
    }
    if (!LoadShaderModuleFromAssets(fragmentShaderAssetPath, fragmentShader)) {
        vkDestroyShaderModule(device_, vertexShader, nullptr);
        return false;
    }

    const VkPipelineShaderStageCreateInfo shaderStages[] = {
        {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stage = VK_SHADER_STAGE_VERTEX_BIT,
            .module = vertexShader,
            .pName = "main",
            .pSpecializationInfo = nullptr,
        },
        {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
            .module = fragmentShader,
            .pName = "main",
            .pSpecializationInfo = nullptr,
        },
    };

    // Vertex input is the main shape that varies between billboard, trail, cheap-point, and
    // density-point pipelines.
    const VkPipelineVertexInputStateCreateInfo vertexInputState{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .vertexBindingDescriptionCount = static_cast<uint32_t>(bindings.size()),
        .pVertexBindingDescriptions = bindings.data(),
        .vertexAttributeDescriptionCount = static_cast<uint32_t>(attributes.size()),
        .pVertexAttributeDescriptions = attributes.data(),
    };

    const VkPipelineInputAssemblyStateCreateInfo inputAssemblyState{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .topology = topology,
        .primitiveRestartEnable = VK_FALSE,
    };

    const VkViewport dummyViewport{
        .x = 0.0f,
        .y = 0.0f,
        .width = static_cast<float>(std::max<uint32_t>(swapchainExtent_.width, 1U)),
        .height = static_cast<float>(std::max<uint32_t>(swapchainExtent_.height, 1U)),
        .minDepth = 0.0f,
        .maxDepth = 1.0f,
    };
    const VkRect2D dummyScissor{
        .offset = {0, 0},
        .extent = swapchainExtent_,
    };
    const VkPipelineViewportStateCreateInfo viewportState{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .viewportCount = 1,
        .pViewports = &dummyViewport,
        .scissorCount = 1,
        .pScissors = &dummyScissor,
    };

    const VkPipelineRasterizationStateCreateInfo rasterizationState{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .depthClampEnable = VK_FALSE,
        .rasterizerDiscardEnable = VK_FALSE,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
        .depthBiasEnable = VK_FALSE,
        .depthBiasConstantFactor = 0.0f,
        .depthBiasClamp = 0.0f,
        .depthBiasSlopeFactor = 0.0f,
        .lineWidth = 1.0f,
    };

    const VkPipelineMultisampleStateCreateInfo multisampleState{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
        .sampleShadingEnable = VK_FALSE,
        .minSampleShading = 1.0f,
        .pSampleMask = nullptr,
        .alphaToCoverageEnable = VK_FALSE,
        .alphaToOneEnable = VK_FALSE,
    };

    const VkStencilOpState emptyStencil{};
    // Trail pipelines do not write depth so later billboard layers can still render cleanly while
    // retaining depth tests against existing scene geometry.
    const VkBool32 depthWritesEnabled = topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP ? VK_FALSE : VK_TRUE;
    const VkPipelineDepthStencilStateCreateInfo depthStencilState{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .depthTestEnable = VK_TRUE,
        .depthWriteEnable = depthWritesEnabled,
        .depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL,
        .depthBoundsTestEnable = VK_FALSE,
        .stencilTestEnable = VK_FALSE,
        .front = emptyStencil,
        .back = emptyStencil,
        .minDepthBounds = 0.0f,
        .maxDepthBounds = 1.0f,
    };

    const VkPipelineColorBlendAttachmentState colorBlendAttachment{
        .blendEnable = VK_TRUE,
        .srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA,
        .dstColorBlendFactor = additiveBlending ? VK_BLEND_FACTOR_ONE : VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
        .colorBlendOp = VK_BLEND_OP_ADD,
        .srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE,
        .dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
        .alphaBlendOp = VK_BLEND_OP_ADD,
        .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT,
    };

    const VkPipelineColorBlendStateCreateInfo colorBlendState{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .logicOpEnable = VK_FALSE,
        .logicOp = VK_LOGIC_OP_COPY,
        .attachmentCount = 1,
        .pAttachments = &colorBlendAttachment,
        .blendConstants = {0.0f, 0.0f, 0.0f, 0.0f},
    };

    const VkDynamicState dynamicStates[] = {
        VK_DYNAMIC_STATE_VIEWPORT,
        VK_DYNAMIC_STATE_SCISSOR,
    };
    const VkPipelineDynamicStateCreateInfo dynamicState{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .dynamicStateCount = static_cast<uint32_t>(std::size(dynamicStates)),
        .pDynamicStates = dynamicStates,
    };

    const VkGraphicsPipelineCreateInfo pipelineCreateInfo{
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .stageCount = 2,
        .pStages = shaderStages,
        .pVertexInputState = &vertexInputState,
        .pInputAssemblyState = &inputAssemblyState,
        .pTessellationState = nullptr,
        .pViewportState = &viewportState,
        .pRasterizationState = &rasterizationState,
        .pMultisampleState = &multisampleState,
        .pDepthStencilState = &depthStencilState,
        .pColorBlendState = &colorBlendState,
        .pDynamicState = &dynamicState,
        .layout = graphicsPipelineLayout_,
        .renderPass = renderPass_,
        .subpass = 0,
        .basePipelineHandle = VK_NULL_HANDLE,
        .basePipelineIndex = -1,
    };

    const VkResult createResult = vkCreateGraphicsPipelines(device_, pipelineCache_, 1, &pipelineCreateInfo, nullptr, &pipeline);
    vkDestroyShaderModule(device_, fragmentShader, nullptr);
    vkDestroyShaderModule(device_, vertexShader, nullptr);

    if (createResult != VK_SUCCESS) {
        SetError(std::string("vkCreateGraphicsPipelines failed for ") + label + ".");
        pipeline = VK_NULL_HANDLE;
        return false;
    }
    return true;
}


bool SolarLabVulkanRenderer::CreateComputePipeline(
    const char* label,
    const char* computeShaderAssetPath,
    VkPipeline& pipeline) {
    VkShaderModule computeShader = VK_NULL_HANDLE;
    if (!LoadShaderModuleFromAssets(computeShaderAssetPath, computeShader)) {
        return false;
    }

    const VkPipelineShaderStageCreateInfo shaderStage{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .stage = VK_SHADER_STAGE_COMPUTE_BIT,
        .module = computeShader,
        .pName = "main",
        .pSpecializationInfo = nullptr,
    };

    const VkComputePipelineCreateInfo createInfo{
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .stage = shaderStage,
        .layout = computePipelineLayout_,
        .basePipelineHandle = VK_NULL_HANDLE,
        .basePipelineIndex = -1,
    };

    const VkResult createResult = vkCreateComputePipelines(device_, pipelineCache_, 1, &createInfo, nullptr, &pipeline);
    vkDestroyShaderModule(device_, computeShader, nullptr);
    if (createResult != VK_SUCCESS) {
        SetError(std::string("vkCreateComputePipelines failed for ") + label + ".");
        pipeline = VK_NULL_HANDLE;
        return false;
    }
    return true;
}
