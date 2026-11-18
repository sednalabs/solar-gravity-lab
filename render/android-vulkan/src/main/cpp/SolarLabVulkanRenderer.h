#pragma once

#include "SolarLabGpuReuse.h"
#include "SolarLabVulkanCapabilities.h"
#include "QcomTileMemory.h"

#include <android/asset_manager.h>
#include <android/native_window.h>
#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

class SolarLabVulkanRenderer {
public:
    struct CelestialAppearanceInput {
        uint32_t material = 10U;
        uint32_t flags = 0U;
        float northPoleX = 0.0f;
        float northPoleY = 1.0f;
        float northPoleZ = 0.0f;
        float referenceMeridianRadians = 0.0f;
        float ringInnerRadiusM = 0.0f;
        float ringOuterRadiusM = 0.0f;
        float ringOpticalDepth = 0.0f;
        float ringPlaneX = 0.0f;
        float ringPlaneY = 1.0f;
        float ringPlaneZ = 0.0f;
        float atmosphereOuterRadiusM = 0.0f;
        float atmosphereOpticalDensity = 0.0f;
        float cometNucleusRadiusM = 0.0f;
        float cometComaRadiusM = 0.0f;
        float cometDustTailLengthM = 0.0f;
        float cometIonTailLengthM = 0.0f;
        float cometAntiSolarX = 1.0f;
        float cometAntiSolarY = 0.0f;
        float cometAntiSolarZ = 0.0f;
        float cometVelocityX = 0.0f;
        float cometVelocityY = 0.0f;
        float cometVelocityZ = 1.0f;
    };

    SolarLabVulkanRenderer();
    ~SolarLabVulkanRenderer();

    static bool IsRuntimeAvailable();

    void SetAssetManager(AAssetManager* assetManager);

    // Takes ownership of one ANativeWindow reference on every call.
    bool Initialize(ANativeWindow* nativeWindow, int width, int height);
    bool Resize(ANativeWindow* nativeWindow, int width, int height);
    void DestroySurface();

    void SubmitScene(
        int64_t sourceRevision,
        double sceneOriginX,
        double sceneOriginY,
        double sceneOriginZ,
        std::vector<double> authoritativePositionsM,
        std::vector<double> authoritativeSourceMassesKg,
        std::vector<float> authoritativeRadiiM,
        std::vector<int32_t> authoritativeColorsArgb,
        std::vector<int32_t> authoritativeKinds,
        std::vector<CelestialAppearanceInput> authoritativeAppearances,
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
        std::vector<int32_t> trailVertexCounts,
        std::vector<double> authoritativePreviousPositionsM = {},
        double presentationStartedAtSeconds = 0.0,
        double presentationDurationSeconds = 0.0,
        std::array<double, 3> trailPreviousOffsetM = {});

    void SetCamera(double centerX, double centerY, double centerZ, double viewRadiusM, double yawRadians, double pitchRadians,
        double previousOffsetX = 0.0, double previousOffsetY = 0.0, double previousOffsetZ = 0.0);
    bool Render();
    double PresentedFraction() const;

    std::string LastError() const;
    std::string BackendLabel() const;
    std::string SceneSummary() const;
    std::string HardwareSummary() const;

private:
    struct SceneBuffers {
        int64_t sourceRevision = 0;
        double sceneOriginX = 0.0;
        double sceneOriginY = 0.0;
        double sceneOriginZ = 0.0;
        std::vector<double> authoritativePositionsM;
        std::vector<double> authoritativePreviousPositionsM;
        double presentationStartedAtSeconds = 0.0;
        double presentationDurationSeconds = 0.0;
        std::array<double, 3> trailPreviousOffsetM{};
        std::vector<double> authoritativeSourceMassesKg;
        std::vector<float> authoritativeRadiiM;
        std::vector<int32_t> authoritativeColorsArgb;
        std::vector<int32_t> authoritativeKinds;
        std::vector<CelestialAppearanceInput> authoritativeAppearances;
        std::vector<double> tracerNearPositionsM;
        std::vector<float> tracerNearRadiiM;
        std::vector<int32_t> tracerNearColorsArgb;
        std::vector<int32_t> tracerNearKinds;
        std::vector<double> tracerMediumPositionsM;
        std::vector<double> tracerMediumVelocitiesMps;
        std::vector<int32_t> tracerMediumStableIds;
        std::vector<float> tracerMediumRadiiM;
        std::vector<int32_t> tracerMediumColorsArgb;
        std::vector<int32_t> tracerMediumKinds;
        std::vector<double> tracerFarPositionsM;
        std::vector<double> tracerFarVelocitiesMps;
        std::vector<int32_t> tracerFarStableIds;
        std::vector<float> tracerFarRadiiM;
        std::vector<int32_t> tracerFarColorsArgb;
        std::vector<int32_t> tracerFarKinds;
        std::vector<double> trailPositionsM;
        std::vector<int32_t> trailColorsArgb;
        std::vector<int32_t> trailVertexCounts;
    };

    enum class DrawPath : uint32_t {
        None = 0,
        BillboardSprite = 1,
        CheapPointSprite = 2,
        DensityPoint = 3,
        ThinLineStrip = 4,
    };

    struct BillboardVertex {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
        float radiusM = 0.0f;
        uint32_t colorArgb = 0;
        uint32_t kind = 0;
        float alpha = 1.0f;
        uint32_t material = 10U;
        uint32_t appearanceFlags = 0U;
        float physicalRadiusM = 0.0f;
        float northPoleX = 0.0f;
        float northPoleY = 1.0f;
        float northPoleZ = 0.0f;
        float ringInnerRadiusM = 0.0f;
        float ringOuterRadiusM = 0.0f;
        float ringOpticalDepth = 0.0f;
        float ringPlaneX = 0.0f;
        float ringPlaneY = 1.0f;
        float ringPlaneZ = 0.0f;
        float atmosphereOuterRadiusM = 0.0f;
        float atmosphereOpticalDensity = 0.0f;
        float referenceMeridianRadians = 0.0f;
        float cometNucleusRadiusM = 0.0f;
        float cometComaRadiusM = 0.0f;
        float cometDustTailLengthM = 0.0f;
        float cometIonTailLengthM = 0.0f;
        float cometAntiSolarX = 1.0f;
        float cometAntiSolarY = 0.0f;
        float cometAntiSolarZ = 0.0f;
        float cometVelocityX = 0.0f;
        float cometVelocityY = 0.0f;
        float cometVelocityZ = 1.0f;
        float previousX = 0.0f;
        float previousY = 0.0f;
        float previousZ = 0.0f;
    };

    struct CheapPointVertex {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
        uint32_t colorArgb = 0;
        float sizePx = 1.0f;
    };

    struct MediumTracerState {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
        float vx = 0.0f;
        float vy = 0.0f;
        float vz = 0.0f;
        uint32_t colorArgb = 0;
        float sizePx = 1.0f;
        uint32_t stableId = 0;
        uint32_t reserved = 0;
    };

    struct DensityPointVertex {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
        uint32_t colorArgb = 0;
        uint32_t densityWeight = 1;
    };

    struct FarTracerState {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
        float vx = 0.0f;
        float vy = 0.0f;
        float vz = 0.0f;
        uint32_t colorArgb = 0;
        uint32_t densityWeight = 1;
        uint32_t stableId = 0;
        uint32_t reserved = 0;
    };

    // The compute shaders use scalar std430 members: vec3 would add padding and
    // break the tightly packed three-dimensional vertex input used by graphics.
    static_assert(sizeof(CheapPointVertex) == 20 && offsetof(CheapPointVertex, colorArgb) == 12);
    static_assert(sizeof(DensityPointVertex) == 20 && offsetof(DensityPointVertex, densityWeight) == 16);
    static_assert(sizeof(MediumTracerState) == 40 && offsetof(MediumTracerState, colorArgb) == 24);
    static_assert(sizeof(FarTracerState) == 40 && offsetof(FarTracerState, stableId) == 32);

    struct TrailVertex {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
        uint32_t colorArgb = 0;
        float alpha = 1.0f;
    };

    struct AuthoritativeInfluenceBody {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
        float sourceMassKg = 0.0f;
    };

    struct alignas(16) SceneUniformData {
        std::array<float, 4> centerRelativeAndMetrics{};
        std::array<float, 4> rightAndSpan{};
        std::array<float, 4> upAndSpan{};
        std::array<float, 4> forwardAndDepth{};
        std::array<float, 4> viewport{};
        std::array<float, 4> primaryLightPositionRelativeAndFlags{};
        std::array<float, 4> presentation{};
        std::array<float, 4> trailPresentationTranslation{};
    };

    struct ComputePushConstants {
        uint32_t sourceCount = 0;
        uint32_t tileCounterCount = 0;
        uint32_t reserved1 = 0;
        uint32_t reserved2 = 0;
        VkDeviceAddress sourceAddress = 0;
        VkDeviceAddress outputAddress = 0;
        VkDeviceAddress drawAddress = 0;
    };
    static_assert(sizeof(ComputePushConstants) == 40 && offsetof(ComputePushConstants, sourceAddress) == 16 &&
        offsetof(ComputePushConstants, outputAddress) == 24 && offsetof(ComputePushConstants, drawAddress) == 32);

    struct GpuBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize sizeBytes = 0;
        VkBufferUsageFlags usage = 0;
        VkMemoryPropertyFlags memoryProperties = 0;
        void* mapped = nullptr;
        bool isTileMemory = false;
        VkDeviceAddress deviceAddress = 0;
        uint64_t allocationGeneration = 0;
        const char* debugLabel = nullptr;
    };

    // Device-local upload source memory must outlive its copy command. Slots are reused only
    // after their individual transfer fence signals; a full graphics-queue drain is never
    // required for ordinary scene uploads.
    struct StagingUploadSlot {
        GpuBuffer stagingBuffer;
        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        bool inFlight = false;
    };

    struct GpuImage {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkFormat format = VK_FORMAT_UNDEFINED;
        uint32_t width = 0;
        uint32_t height = 0;
        const char* debugLabel = nullptr;
    };

    struct DrawStreamBuffers {
        DrawPath path = DrawPath::None;
        GpuBuffer vertexBuffer;
        uint32_t vertexCount = 0;
        uint32_t strideBytes = 0;
        VkBufferUsageFlags plannedUsage = 0;
        const char* label = nullptr;
    };

    struct ComputeDrawStreamBuffers {
        bool enabled = false;
        DrawPath path = DrawPath::None;
        GpuBuffer sourceStateBuffer;
        GpuBuffer outputVertexBuffer;
        GpuBuffer indirectCommandBuffer;
        GpuBuffer indirectReadbackBuffer;
        GpuBuffer tileCounterBuffer;
        GpuBuffer tileCounterReadbackBuffer;
        uint32_t sourceVertexCount = 0;
        uint32_t dispatchGroupCountX = 0;
        uint32_t outputVertexCapacity = 0;
        uint32_t tileCounterCount = 0;
        uint32_t activeTileCount = 0;
        uint32_t peakTileOccupancy = 0;
        uint32_t overflowVertexCount = 0;
        bool tileStatsValid = false;
        uint32_t visibleVertexCount = 0;
        bool visibleVertexCountValid = false;
        std::array<uint64_t, 5> descriptorBufferGenerations{};
        const char* label = nullptr;
    };

    struct SceneGpuStreams {
        int64_t uploadedRevision = -1;
        size_t totalBytes = 0;
        GpuBuffer authoritativeInfluenceBuffer;
        uint32_t authoritativeInfluenceCount = 0;
        DrawStreamBuffers authoritative;
        DrawStreamBuffers tracerNear;
        DrawStreamBuffers tracerMedium;
        DrawStreamBuffers tracerFar;
        DrawStreamBuffers trails;
        ComputeDrawStreamBuffers tracerMediumCompute;
        ComputeDrawStreamBuffers tracerFarCompute;
        std::vector<uint32_t> trailStripVertexCounts;
    };

    struct StreamUploadStats {
        int64_t sourceRevision = -1;
        size_t bytesUploaded = 0;
        uint32_t authoritativeCount = 0;
        uint32_t ringBodyCount = 0;
        uint32_t atmosphereBodyCount = 0;
        uint32_t cometBodyCount = 0;
        uint32_t tracerNearCount = 0;
        uint32_t tracerMediumCount = 0;
        uint32_t tracerFarCount = 0;
        uint32_t trailVertexCount = 0;
        uint32_t trailStripCount = 0;
    };

    bool CreateInstance();
    bool CreateSurface();
    void AdoptNativeWindow(ANativeWindow* nativeWindow);
    bool PickPhysicalDevice();
    bool CreateDevice();
    bool CreatePipelineCache();
    bool CreateSwapchain(int width, int height);
    bool CreateRenderPass();
    bool CreateDepthResources();
    bool CreateFramebuffers();
    bool CreateCommandPool();
    bool CreateDescriptorResources();
    bool CreateSurfaceColorBuffer();
    bool CreateGraphicsPipelines();
    bool CreateComputePipelines();
    bool AllocateAndRecordCommandBuffers();
    bool CreateSyncObjects();
    void CmdPipelineBarrier(VkCommandBuffer commandBuffer, VkPipelineStageFlags sourceStages,
        VkPipelineStageFlags destinationStages, VkDependencyFlags flags,
        uint32_t memoryCount, const VkMemoryBarrier* memory,
        uint32_t bufferCount, const VkBufferMemoryBarrier* buffers,
        uint32_t imageCount, const VkImageMemoryBarrier* images);
    VkResult SubmitWithSynchronization2(VkCommandBuffer commandBuffer, VkFence fence,
        VkSemaphore waitSemaphore = VK_NULL_HANDLE, VkSemaphore signalSemaphore = VK_NULL_HANDLE);
    void InitialiseGpuProfiling();
    void RefreshGpuTimingsLocked();
    void CapturePipelineStatistics(VkPipeline pipeline, const char* label);
    void DestroySurfaceResources();
    void DestroyDepthResources();
    void Cleanup();
    void SetError(const std::string& message);

    VkSurfaceFormatKHR ChooseSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) const;
    VkPresentModeKHR ChoosePresentMode(const std::vector<VkPresentModeKHR>& presentModes) const;
    VkExtent2D ChooseExtent(const VkSurfaceCapabilitiesKHR& capabilities, int width, int height) const;

    bool EnsureSceneGpuStreamsLocked();
    bool UploadSceneGpuStreamsLocked();
    bool UpdateSceneUniformBufferLocked();
    bool UpdateComputeDescriptorSetsLocked();
    solarlab::rendering::SceneCommandState SceneCommandStateLocked() const;
    bool RecordComputePassLocked(VkCommandBuffer commandBuffer);
    bool RecordSceneBindingsLocked(VkCommandBuffer commandBuffer);
    void RefreshCompactionVisibleCountsFromReadbackLocked();

    void DestroyGpuBuffer(GpuBuffer& buffer);
    void DestroyGpuImage(GpuImage& image);
    void DestroySceneGpuStreams();
    void DestroyGraphicsPipelines();
    void DestroyComputePipelines();
    void DestroyDescriptorResources();
    bool EnsureBufferWithMemoryProperties(
        VkDeviceSize sizeBytes,
        VkBufferUsageFlags usage,
        VkMemoryPropertyFlags memoryProperties,
        const char* label,
        bool reportErrors,
        GpuBuffer& buffer);
    bool EnsureHostVisibleBuffer(VkDeviceSize sizeBytes, VkBufferUsageFlags usage, const char* label, GpuBuffer& buffer);
    bool EnsureDeviceLocalBuffer(VkDeviceSize sizeBytes, VkBufferUsageFlags usage, const char* label, bool reportErrors, GpuBuffer& buffer);
    bool EnsureFarCounterBuffer(VkDeviceSize sizeBytes, GpuBuffer& buffer);
    StagingUploadSlot* AcquireStagingUploadSlot(bool reportErrors);
    bool SubmitStagingCopy(StagingUploadSlot& slot, const GpuBuffer& target, VkDeviceSize sizeBytes, bool reportErrors);
    void DestroyStagingUploadRing();
    bool TryUploadDeviceLocalWithStaging(const void* data, size_t sizeBytes, VkBufferUsageFlags usage, const char* label, GpuBuffer& target);
    bool UploadBytesInternal(const void* data, size_t sizeBytes, const GpuBuffer& buffer, bool reportErrors);
    bool UploadBytes(const void* data, size_t sizeBytes, GpuBuffer& buffer);
    uint32_t FindMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const;
    VkFormat PickDepthFormat() const;
    bool CreateDepthImage(uint32_t width, uint32_t height, VkFormat format, const char* label, GpuImage& image);
    std::string BuildSceneSummaryLocked() const;
    static const char* DrawPathName(DrawPath path);

    bool LoadShaderModuleFromAssets(const char* assetPath, VkShaderModule& shaderModule);
    bool CreateGraphicsPipeline(
        const char* label,
        const char* vertexShaderAssetPath,
        const char* fragmentShaderAssetPath,
        VkPrimitiveTopology topology,
        bool additiveBlending,
        const std::vector<VkVertexInputBindingDescription>& bindings,
        const std::vector<VkVertexInputAttributeDescription>& attributes,
        VkPipeline& pipeline);
    bool CreateComputePipeline(
        const char* label,
        const char* computeShaderAssetPath,
        VkPipeline& pipeline);

    mutable std::mutex stateMutex_;
    SceneBuffers sceneBuffers_;
    SceneGpuStreams sceneGpuStreams_;
    StreamUploadStats uploadStats_;

    double cameraCenterX_ = 0.0;
    double cameraCenterY_ = 0.0;
    double cameraCenterZ_ = 0.0;
    double cameraPreviousOffsetX_ = 0.0;
    double cameraPreviousOffsetY_ = 0.0;
    double cameraPreviousOffsetZ_ = 0.0;
    double cameraViewRadiusM_ = 0.0;
    double cameraYawRadians_ = 0.0;
    double cameraPitchRadians_ = 0.0;
    double pendingPresentationFraction_ = 1.0;
    double presentedFraction_ = 1.0;

    std::string lastError_;
    std::string backendLabelCache_;
    std::string sceneSummaryCache_;

    AAssetManager* assetManager_ = nullptr;
    ANativeWindow* nativeWindow_ = nullptr;

    VkInstance instance_ = VK_NULL_HANDLE;
    uint32_t instanceApiVersion_ = VK_API_VERSION_1_1;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkPhysicalDeviceFeatures supportedFeatures_{};
    VkPhysicalDeviceFeatures enabledFeatures_{};
    VkPhysicalDeviceProperties physicalDeviceProperties_{};
    solarlab::rendering::VulkanCapabilities capabilities_;
    solarlab::rendering::VulkanDeviceFeatures optionalFeatures_;
    solarlab::rendering::QcomTileMemory qcomTileMemory_;
    PFN_vkCmdPipelineBarrier2KHR cmdPipelineBarrier2_ = nullptr;
    PFN_vkQueueSubmit2KHR queueSubmit2_ = nullptr;
    PFN_vkGetBufferDeviceAddressKHR getBufferDeviceAddress_ = nullptr;
    PFN_vkGetCalibratedTimestampsEXT getCalibratedTimestamps_ = nullptr;
    PFN_vkGetPipelineExecutablePropertiesKHR getPipelineExecutableProperties_ = nullptr;
    PFN_vkGetPipelineExecutableStatisticsKHR getPipelineExecutableStatistics_ = nullptr;
    VkQueryPool timestampQueryPool_ = VK_NULL_HANDLE;
    bool gpuTimingPending_ = false;
    uint64_t gpuTimingSamples_ = 0;
    double gpuFrameNs_ = 0.0;
    double gpuPreGraphicsNs_ = 0.0;
    uint64_t calibratedDeviceTicks_ = 0;
    uint64_t calibratedHostNs_ = 0;
    uint64_t calibratedMaxDeviationNs_ = 0;
    uint64_t pipelineStatisticCount_ = 0;
    uint64_t synchronization2Submissions_ = 0;
    uint64_t persistentMappingCount_ = 0;
    uint32_t pendingAccelerationPaths_ = 0;
    uint64_t subgroupCompletedFrames_ = 0;
    uint64_t bufferAddressCompletedFrames_ = 0;
    uint64_t tileMemoryCompletedFrames_ = 0;
    bool mediumSubgroupPipeline_ = false;
    bool mediumBufferAddressPipeline_ = false;
    bool farSubgroupPipeline_ = false;
    VkDevice device_ = VK_NULL_HANDLE;
    VkPipelineCache pipelineCache_ = VK_NULL_HANDLE;
    uint32_t graphicsQueueFamilyIndex_ = UINT32_MAX;
    uint32_t presentQueueFamilyIndex_ = UINT32_MAX;
    bool graphicsQueueSupportsCompute_ = false;
    bool computeCompactionEnabled_ = false;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    VkQueue presentQueue_ = VK_NULL_HANDLE;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainImageFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchainExtent_{};
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainImageViews_;

    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkFormat depthFormat_ = VK_FORMAT_UNDEFINED;
    GpuImage depthImage_;
    std::vector<VkFramebuffer> framebuffers_;

    GpuBuffer sceneUniformBuffer_;
    GpuBuffer surfaceColorBuffer_;
    VkDescriptorSetLayout sceneDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet sceneDescriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout graphicsPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline billboardPipeline_ = VK_NULL_HANDLE;
    VkPipeline mediumPointPipeline_ = VK_NULL_HANDLE;
    VkPipeline farDensityPipeline_ = VK_NULL_HANDLE;
    VkPipeline trailPipeline_ = VK_NULL_HANDLE;

    VkDescriptorSetLayout computeDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool computeDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet tracerMediumComputeDescriptorSet_ = VK_NULL_HANDLE;
    VkDescriptorSet tracerFarComputeDescriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout computePipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline mediumComputePipeline_ = VK_NULL_HANDLE;
    VkPipeline farComputePipeline_ = VK_NULL_HANDLE;

    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers_;
    bool commandBuffersDirty_ = true;
    uint64_t bufferAllocationCount_ = 0;
    uint64_t commandRecordingCount_ = 0;
    uint64_t sceneUploadCount_ = 0;
    uint64_t commandReuseCount_ = 0;
    uint64_t cpuUploadBytes_ = 0;

    static constexpr size_t kStagingUploadRingSize = 8U;
    std::array<StagingUploadSlot, kStagingUploadRingSize> stagingUploadRing_{};
    size_t nextStagingUploadSlot_ = 0U;

    VkSemaphore imageAvailableSemaphore_ = VK_NULL_HANDLE;
    // Presentation completion is not implied by a rendering fence. Reacquiring
    // each swapchain image makes its own presentation-wait semaphore reusable.
    std::vector<VkSemaphore> renderFinishedSemaphores_;
    VkFence inFlightFence_ = VK_NULL_HANDLE;
};
