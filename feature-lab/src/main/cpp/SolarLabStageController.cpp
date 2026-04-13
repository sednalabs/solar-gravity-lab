#include "SolarLabStageController.h"

#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <array>
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <functional>
#include <limits>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "solarlab_v2.h"

namespace {
constexpr const char* kLogTag = "SolarLabStage";
constexpr double kAstronomicalUnitM = 149597870700.0;
constexpr double kMinViewRadiusM = 0.001 * kAstronomicalUnitM;
constexpr double kMaxViewRadiusM = 150000.0 * kAstronomicalUnitM;
constexpr double kDefaultViewRadiusM = 24.0 * kAstronomicalUnitM;
constexpr double kDefaultYawRadians = -0.5934119456780721;    // -34 degrees.
constexpr double kDefaultPitchRadians = 1.0995574287564276;   // 63 degrees.
constexpr double kPi = 3.14159265358979323846;
constexpr double kMinPitchRadians = 0.20943951023931956;      // 12 degrees.
constexpr double kMaxPitchRadians = 1.53588974175501;         // 88 degrees.
constexpr double kOrbitYawRadiansPerPixel = 0.0075;
constexpr double kOrbitPitchRadiansPerPixel = 0.0050;
constexpr uint32_t kKindStar = 0U;
constexpr uint32_t kKindPlanet = 1U;
constexpr uint32_t kKindDwarfPlanet = 2U;
constexpr uint32_t kKindAsteroid = 3U;
constexpr uint32_t kKindComet = 4U;
constexpr uint32_t kKindProbe = 5U;
constexpr uint32_t kKindTestObject = 6U;
constexpr int kObserverModeFree = 0;
constexpr int kObserverModeFollowSelected = 1;
constexpr int kObserverModeFollowSelectedHost = 2;
constexpr int kProcessingModeDefault = 0;
constexpr int kProcessingModeLow = 1;

void LogInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message.c_str());
}

struct Float3 {
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
};

Float3 MakeFloat3(double x, double y, double z) {
    return Float3{.x = x, .y = y, .z = z};
}

Float3 operator+(Float3 a, Float3 b) {
    return MakeFloat3(a.x + b.x, a.y + b.y, a.z + b.z);
}

Float3 operator-(Float3 a, Float3 b) {
    return MakeFloat3(a.x - b.x, a.y - b.y, a.z - b.z);
}

Float3 operator*(Float3 v, double scalar) {
    return MakeFloat3(v.x * scalar, v.y * scalar, v.z * scalar);
}

Float3 operator/(Float3 v, double scalar) {
    return scalar == 0.0 ? v : MakeFloat3(v.x / scalar, v.y / scalar, v.z / scalar);
}

double Dot(Float3 a, Float3 b) {
    return (a.x * b.x) + (a.y * b.y) + (a.z * b.z);
}

Float3 Cross(Float3 a, Float3 b) {
    return MakeFloat3(
        (a.y * b.z) - (a.z * b.y),
        (a.z * b.x) - (a.x * b.z),
        (a.x * b.y) - (a.y * b.x));
}

double Magnitude(Float3 v) {
    return std::sqrt(Dot(v, v));
}

Float3 Normalize(Float3 v) {
    const double magnitude = Magnitude(v);
    if (magnitude <= 1e-9) {
        return MakeFloat3(0.0, 0.0, 0.0);
    }
    return v / magnitude;
}

double Clamp(double value, double lower, double upper) {
    return std::max(lower, std::min(value, upper));
}

float ClampFloat(float value, float lower, float upper) {
    return std::max(lower, std::min(value, upper));
}

float KindMinimumBillboardDiameterPx(uint32_t kind) {
    switch (kind) {
        case kKindStar:
            return 9.0f;
        case kKindPlanet:
            return 6.4f;
        case kKindDwarfPlanet:
            return 5.2f;
        case kKindProbe:
        case kKindTestObject:
            return 4.0f;
        default:
            return 3.8f;
    }
}

uint32_t PackArgb(const SlPackedColor& color) {
    auto channel = [](float value) -> uint32_t {
        return static_cast<uint32_t>(std::clamp(std::lround(value * 255.0f), 0l, 255l));
    };
    const uint32_t a = channel(color.a <= 0.0f ? 1.0f : color.a);
    const uint32_t r = channel(color.r);
    const uint32_t g = channel(color.g);
    const uint32_t b = channel(color.b);
    return (a << 24U) | (r << 16U) | (g << 8U) | b;
}

std::string DecodeInlineUtf8(const uint8_t* bytes, uint32_t length) {
    if (bytes == nullptr || length == 0) {
        return {};
    }
    return std::string(reinterpret_cast<const char*>(bytes), reinterpret_cast<const char*>(bytes) + length);
}

std::string DecodeBytesView(const SlBytesView& view) {
    if (view.data == nullptr || view.length == 0) {
        return {};
    }
    return std::string(reinterpret_cast<const char*>(view.data), reinterpret_cast<const char*>(view.data) + view.length);
}

uint32_t InferBodyKind(const std::string& bodyId, float radiusM, float emissiveLuminance) {
    const std::string lowered = [&]() {
        std::string out = bodyId;
        std::transform(out.begin(), out.end(), out.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        return out;
    }();

    if (lowered == "sun" || emissiveLuminance >= 100000.0f) {
        return kKindStar;
    }
    if (lowered.find("probe") != std::string::npos || lowered.find("ship") != std::string::npos) {
        return kKindProbe;
    }
    if (lowered.find("comet") != std::string::npos) {
        return kKindComet;
    }
    if (radiusM >= 1'000'000.0f) {
        return kKindPlanet;
    }
    if (radiusM >= 250'000.0f) {
        return kKindDwarfPlanet;
    }
    if (radiusM <= 10'000.0f) {
        return kKindProbe;
    }
    return kKindAsteroid;
}

bool ShouldIncludeAuthoritativeBody(float radiusM, float emissiveLuminance, bool selected) {
    return selected || emissiveLuminance > 1000.0f || radiusM >= 100'000.0f;
}

struct CameraFrame {
    Float3 right;
    Float3 up;
    Float3 forward;
    double halfSpanX = kDefaultViewRadiusM;
    double halfSpanY = kDefaultViewRadiusM;
    double metersPerPixel = 1.0;
};

CameraFrame BuildCameraFrame(
    double viewRadiusM,
    double yawRadians,
    double pitchRadians,
    int viewportWidthPx,
    int viewportHeightPx) {
    const int width = std::max(viewportWidthPx, 1);
    const int height = std::max(viewportHeightPx, 1);
    const int minDimension = std::max(1, std::min(width, height));
    const double safeRadius = std::max(viewRadiusM, 1.0);

    CameraFrame frame;
    frame.halfSpanY = safeRadius;
    frame.halfSpanX = safeRadius * (static_cast<double>(width) / static_cast<double>(minDimension));
    frame.metersPerPixel = (2.0 * safeRadius) / static_cast<double>(minDimension);
    frame.right = Normalize(MakeFloat3(std::cos(yawRadians), std::sin(yawRadians), 0.0));
    const Float3 screenUpHorizontal = Normalize(MakeFloat3(-std::sin(yawRadians), std::cos(yawRadians), 0.0));
    frame.forward = Normalize(screenUpHorizontal * std::cos(pitchRadians) + MakeFloat3(0.0, 0.0, -std::sin(pitchRadians)));
    frame.up = Normalize(Cross(frame.right, frame.forward));
    return frame;
}

struct ProjectedBody {
    double screenX = 0.0;
    double screenY = 0.0;
    float radiusPx = 0.0f;
    bool visible = false;
};

ProjectedBody ProjectBody(
    const SolarLabStageController::RuntimeBodyProxy& body,
    double sceneOriginX,
    double sceneOriginY,
    double sceneOriginZ,
    double cameraCenterX,
    double cameraCenterY,
    double cameraCenterZ,
    double viewRadiusM,
    double yawRadians,
    double pitchRadians,
    int viewportWidthPx,
    int viewportHeightPx) {
    const int width = std::max(viewportWidthPx, 1);
    const int height = std::max(viewportHeightPx, 1);
    const CameraFrame frame = BuildCameraFrame(viewRadiusM, yawRadians, pitchRadians, width, height);
    const Float3 cameraCenterRelative = MakeFloat3(
        cameraCenterX - sceneOriginX,
        cameraCenterY - sceneOriginY,
        cameraCenterZ - sceneOriginZ);
    const Float3 positionRelative = MakeFloat3(body.positionRelativeX, body.positionRelativeY, body.positionRelativeZ);
    const Float3 delta = positionRelative - cameraCenterRelative;
    const double clipX = Dot(delta, frame.right) / std::max(frame.halfSpanX, 1.0);
    const double clipY = Dot(delta, frame.up) / std::max(frame.halfSpanY, 1.0);
    if (std::abs(clipX) > 1.25 || std::abs(clipY) > 1.25) {
        return {};
    }

    ProjectedBody result;
    result.screenX = ((clipX * 0.5) + 0.5) * static_cast<double>(width);
    result.screenY = (1.0 - ((clipY * 0.5) + 0.5)) * static_cast<double>(height);
    result.radiusPx = std::max(
        static_cast<float>(body.radiusM / std::max(frame.metersPerPixel, 1e-3)),
        KindMinimumBillboardDiameterPx(body.kind) * 0.5f);
    result.visible = true;
    return result;
}

uint64_t MakeSyntheticRevision(int64_t packetRevision, uint64_t cameraRevisionCounter, int processingModeCode, int observerModeCode) {
    uint64_t revision = static_cast<uint64_t>(packetRevision < 0 ? 0 : packetRevision);
    revision ^= (cameraRevisionCounter + 0x9E3779B97F4A7C15ULL + (revision << 6U) + (revision >> 2U));
    revision ^= (static_cast<uint64_t>(processingModeCode & 0xFFFF) << 16U);
    revision ^= (static_cast<uint64_t>(observerModeCode & 0xFFFF) << 32U);
    if (revision == 0) {
        revision = 1;
    }
    return revision;
}

struct SimplificationPolicy {
    double nearExtentFactor = 1.5;
    double mediumExtentFactor = 6.0;
    double farExtentFactor = 24.0;
    size_t nearTracerBudget = 4096;
    size_t mediumTracerBudget = 8192;
    size_t farTracerBudget = 16384;
    size_t maxTrailVerticesPerTrail = 128;
};

enum class CameraScaleBand {
    Close,
    Local,
    System,
    Wide,
    Deep,
};

CameraScaleBand CameraScaleBandFromViewRadius(double viewRadiusM) {
    const double safeRadius = std::max(viewRadiusM, 1.0);
    const std::array<double, 5> nominal = {
        0.010 * kAstronomicalUnitM,
        0.18 * kAstronomicalUnitM,
        3.2 * kAstronomicalUnitM,
        32.0 * kAstronomicalUnitM,
        320.0 * kAstronomicalUnitM,
    };
    const std::array<CameraScaleBand, 5> bands = {
        CameraScaleBand::Close,
        CameraScaleBand::Local,
        CameraScaleBand::System,
        CameraScaleBand::Wide,
        CameraScaleBand::Deep,
    };
    for (size_t index = 0; index + 1 < nominal.size(); ++index) {
        const double threshold = std::sqrt(nominal[index] * nominal[index + 1]);
        if (safeRadius < threshold) {
            return bands[index];
        }
    }
    return CameraScaleBand::Deep;
}

SimplificationPolicy PolicyForCamera(double viewRadiusM, int processingModeCode) {
    SimplificationPolicy policy;
    if (processingModeCode == kProcessingModeLow) {
        policy.nearTracerBudget = 2048;
        policy.mediumTracerBudget = 4096;
        policy.farTracerBudget = 6144;
        policy.maxTrailVerticesPerTrail = 96;
    }

    switch (CameraScaleBandFromViewRadius(viewRadiusM)) {
        case CameraScaleBand::Close:
            policy.nearExtentFactor = 1.75;
            policy.mediumExtentFactor = 4.5;
            policy.farExtentFactor = 12.0;
            policy.nearTracerBudget *= 2;
            policy.mediumTracerBudget = std::max<size_t>(2048, policy.mediumTracerBudget / 2);
            policy.farTracerBudget = std::max<size_t>(2048, policy.farTracerBudget / 4);
            policy.maxTrailVerticesPerTrail = std::min<size_t>(512, policy.maxTrailVerticesPerTrail * 2);
            break;
        case CameraScaleBand::Local:
            policy.nearExtentFactor = 1.6;
            policy.mediumExtentFactor = 5.5;
            policy.farExtentFactor = 18.0;
            policy.nearTracerBudget = (policy.nearTracerBudget * 3) / 2;
            policy.farTracerBudget = std::max<size_t>(4096, policy.farTracerBudget / 2);
            break;
        case CameraScaleBand::System:
            break;
        case CameraScaleBand::Wide:
            policy.nearExtentFactor = 1.25;
            policy.mediumExtentFactor = 7.5;
            policy.farExtentFactor = 36.0;
            policy.nearTracerBudget = std::max<size_t>(2048, policy.nearTracerBudget / 2);
            policy.farTracerBudget *= 2;
            policy.maxTrailVerticesPerTrail = std::max<size_t>(96, policy.maxTrailVerticesPerTrail / 2);
            break;
        case CameraScaleBand::Deep:
            policy.nearExtentFactor = 1.0;
            policy.mediumExtentFactor = 8.0;
            policy.farExtentFactor = 48.0;
            policy.nearTracerBudget = std::max<size_t>(1024, policy.nearTracerBudget / 4);
            policy.mediumTracerBudget = std::max<size_t>(4096, policy.mediumTracerBudget / 2);
            policy.farTracerBudget *= 3;
            policy.maxTrailVerticesPerTrail = std::max<size_t>(64, policy.maxTrailVerticesPerTrail / 3);
            break;
    }
    return policy;
}

}  // namespace

struct SolarLabStageController::RuntimeAbi {
    void* libraryHandle = nullptr;
    decltype(&sl_v2_session_export_vulkan_scene) session_export_vulkan_scene = nullptr;
    decltype(&sl_v2_vulkan_scene_packet_buffer) packet_buffer = nullptr;
    decltype(&sl_v2_vulkan_scene_packet_release) packet_release = nullptr;

    ~RuntimeAbi() {
        if (libraryHandle != nullptr) {
            dlclose(libraryHandle);
            libraryHandle = nullptr;
        }
    }

    bool EnsureLoaded(std::string& error) {
        if (session_export_vulkan_scene != nullptr && packet_buffer != nullptr && packet_release != nullptr) {
            return true;
        }
        if (libraryHandle == nullptr) {
            libraryHandle = dlopen("libsolarlab_v2.so", RTLD_NOW | RTLD_LOCAL);
            if (libraryHandle == nullptr) {
                error = dlerror() != nullptr ? dlerror() : "dlopen(libsolarlab_v2.so) failed";
                return false;
            }
        }
        session_export_vulkan_scene = reinterpret_cast<decltype(session_export_vulkan_scene)>(dlsym(libraryHandle, "sl_v2_session_export_vulkan_scene"));
        packet_buffer = reinterpret_cast<decltype(packet_buffer)>(dlsym(libraryHandle, "sl_v2_vulkan_scene_packet_buffer"));
        packet_release = reinterpret_cast<decltype(packet_release)>(dlsym(libraryHandle, "sl_v2_vulkan_scene_packet_release"));
        if (session_export_vulkan_scene == nullptr || packet_buffer == nullptr || packet_release == nullptr) {
            error = dlerror() != nullptr ? dlerror() : "Failed to resolve solarlab_v2 runtime ABI symbols";
            return false;
        }
        return true;
    }
};

SolarLabStageController::SolarLabStageController() : runtimeAbi_(new RuntimeAbi()) {}

SolarLabStageController::~SolarLabStageController() {
    delete runtimeAbi_;
    runtimeAbi_ = nullptr;
}

bool SolarLabStageController::IsVulkanRuntimeAvailable() {
    return SolarLabVulkanRenderer::IsRuntimeAvailable();
}

void SolarLabStageController::SetAssetManager(AAssetManager* assetManager) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    renderer_.SetAssetManager(assetManager);
}

bool SolarLabStageController::Initialize(JNIEnv* env, jobject surface, int width, int height) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    surfaceWidthPx_ = std::max(width, 1);
    surfaceHeightPx_ = std::max(height, 1);
    return renderer_.Initialize(env, surface, width, height);
}

bool SolarLabStageController::Resize(JNIEnv* env, jobject surface, int width, int height) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    surfaceWidthPx_ = std::max(width, 1);
    surfaceHeightPx_ = std::max(height, 1);
    return renderer_.Resize(env, surface, width, height);
}

void SolarLabStageController::DestroySurface() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    renderer_.DestroySurface();
}

void SolarLabStageController::SubmitScene(
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
    std::lock_guard<std::mutex> lock(stateMutex_);
    boundRuntimeSessionHandle_ = 0;
    runtimeScene_ = RuntimeSceneState{};
    renderer_.SubmitScene(
        sourceRevision,
        sceneOriginX,
        sceneOriginY,
        sceneOriginZ,
        std::move(authoritativePositionsM),
        std::move(authoritativeSourceMassesKg),
        std::move(authoritativeRadiiM),
        std::move(authoritativeColorsArgb),
        std::move(authoritativeKinds),
        std::move(tracerNearPositionsM),
        std::move(tracerNearRadiiM),
        std::move(tracerNearColorsArgb),
        std::move(tracerNearKinds),
        std::move(tracerMediumPositionsM),
        std::move(tracerMediumVelocitiesMps),
        std::move(tracerMediumStableIds),
        std::move(tracerMediumRadiiM),
        std::move(tracerMediumColorsArgb),
        std::move(tracerMediumKinds),
        std::move(tracerFarPositionsM),
        std::move(tracerFarVelocitiesMps),
        std::move(tracerFarStableIds),
        std::move(tracerFarRadiiM),
        std::move(tracerFarColorsArgb),
        std::move(tracerFarKinds),
        std::move(trailPositionsM),
        std::move(trailColorsArgb),
        std::move(trailVertexCounts));
}

void SolarLabStageController::SetCamera(
    double centerX,
    double centerY,
    double centerZ,
    double viewRadiusM,
    double yawRadians,
    double pitchRadians) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    cameraCenterX_ = centerX;
    cameraCenterY_ = centerY;
    cameraCenterZ_ = centerZ;
    cameraViewRadiusM_ = Clamp(viewRadiusM, kMinViewRadiusM, kMaxViewRadiusM);
    cameraYawRadians_ = yawRadians;
    while (cameraYawRadians_ > kPi) cameraYawRadians_ -= 2.0 * kPi;
    while (cameraYawRadians_ < -kPi) cameraYawRadians_ += 2.0 * kPi;
    cameraPitchRadians_ = Clamp(pitchRadians, kMinPitchRadians, kMaxPitchRadians);
    runtimeCameraInitialized_ = true;
    ++cameraRevisionCounter_;
    PushCameraLocked();
}

void SolarLabStageController::BindRuntimeSession(uint64_t runtimeSessionHandle) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (boundRuntimeSessionHandle_ == runtimeSessionHandle) {
        return;
    }
    boundRuntimeSessionHandle_ = runtimeSessionHandle;
    runtimeCameraInitialized_ = false;
    runtimeScene_ = RuntimeSceneState{};
    ++cameraRevisionCounter_;
}

void SolarLabStageController::UnbindRuntimeSession() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (boundRuntimeSessionHandle_ == 0) {
        return;
    }
    boundRuntimeSessionHandle_ = 0;
    runtimeScene_ = RuntimeSceneState{};
    ++cameraRevisionCounter_;
}

void SolarLabStageController::SetRuntimeProcessingMode(int processingModeCode) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (runtimeProcessingModeCode_ == processingModeCode) {
        return;
    }
    runtimeProcessingModeCode_ = processingModeCode;
    ++cameraRevisionCounter_;
}

void SolarLabStageController::SetRuntimeObserverMode(int observerModeCode) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (runtimeObserverModeCode_ == observerModeCode) {
        return;
    }
    runtimeObserverModeCode_ = observerModeCode;
    if (runtimeObserverModeCode_ != kObserverModeFree) {
        runtimeCameraInitialized_ = false;
    }
    ++cameraRevisionCounter_;
}

void SolarLabStageController::SetRuntimeSelectedBodyId(const std::string& bodyId) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (runtimeSelectedBodyId_ == bodyId) {
        return;
    }
    runtimeSelectedBodyId_ = bodyId;
    if (runtimeObserverModeCode_ != kObserverModeFree) {
        runtimeCameraInitialized_ = false;
    }
    ++cameraRevisionCounter_;
}

void SolarLabStageController::ResetRuntimeCamera() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    runtimeCameraInitialized_ = false;
    cameraCenterX_ = 0.0;
    cameraCenterY_ = 0.0;
    cameraCenterZ_ = 0.0;
    cameraViewRadiusM_ = kDefaultViewRadiusM;
    cameraYawRadians_ = kDefaultYawRadians;
    cameraPitchRadians_ = kDefaultPitchRadians;
    ++cameraRevisionCounter_;
}

void SolarLabStageController::PanRuntimeCamera(float distanceXPx, float distanceYPx, int viewportWidthPx, int viewportHeightPx) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!RuntimeSessionBoundLocked() || runtimeObserverModeCode_ != kObserverModeFree) {
        return;
    }
    if (!runtimeCameraInitialized_) {
        return;
    }
    const CameraFrame frame = BuildCameraFrame(cameraViewRadiusM_, cameraYawRadians_, cameraPitchRadians_, viewportWidthPx, viewportHeightPx);
    cameraCenterX_ += (distanceXPx * frame.metersPerPixel * frame.right.x) - (distanceYPx * frame.metersPerPixel * frame.up.x);
    cameraCenterY_ += (distanceXPx * frame.metersPerPixel * frame.right.y) - (distanceYPx * frame.metersPerPixel * frame.up.y);
    cameraCenterZ_ += (distanceXPx * frame.metersPerPixel * frame.right.z) - (distanceYPx * frame.metersPerPixel * frame.up.z);
    ++cameraRevisionCounter_;
}

void SolarLabStageController::ZoomRuntimeCamera(float scaleFactor) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!RuntimeSessionBoundLocked()) {
        return;
    }
    if (!runtimeCameraInitialized_ || scaleFactor <= 0.0f) {
        return;
    }
    cameraViewRadiusM_ = Clamp(cameraViewRadiusM_ / static_cast<double>(scaleFactor), kMinViewRadiusM, kMaxViewRadiusM);
    ++cameraRevisionCounter_;
}

void SolarLabStageController::OrbitRuntimeCamera(float deltaXPx, float deltaYPx) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!RuntimeSessionBoundLocked() || runtimeObserverModeCode_ != kObserverModeFree) {
        return;
    }
    if (!runtimeCameraInitialized_) {
        return;
    }
    cameraYawRadians_ -= static_cast<double>(deltaXPx) * kOrbitYawRadiansPerPixel;
    while (cameraYawRadians_ > kPi) cameraYawRadians_ -= 2.0 * kPi;
    while (cameraYawRadians_ < -kPi) cameraYawRadians_ += 2.0 * kPi;
    cameraPitchRadians_ = Clamp(cameraPitchRadians_ - (static_cast<double>(deltaYPx) * kOrbitPitchRadiansPerPixel), kMinPitchRadians, kMaxPitchRadians);
    ++cameraRevisionCounter_;
}

std::string SolarLabStageController::PickRuntimeBodyId(float screenXPx, float screenYPx, int viewportWidthPx, int viewportHeightPx) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!RuntimeSessionBoundLocked() || runtimeScene_.pickBodies.empty() || !runtimeCameraInitialized_) {
        return {};
    }

    double bestDistanceSquared = std::numeric_limits<double>::max();
    std::string bestBodyId;
    for (const RuntimeBodyProxy& body : runtimeScene_.pickBodies) {
        const ProjectedBody projected = ProjectBody(
            body,
            runtimeScene_.sceneOriginX,
            runtimeScene_.sceneOriginY,
            runtimeScene_.sceneOriginZ,
            cameraCenterX_,
            cameraCenterY_,
            cameraCenterZ_,
            cameraViewRadiusM_,
            cameraYawRadians_,
            cameraPitchRadians_,
            viewportWidthPx,
            viewportHeightPx);
        if (!projected.visible) {
            continue;
        }
        const double dx = static_cast<double>(screenXPx) - projected.screenX;
        const double dy = static_cast<double>(screenYPx) - projected.screenY;
        const double selectionRadiusPx = std::max<double>(projected.radiusPx * 1.45f, 18.0f);
        const double distanceSquared = (dx * dx) + (dy * dy);
        if (distanceSquared <= selectionRadiusPx * selectionRadiusPx && distanceSquared < bestDistanceSquared) {
            bestDistanceSquared = distanceSquared;
            bestBodyId = body.bodyId;
        }
    }
    return bestBodyId;
}

bool SolarLabStageController::Render() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (RuntimeSessionBoundLocked()) {
        if (!RefreshRuntimeSceneLocked()) {
            if (runtimeScene_.uploadedRevision < 0) {
                return false;
            }
        }
    } else {
        PushCameraLocked();
    }
    return renderer_.Render();
}

std::string SolarLabStageController::LastError() const {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!lastError_.empty()) {
        return lastError_;
    }
    return renderer_.LastError();
}

std::string SolarLabStageController::BackendLabel() const {
    std::lock_guard<std::mutex> lock(stateMutex_);
    return renderer_.BackendLabel();
}

std::string SolarLabStageController::SceneSummary() const {
    std::lock_guard<std::mutex> lock(stateMutex_);
    std::ostringstream out;
    out << renderer_.SceneSummary();
    if (RuntimeSessionBoundLocked()) {
        out << " runtime[s=" << boundRuntimeSessionHandle_ << "]";
        if (!runtimeScene_.packetSummary.empty()) {
            out << ' ' << runtimeScene_.packetSummary;
        }
    }
    return out.str();
}

std::string SolarLabStageController::HardwareSummary() const {
    std::lock_guard<std::mutex> lock(stateMutex_);
    return renderer_.HardwareSummary();
}

void SolarLabStageController::SetErrorLocked(const std::string& message) {
    lastError_ = message;
}

bool SolarLabStageController::RuntimeSessionBoundLocked() const {
    return boundRuntimeSessionHandle_ != 0;
}

void SolarLabStageController::PushCameraLocked() {
    renderer_.SetCamera(
        cameraCenterX_,
        cameraCenterY_,
        cameraCenterZ_,
        cameraViewRadiusM_,
        cameraYawRadians_,
        cameraPitchRadians_);
}

void SolarLabStageController::InitializeFreeCameraFromRuntimePacketLocked(
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
    bool forceSnap) {
    if (runtimeCameraInitialized_ && !forceSnap) {
        return;
    }

    const Float3 cameraPosition = MakeFloat3(
        sceneOriginX + cameraPositionFromOriginX,
        sceneOriginY + cameraPositionFromOriginY,
        sceneOriginZ + cameraPositionFromOriginZ);
    const Float3 cameraTarget = MakeFloat3(
        sceneOriginX + cameraTargetFromOriginX,
        sceneOriginY + cameraTargetFromOriginY,
        sceneOriginZ + cameraTargetFromOriginZ);
    const Float3 viewDirection = Normalize(cameraTarget - cameraPosition);
    const double horizontalMagnitude = std::sqrt((viewDirection.x * viewDirection.x) + (viewDirection.y * viewDirection.y));
    cameraYawRadians_ = std::atan2(viewDirection.y, viewDirection.x) - (kPi * 0.5);
    cameraPitchRadians_ = Clamp(std::atan2(-viewDirection.z, horizontalMagnitude), kMinPitchRadians, kMaxPitchRadians);
    cameraCenterX_ = cameraTarget.x;
    cameraCenterY_ = cameraTarget.y;
    cameraCenterZ_ = cameraTarget.z;
    const double cameraDistance = Magnitude(cameraPosition - cameraTarget);
    const double halfFovRadians = std::max(0.1, (verticalFovDegrees * kPi / 180.0) * 0.5);
    cameraViewRadiusM_ = Clamp(std::max(cameraDistance * std::tan(halfFovRadians), 1000.0), kMinViewRadiusM, kMaxViewRadiusM);
    runtimeCameraInitialized_ = true;
    ++cameraRevisionCounter_;
}

bool SolarLabStageController::RefreshRuntimeSceneLocked() {
    std::string runtimeLoadError;
    if (runtimeAbi_ == nullptr || !runtimeAbi_->EnsureLoaded(runtimeLoadError)) {
        SetErrorLocked("Failed to load Rust runtime ABI for stage renderer: " + runtimeLoadError);
        return false;
    }

    const SlVulkanScenePacketResult packetResult = runtimeAbi_->session_export_vulkan_scene(
        SlRuntimeHandle{.raw = boundRuntimeSessionHandle_});
    if (packetResult.result.code != SL_STATUS_OK || packetResult.handle.raw == 0) {
        std::ostringstream out;
        out << "Rust runtime scene export failed with status=" << static_cast<int>(packetResult.result.code);
        SetErrorLocked(out.str());
        return false;
    }

    struct ScopedPacketRelease {
        RuntimeAbi* runtimeAbi = nullptr;
        SlRenderPacketHandle handle{};
        ~ScopedPacketRelease() {
            if (runtimeAbi != nullptr && handle.raw != 0) {
                runtimeAbi->packet_release(handle);
            }
        }
    } packetRelease{.runtimeAbi = runtimeAbi_, .handle = packetResult.handle};

    auto requestBuffer = [&](SlVulkanSceneBufferKind kind) -> std::optional<SlBufferView> {
        const SlBufferViewResult bufferResult = runtimeAbi_->packet_buffer(packetResult.handle, kind);
        if (bufferResult.result.code != SL_STATUS_OK) {
            return std::nullopt;
        }
        return bufferResult.view;
    };

    const std::optional<SlBufferView> bodiesView = requestBuffer(SL_VULKAN_SCENE_BODY_INSTANCES);
    const std::optional<SlBufferView> tracersView = requestBuffer(SL_VULKAN_SCENE_TRACER_INSTANCES);
    const std::optional<SlBufferView> trailSpansView = requestBuffer(SL_VULKAN_SCENE_TRAIL_SPANS);
    const std::optional<SlBufferView> trailVerticesView = requestBuffer(SL_VULKAN_SCENE_TRAIL_VERTICES);

    const SimplificationPolicy policy = PolicyForCamera(cameraViewRadiusM_, runtimeProcessingModeCode_);
    const bool cameraLocked = runtimeObserverModeCode_ != kObserverModeFree;
    if (cameraLocked) {
        InitializeFreeCameraFromRuntimePacketLocked(
            packetResult.info.camera.frame_origin_m.x,
            packetResult.info.camera.frame_origin_m.y,
            packetResult.info.camera.frame_origin_m.z,
            packetResult.info.camera.position_from_origin_m.x,
            packetResult.info.camera.position_from_origin_m.y,
            packetResult.info.camera.position_from_origin_m.z,
            packetResult.info.camera.target_from_origin_m.x,
            packetResult.info.camera.target_from_origin_m.y,
            packetResult.info.camera.target_from_origin_m.z,
            packetResult.info.camera.vertical_fov_degrees,
            true);
        cameraCenterX_ = packetResult.info.camera.frame_origin_m.x + packetResult.info.camera.target_from_origin_m.x;
        cameraCenterY_ = packetResult.info.camera.frame_origin_m.y + packetResult.info.camera.target_from_origin_m.y;
        cameraCenterZ_ = packetResult.info.camera.frame_origin_m.z + packetResult.info.camera.target_from_origin_m.z;
    } else {
        InitializeFreeCameraFromRuntimePacketLocked(
            packetResult.info.camera.frame_origin_m.x,
            packetResult.info.camera.frame_origin_m.y,
            packetResult.info.camera.frame_origin_m.z,
            packetResult.info.camera.position_from_origin_m.x,
            packetResult.info.camera.position_from_origin_m.y,
            packetResult.info.camera.position_from_origin_m.z,
            packetResult.info.camera.target_from_origin_m.x,
            packetResult.info.camera.target_from_origin_m.y,
            packetResult.info.camera.target_from_origin_m.z,
            packetResult.info.camera.vertical_fov_degrees,
            false);
    }

    std::vector<double> authoritativePositionsM;
    std::vector<double> authoritativeSourceMassesKg;
    std::vector<float> authoritativeRadiiM;
    std::vector<int32_t> authoritativeColorsArgb;
    std::vector<int32_t> authoritativeKinds;
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
    std::vector<RuntimeBodyProxy> pickBodies;

    runtimeScene_.sceneOriginX = packetResult.info.camera.frame_origin_m.x;
    runtimeScene_.sceneOriginY = packetResult.info.camera.frame_origin_m.y;
    runtimeScene_.sceneOriginZ = packetResult.info.camera.frame_origin_m.z;

    if (bodiesView.has_value() && bodiesView->data != nullptr && bodiesView->element_count > 0) {
        const uint8_t* raw = reinterpret_cast<const uint8_t*>(bodiesView->data);
        const uint32_t bodyCount = bodiesView->element_count;
        const uint32_t stride = bodiesView->stride_bytes == 0 ? sizeof(SlVulkanBodyInstance) : bodiesView->stride_bytes;
        pickBodies.reserve(bodyCount);
        for (uint32_t index = 0; index < bodyCount; ++index) {
            const auto* body = reinterpret_cast<const SlVulkanBodyInstance*>(raw + (stride * index));
            const std::string bodyId = DecodeInlineUtf8(body->body_id, body->body_id_len);
            const uint32_t kind = InferBodyKind(bodyId, body->radius_m, body->emissive_luminance);
            const bool selected = body->selected != 0;
            pickBodies.push_back(RuntimeBodyProxy{
                .bodyId = bodyId,
                .positionRelativeX = body->position_from_origin_m.x,
                .positionRelativeY = body->position_from_origin_m.y,
                .positionRelativeZ = body->position_from_origin_m.z,
                .radiusM = body->radius_m,
                .kind = kind,
            });
            if (!ShouldIncludeAuthoritativeBody(body->radius_m, body->emissive_luminance, selected)) {
                continue;
            }
            authoritativePositionsM.push_back(body->position_from_origin_m.x);
            authoritativePositionsM.push_back(body->position_from_origin_m.y);
            authoritativePositionsM.push_back(body->position_from_origin_m.z);
            authoritativeSourceMassesKg.push_back(0.0);
            authoritativeRadiiM.push_back(body->radius_m * (selected ? 1.18f : 1.0f));
            authoritativeColorsArgb.push_back(static_cast<int32_t>(PackArgb(body->albedo)));
            authoritativeKinds.push_back(static_cast<int32_t>(kind));
        }
    }

    if (tracersView.has_value() && tracersView->data != nullptr && tracersView->element_count > 0) {
        const uint8_t* raw = reinterpret_cast<const uint8_t*>(tracersView->data);
        const uint32_t tracerCount = tracersView->element_count;
        const uint32_t stride = tracersView->stride_bytes == 0 ? sizeof(SlVulkanTracerInstance) : tracersView->stride_bytes;
        const CameraFrame frame = BuildCameraFrame(cameraViewRadiusM_, cameraYawRadians_, cameraPitchRadians_, surfaceWidthPx_, surfaceHeightPx_);
        const Float3 cameraCenterRelative = MakeFloat3(
            cameraCenterX_ - runtimeScene_.sceneOriginX,
            cameraCenterY_ - runtimeScene_.sceneOriginY,
            cameraCenterZ_ - runtimeScene_.sceneOriginZ);
        for (uint32_t index = 0; index < tracerCount; ++index) {
            const auto* tracer = reinterpret_cast<const SlVulkanTracerInstance*>(raw + (stride * index));
            const Float3 position = MakeFloat3(
                tracer->position_from_origin_m.x,
                tracer->position_from_origin_m.y,
                tracer->position_from_origin_m.z);
            const Float3 delta = position - cameraCenterRelative;
            const double xView = std::abs(Dot(delta, frame.right));
            const double yView = std::abs(Dot(delta, frame.up));
            const double zView = std::abs(Dot(delta, frame.forward));
            const double maxExtent = std::max({xView, yView, zView});
            const double pseudoRadiusM = std::max<double>(tracer->size_px * frame.metersPerPixel * 0.8, 1000.0);
            const int32_t colorArgb = static_cast<int32_t>(PackArgb(tracer->color));
            auto appendNear = [&]() {
                tracerNearPositionsM.insert(tracerNearPositionsM.end(), {position.x, position.y, position.z});
                tracerNearRadiiM.push_back(static_cast<float>(pseudoRadiusM));
                tracerNearColorsArgb.push_back(colorArgb);
                tracerNearKinds.push_back(static_cast<int32_t>(kKindProbe));
            };
            auto appendMedium = [&]() {
                tracerMediumPositionsM.insert(tracerMediumPositionsM.end(), {position.x, position.y, position.z});
                tracerMediumVelocitiesMps.insert(tracerMediumVelocitiesMps.end(), {0.0, 0.0, 0.0});
                tracerMediumStableIds.push_back(static_cast<int32_t>(index + 1U));
                tracerMediumRadiiM.push_back(static_cast<float>(tracer->size_px));
                tracerMediumColorsArgb.push_back(colorArgb);
                tracerMediumKinds.push_back(static_cast<int32_t>(kKindProbe));
            };
            auto appendFar = [&]() {
                tracerFarPositionsM.insert(tracerFarPositionsM.end(), {position.x, position.y, position.z});
                tracerFarVelocitiesMps.insert(tracerFarVelocitiesMps.end(), {0.0, 0.0, 0.0});
                tracerFarStableIds.push_back(static_cast<int32_t>(index + 1U));
                tracerFarRadiiM.push_back(static_cast<float>(std::max(1.0f, tracer->size_px * 0.75f)));
                tracerFarColorsArgb.push_back(colorArgb);
                tracerFarKinds.push_back(static_cast<int32_t>(kKindProbe));
            };

            if (maxExtent <= cameraViewRadiusM_ * policy.nearExtentFactor && tracerNearRadiiM.size() < policy.nearTracerBudget) {
                appendNear();
            } else if (maxExtent <= cameraViewRadiusM_ * policy.mediumExtentFactor && tracerMediumRadiiM.size() < policy.mediumTracerBudget) {
                appendMedium();
            } else if (maxExtent <= cameraViewRadiusM_ * policy.farExtentFactor && tracerFarRadiiM.size() < policy.farTracerBudget) {
                appendFar();
            }
        }
    }

    if (trailSpansView.has_value() && trailVerticesView.has_value() && trailSpansView->data != nullptr && trailVerticesView->data != nullptr) {
        const uint8_t* spansRaw = reinterpret_cast<const uint8_t*>(trailSpansView->data);
        const uint8_t* verticesRaw = reinterpret_cast<const uint8_t*>(trailVerticesView->data);
        const uint32_t spanCount = trailSpansView->element_count;
        const uint32_t spanStride = trailSpansView->stride_bytes == 0 ? sizeof(SlVulkanTrailSpan) : trailSpansView->stride_bytes;
        const uint32_t vertexStride = trailVerticesView->stride_bytes == 0 ? sizeof(SlVulkanTrailVertex) : trailVerticesView->stride_bytes;
        const uint32_t totalVertexCount = trailVerticesView->element_count;
        for (uint32_t spanIndex = 0; spanIndex < spanCount; ++spanIndex) {
            const auto* span = reinterpret_cast<const SlVulkanTrailSpan*>(spansRaw + (spanStride * spanIndex));
            const uint32_t start = std::min(span->vertex_offset, totalVertexCount);
            const uint32_t count = std::min(span->vertex_count, totalVertexCount - start);
            if (count < 2U) {
                continue;
            }
            const uint32_t maxVertices = static_cast<uint32_t>(std::max<size_t>(2, policy.maxTrailVerticesPerTrail));
            const uint32_t step = std::max<uint32_t>(1U, static_cast<uint32_t>(std::ceil(static_cast<double>(count) / static_cast<double>(maxVertices))));
            uint32_t emitted = 0;
            for (uint32_t vertexIndex = 0; vertexIndex < count; vertexIndex += step) {
                const auto* vertex = reinterpret_cast<const SlVulkanTrailVertex*>(verticesRaw + (vertexStride * (start + vertexIndex)));
                trailPositionsM.insert(trailPositionsM.end(), {
                    static_cast<double>(vertex->position_from_origin_m.x),
                    static_cast<double>(vertex->position_from_origin_m.y),
                    static_cast<double>(vertex->position_from_origin_m.z),
                });
                trailColorsArgb.push_back(static_cast<int32_t>(PackArgb(span->color)));
                ++emitted;
            }
            const auto* lastVertex = reinterpret_cast<const SlVulkanTrailVertex*>(verticesRaw + (vertexStride * (start + count - 1U)));
            if (emitted == 0 || step > 1U) {
                trailPositionsM.insert(trailPositionsM.end(), {
                    static_cast<double>(lastVertex->position_from_origin_m.x),
                    static_cast<double>(lastVertex->position_from_origin_m.y),
                    static_cast<double>(lastVertex->position_from_origin_m.z),
                });
                trailColorsArgb.push_back(static_cast<int32_t>(PackArgb(span->color)));
                ++emitted;
            }
            trailVertexCounts.push_back(static_cast<int32_t>(emitted));
        }
    }

    const std::string sceneRevision = DecodeBytesView(packetResult.info.scene_revision);
    const uint64_t revisionSeed = static_cast<uint64_t>(std::hash<std::string>{}(sceneRevision));
    const uint64_t syntheticRevision = MakeSyntheticRevision(
        static_cast<int64_t>(revisionSeed ^ static_cast<uint64_t>(packetResult.info.diagnostics.frame_number)),
        cameraRevisionCounter_,
        runtimeProcessingModeCode_,
        runtimeObserverModeCode_);

    renderer_.SubmitScene(
        static_cast<int64_t>(syntheticRevision),
        runtimeScene_.sceneOriginX,
        runtimeScene_.sceneOriginY,
        runtimeScene_.sceneOriginZ,
        std::move(authoritativePositionsM),
        std::move(authoritativeSourceMassesKg),
        std::move(authoritativeRadiiM),
        std::move(authoritativeColorsArgb),
        std::move(authoritativeKinds),
        std::move(tracerNearPositionsM),
        std::move(tracerNearRadiiM),
        std::move(tracerNearColorsArgb),
        std::move(tracerNearKinds),
        std::move(tracerMediumPositionsM),
        std::move(tracerMediumVelocitiesMps),
        std::move(tracerMediumStableIds),
        std::move(tracerMediumRadiiM),
        std::move(tracerMediumColorsArgb),
        std::move(tracerMediumKinds),
        std::move(tracerFarPositionsM),
        std::move(tracerFarVelocitiesMps),
        std::move(tracerFarStableIds),
        std::move(tracerFarRadiiM),
        std::move(tracerFarColorsArgb),
        std::move(tracerFarKinds),
        std::move(trailPositionsM),
        std::move(trailColorsArgb),
        std::move(trailVertexCounts));

    runtimeScene_.packetRevision = static_cast<int64_t>(packetResult.info.diagnostics.frame_number);
    runtimeScene_.uploadedRevision = static_cast<int64_t>(syntheticRevision);
    runtimeScene_.pickBodies = std::move(pickBodies);
    std::ostringstream summary;
    summary << "rev=" << sceneRevision
            << " bodies=" << packetResult.info.body_instance_count
            << " tracers=" << packetResult.info.tracer_instance_count
            << " trails=" << packetResult.info.trail_span_count << '/' << packetResult.info.trail_vertex_count;
    runtimeScene_.packetSummary = summary.str();
    PushCameraLocked();
    lastError_.clear();
    return true;
}
