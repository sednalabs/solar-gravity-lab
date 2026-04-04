#pragma once

#include <jni.h>
#include <android/asset_manager.h>
#include <android/native_window.h>
#include <vulkan/vulkan.h>

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <span>
#include <string>
#include <vector>

class SolarLabVulkanRenderer {
public:
    SolarLabVulkanRenderer();
    ~SolarLabVulkanRenderer();

    static bool IsRuntimeAvailable();

    void SetAssetManager(AAssetManager* assetManager);

    bool Initialize(JNIEnv* env, jobject surface, int width, int height);
    bool Resize(JNIEnv* env, jobject surface, int width, int height);
    void DestroySurface();

    void SubmitSceneSeed(
        std::span<const double> tracerMediumPositionsM,
        std::span<const int64_t> tracerMediumHandles,
        std::span<const double> tracerMediumVelocitiesMps,
        std::span<const float> tracerMediumRadiiM,
        std::span<const int32_t> tracerMediumColorsArgb,
        std::span<const int32_t> tracerMediumKinds,
        std::span<const double> tracerFarPositionsM,
        std::span<const int64_t> tracerFarHandles,
        std::span<const double> tracerFarVelocitiesMps,
        std::span<const float> tracerFarRadiiM,
        std::span<const int32_t> tracerFarColorsArgb,
        std::span<const int32_t> tracerFarKinds,
        int64_t seedRevision);
    void SetFrameState(
        int64_t sourceRevision,
        double epochSeconds,
        double simulationAdvanceSeconds,
        std::span<const double> authoritativePositionsM,
        std::span<const double> authoritativeSourceMassesKg,
        std::span<const float> authoritativeRadiiM,
        std::span<const int32_t> authoritativeColorsArgb,
        std::span<const int32_t> authoritativeKinds,
        std::span<const double> tracerNearPositionsM,
        std::span<const float> tracerNearRadiiM,
        std::span<const int32_t> tracerNearColorsArgb,
        std::span<const int32_t> tracerNearKinds,
        std::span<const double> trailPositionsM,
        std::span<const int32_t> trailColorsArgb,
        std::span<const int32_t> trailVertexCounts);

    void SetCamera(double centerX, double centerY, double centerZ, double viewRadiusM);
    bool Render();

    std::string LastError() const;
    std::string BackendLabel() const;
    std::string SceneSummary() const;
    std::string HardwareSummary() const;

private:
    struct SeedBuffers {
        int64_t sourceRevision = 0;
        std::vector<int64_t> tracerMediumHandles;
        std::vector<double> tracerMediumPositionsM;
        std::vector<double> tracerMediumVelocitiesMps;
        std::vector<float> tracerMediumRadiiM;
        std::vector<int32_t> tracerMediumColorsArgb;
        std::vector<int32_t> tracerMediumKinds;
        std::vector<int64_t> tracerFarHandles;
        std::vector<double> tracerFarPositionsM;
        std::vector<double> tracerFarVelocitiesMps;
        std::vector<float> tracerFarRadiiM;
        std::vector<int32_t> tracerFarColorsArgb;
        std::vector<int32_t> tracerFarKinds;
    };

    struct FrameBuffers {
        int64_t sourceRevision = 0;
        double epochSeconds = 0.0;
        double simulationAdvanceSeconds = 0.0;
        std::vector<double> authoritativePositionsM;
        std::vector<double> authoritativeSourceMassesKg;
        std::vector<float> authoritativeRadiiM;
        std::vector<int32_t> authoritativeColorsArgb;
        std::vector<int32_t> authoritativeKinds;
        std::vector<double> tracerNearPositionsM;
        std::vector<float> tracerNearRadiiM;
        std::vector<int32_t> tracerNearColorsArgb;
        std::vector<int32_t> tracerNearKinds;
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
        float reserved = 0.0f;
    };

    struct CheapPointVertex {
        float x = 0.0f;
        float y = 0.0f;
        uint32_t colorArgb = 0;
        float sizePx = 1.0f;
    };

    struct MediumTracerState {
        uint32_t handleLo = 0;
        uint32_t handleHi = 0;
        float x = 0.0f;
        float y = 0.0f;
        float vx = 0.0f;
        float vy = 0.0f;
        uint32_t colorArgb = 0;
        float sizePx = 1.0f;
        float trailAlpha = 0.0f;
        float trailReserved = 0.0f;
    };

    struct DensityPointVertex {
        float x = 0.0f;
        float y = 0.0f;
        uint32_t colorArgb = 0;
        uint32_t densityWeight = 1;
    };

    struct FarTracerState {
        uint32_t handleLo = 0;
        uint32_t handleHi = 0;
        float x = 0.0f;
        float y = 0.0f;
        float vx = 0.0f;
        float vy = 0.0f;
        uint32_t colorArgb = 0;
        uint32_t densityWeight = 1;
        float trailAlpha = 0.0f;
        float trailReserved = 0.0f;
    };

    struct TrailVertex {
        float x = 0.0f;
        float y = 0.0f;
        uint32_t colorArgb = 0;
        float alpha = 1.0f;
    };

    struct TrailHistoryMeta {
        uint32_t sampleCount = 0;
        uint32_t capacity = 0;
        uint32_t reserved0 = 0;
        uint32_t reserved1 = 0;
    };

    struct AuthoritativeInfluenceBody {
        float x = 0.0f;
        float y = 0.0f;
        float z = 0.0f;
        float sourceMassKg = 0.0f;
    };

    struct alignas(16) SceneUniformData {
        std::array<float, 4> centerSpan{};
        std::array<float, 4> metrics{};
        std::array<float, 4> viewport{};
    };

    struct ComputePushConstants {
        uint32_t sourceCount = 0;
        uint32_t reserved0 = 0;
        uint32_t reserved1 = 0;
        uint32_t reserved2 = 0;
    };

    struct GpuBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize sizeBytes = 0;
        VkBufferUsageFlags usage = 0;
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
        GpuBuffer trailVertexBuffer;
        GpuBuffer trailMetaBuffer;
        GpuBuffer outputVertexBuffer;
        GpuBuffer indirectCommandBuffer;
        GpuBuffer indirectReadbackBuffer;
        GpuBuffer tileCounterBuffer;
        uint32_t sourceVertexCount = 0;
        uint32_t dispatchGroupCountX = 0;
        uint32_t tileCounterCount = 0;
        uint32_t visibleVertexCount = 0;
        bool visibleVertexCountValid = false;
        uint32_t trailVertexCount = 0;
        uint32_t trailStripCount = 0;
        uint32_t trailVerticesPerSource = 0;
        const char* label = nullptr;
    };

    struct SceneGpuStreams {
        int64_t uploadedSeedRevision = -1;
        int64_t uploadedFrameRevision = -1;
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
        uint32_t tracerNearCount = 0;
        uint32_t tracerMediumCount = 0;
        uint32_t tracerFarCount = 0;
        uint32_t trailVertexCount = 0;
        uint32_t trailStripCount = 0;
    };

    bool CreateInstance();
    bool CreateSurface(JNIEnv* env, jobject surface);
    bool PickPhysicalDevice();
    bool CreateDevice();
    bool CreateSwapchain(int width, int height);
    bool CreateRenderPass();
    bool CreateFramebuffers();
    bool CreateCommandPool();
    bool CreateDescriptorResources();
    bool CreateGraphicsPipelines();
    bool CreateComputePipelines();
    bool AllocateAndRecordCommandBuffers();
    bool CreateSyncObjects();
    void DestroySurfaceResources();
    void Cleanup();
    void SetError(const std::string& message);

    VkSurfaceFormatKHR ChooseSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) const;
    VkPresentModeKHR ChoosePresentMode(const std::vector<VkPresentModeKHR>& presentModes) const;
    VkExtent2D ChooseExtent(const VkSurfaceCapabilitiesKHR& capabilities, int width, int height) const;

    bool EnsureSceneGpuStreamsLocked();
    bool UploadSceneGpuStreamsLocked();
    bool UploadFrameGpuStreamsLocked();
    bool UpdateSceneUniformBufferLocked();
    bool UpdateComputeDescriptorSetsLocked();
    bool RecordComputePassLocked(VkCommandBuffer commandBuffer);
    bool RecordSceneBindingsLocked(VkCommandBuffer commandBuffer);
    void RefreshCompactionVisibleCountsFromReadbackLocked();

    void DestroyGpuBuffer(GpuBuffer& buffer);
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
    bool CopyBufferBytes(const GpuBuffer& source, const GpuBuffer& target, VkDeviceSize sizeBytes, bool reportErrors);
    bool TryUploadDeviceLocalWithStaging(const void* data, size_t sizeBytes, VkBufferUsageFlags usage, const char* label, GpuBuffer& target);
    bool UploadBytesInternal(const void* data, size_t sizeBytes, const GpuBuffer& buffer, bool reportErrors);
    bool UploadBytes(const void* data, size_t sizeBytes, GpuBuffer& buffer);
    uint32_t FindMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const;
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
    SeedBuffers seedBuffers_;
    FrameBuffers frameBuffers_;
    SceneGpuStreams sceneGpuStreams_;
    StreamUploadStats uploadStats_;

    double cameraCenterX_ = 0.0;
    double cameraCenterY_ = 0.0;
    double cameraCenterZ_ = 0.0;
    double cameraViewRadiusM_ = 0.0;
    std::string lastError_;
    std::string backendLabelCache_;
    std::string sceneSummaryCache_;

    AAssetManager* assetManager_ = nullptr;
    ANativeWindow* nativeWindow_ = nullptr;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkPhysicalDeviceFeatures supportedFeatures_{};
    VkPhysicalDeviceFeatures enabledFeatures_{};
    VkPhysicalDeviceProperties physicalDeviceProperties_{};
    VkDevice device_ = VK_NULL_HANDLE;
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
    std::vector<VkFramebuffer> framebuffers_;

    GpuBuffer sceneUniformBuffer_;
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
    int64_t commandBuffersRevision_ = -1;

    VkSemaphore imageAvailableSemaphore_ = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSemaphore_ = VK_NULL_HANDLE;
    VkFence inFlightFence_ = VK_NULL_HANDLE;
};
