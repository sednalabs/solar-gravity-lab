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

    struct LabBodyAnchor {
        std::string bodyId;
        double screenX = 0.0;
        double screenY = 0.0;
        double radiusPx = 0.0;
        double depthM = 0.0;
        bool occluded = false;
        double worldX = 0.0;
        double worldY = 0.0;
        double worldZ = 0.0;
    };

    struct CameraTransitionFrame {
        CameraSnapshot start;
        CameraSnapshot target;
        // Nonempty centres are offsets from this live body, not absolute coordinates.
        std::string referenceBodyId;
    };

    struct LabViewportSnapshot {
        int width = 1;
        int height = 1;
        CameraSnapshot camera;
        double metersPerPixel = 1.0;
        std::vector<LabBodyAnchor> bodies;
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
        double pitchRadians,
        const std::string& referenceBodyId = {});

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
    LabViewportSnapshot GetLabViewportSnapshot() const;
    void SetLabMapView(bool enabled, double normalX, double normalY, double normalZ);
    void SetTouchDensity(double density);
    void SetFlightCamera(int mode);
    std::optional<CameraSnapshot> ResolveRuntimeHomeCamera() const;
    std::optional<CameraSnapshot> ResolveRuntimeBodyFrame(const std::string& bodyId) const;
    std::optional<CameraTransitionFrame> ResolveRuntimeCameraTransition(const std::string& bodyId) const;
    std::string PickRuntimeBodyId(float screenXPx, float screenYPx, int viewportWidthPx, int viewportHeightPx);

    bool Render(bool refreshRuntimeScene = true);
    bool HasPendingPresentation() const;

    std::string LastError() const;
    std::string BackendLabel() const;
    std::string SceneSummary() const;
    std::string HardwareSummary() const;

    struct RuntimeBodyProxy {
        std::string bodyId;
        double positionRelativeX = 0.0;
        double positionRelativeY = 0.0;
        double positionRelativeZ = 0.0;
        double previousRelativeX = 0.0;
        double previousRelativeY = 0.0;
        double previousRelativeZ = 0.0;
        float radiusM = 0.0f;
        float visualRadiusM = 0.0f;
        float frameRadiusM = 0.0f;
        uint32_t kind = 0;
        uint32_t meshKind = 0;
        std::array<float, 4> attitude{0, 0, 0, 1};
    };

private:
    struct RuntimeSceneState {
        int64_t packetRevision = -1;
        int64_t uploadedRevision = -1;
        double sceneOriginX = 0.0;
        double sceneOriginY = 0.0;
        double sceneOriginZ = 0.0;
        std::string scenarioId;
        std::string branchId;
        double observedAtSeconds = 0.0;
        double presentationStartedAtSeconds = 0.0;
        double presentationDurationSeconds = 0.0;
        double cameraPreviousOffsetX = 0.0;
        double cameraPreviousOffsetY = 0.0;
        double cameraPreviousOffsetZ = 0.0;
        bool snapshotValid = false;
        std::string packetSummary;
        std::vector<RuntimeBodyProxy> pickBodies;
    };

    struct RuntimeAbi;

    void SetErrorLocked(const std::string& message);
    bool RefreshRuntimeSceneLocked();
    void PushCameraLocked();
    CameraSnapshot PresentedCameraLocked(double fraction) const;
    double PresentationFractionLocked() const;
    void ClearCameraPresentationLocked();
    void ApplyLabMapOrientationLocked();
    void PreserveFollowOffsetLocked(double previousCenterX, double previousCenterY, double previousCenterZ);
    void SetRuntimeObserverModeLocked(int observerModeCode);
    bool RuntimeSessionBoundLocked() const;
    double MinimumViewRadiusLocked() const;
    std::optional<CameraSnapshot> ResolveRuntimeHomeCameraLocked() const;
    std::optional<CameraSnapshot> ResolveRuntimeBodyFrameLocked(const std::string& bodyId) const;
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

    int flightCameraMode_ = 0;
    bool flightFramePending_ = false;
    bool labMapMode_ = false;
    double labMapYawRadians_ = 0.0;
    double labMapPitchRadians_ = 1.5707963267948966;
    double labOrbitYawRadians_ = -0.5934119456780721;
    double labOrbitPitchRadians_ = 1.0995574287564276;
    double touchDensity_ = 1.0;
    double followOffsetX_ = 0.0;
    double followOffsetY_ = 0.0;
    double followOffsetZ_ = 0.0;
    double followTargetX_ = 0.0;
    double followTargetY_ = 0.0;
    double followTargetZ_ = 0.0;
    bool followTargetValid_ = false;

    double cameraCenterX_ = 0.0;
    double cameraCenterY_ = 0.0;
    double cameraCenterZ_ = 0.0;
    double cameraViewRadiusM_ = 24.0 * 149597870700.0;
    double cameraYawRadians_ = -0.5934119456780721;
    double cameraPitchRadians_ = 1.0995574287564276;
    bool runtimeCameraInitialized_ = false;
    bool runtimeHomePending_ = false;
    uint64_t cameraRevisionCounter_ = 1;
    int surfaceWidthPx_ = 1;
    int surfaceHeightPx_ = 1;

    RuntimeSceneState runtimeScene_;
};
