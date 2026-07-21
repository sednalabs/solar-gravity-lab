#pragma once

#include "SolarLabVulkanRenderer.h"

#include <android/asset_manager.h>

#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

class SolarLabStageController {
public:
    struct CameraSnapshot {
        double centerX = 0.0;
        double centerY = 0.0;
        double centerZ = 0.0;
        double viewRadiusM = 24.0 * 149597870700.0;
        double yawRadians = -0.5934119456780721;
        double pitchRadians = 1.0995574287564276;
    };

    SolarLabStageController();
    ~SolarLabStageController();

    static bool IsVulkanRuntimeAvailable();

    void SetAssetManager(AAssetManager* assetManager);

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
        std::vector<int32_t> trailVertexCounts);

    void SetCamera(
        double centerX,
        double centerY,
        double centerZ,
        double viewRadiusM,
        double yawRadians,
        double pitchRadians);

    void BindRuntimeSession(uint64_t runtimeSessionHandle);
    void UnbindRuntimeSession();
    void SetRuntimeProcessingMode(int processingModeCode);
    void SetRuntimeObserverMode(int observerModeCode);
    void SetRuntimeSelectedBodyId(const std::string& bodyId);
    void SetRuntimeTraceLayerMode(int traceLayerModeCode);
    void ResetRuntimeCamera();
    void PanRuntimeCamera(float distanceXPx, float distanceYPx, int viewportWidthPx, int viewportHeightPx);
    void ZoomRuntimeCamera(float scaleFactor, float focusXPx, float focusYPx, int viewportWidthPx, int viewportHeightPx);
    void PanAndZoomRuntimeCamera(float distanceXPx, float distanceYPx, float scaleFactor, float focusXPx, float focusYPx, int viewportWidthPx, int viewportHeightPx);
    void OrbitRuntimeCamera(float deltaXPx, float deltaYPx, float focusXPx, float focusYPx, int viewportWidthPx, int viewportHeightPx);
    CameraSnapshot GetCameraSnapshot() const;
    std::optional<CameraSnapshot> ResolveRuntimeHomeCamera() const;
    std::optional<CameraSnapshot> ResolveRuntimeBodyFrame(const std::string& bodyId) const;
    std::string PickRuntimeBodyId(float screenXPx, float screenYPx, int viewportWidthPx, int viewportHeightPx);

    bool Render();

    std::string LastError() const;
    std::string BackendLabel() const;
    std::string SceneSummary() const;
    std::string HardwareSummary() const;

    struct RuntimeBodyProxy {
        std::string bodyId;
        double positionRelativeX = 0.0;
        double positionRelativeY = 0.0;
        double positionRelativeZ = 0.0;
        float radiusM = 0.0f;
        uint32_t kind = 0;
    };

private:
    struct RuntimeSceneState {
        int64_t packetRevision = -1;
        int64_t uploadedRevision = -1;
        double sceneOriginX = 0.0;
        double sceneOriginY = 0.0;
        double sceneOriginZ = 0.0;
        std::string packetSummary;
        std::vector<RuntimeBodyProxy> pickBodies;
    };

    struct RuntimeAbi;

    void SetErrorLocked(const std::string& message);
    bool RefreshRuntimeSceneLocked();
    void PushCameraLocked();
    bool RuntimeSessionBoundLocked() const;
    double MinimumViewRadiusLocked() const;
    void InitializeFreeCameraFromRuntimePacketLocked(
        double sceneOriginX,
        double sceneOriginY,
        double sceneOriginZ,
        double cameraPositionFromOriginX,
        double cameraPositionFromOriginY,
        double cameraPositionFromOriginZ,
        double cameraTargetFromOriginX,
        double cameraTargetFromOriginY,
        double cameraTargetFromOriginZ,
        double verticalFovDegrees,
        bool forceSnap);

    SolarLabVulkanRenderer renderer_;
    RuntimeAbi* runtimeAbi_ = nullptr;
    mutable std::mutex stateMutex_;
    std::string lastError_;

    uint64_t boundRuntimeSessionHandle_ = 0;
    int runtimeProcessingModeCode_ = 0;
    int runtimeObserverModeCode_ = 0;
    int runtimeTraceLayerModeCode_ = 0;
    std::string runtimeSelectedBodyId_;

    double cameraCenterX_ = 0.0;
    double cameraCenterY_ = 0.0;
    double cameraCenterZ_ = 0.0;
    double cameraViewRadiusM_ = 24.0 * 149597870700.0;
    double cameraYawRadians_ = -0.5934119456780721;
    double cameraPitchRadians_ = 1.0995574287564276;
    bool runtimeCameraInitialized_ = false;
    uint64_t cameraRevisionCounter_ = 1;
    int surfaceWidthPx_ = 1;
    int surfaceHeightPx_ = 1;

    RuntimeSceneState runtimeScene_;
};
