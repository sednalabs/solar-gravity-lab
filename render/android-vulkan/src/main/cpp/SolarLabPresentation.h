#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <optional>
#include <string>
#include <unordered_set>

// Pure presentation geometry shared by native interaction and Vulkan uniforms.
// Distances here never feed the authoritative simulation.
namespace solarlab::presentation {

struct CameraBasis {
    std::array<double, 3> right;
    std::array<double, 3> up;
    std::array<double, 3> forward;
};

inline CameraBasis MakeCameraBasis(double yaw, double pitch) {
    const double sy = std::sin(yaw), cy = std::cos(yaw);
    const double sp = std::sin(pitch), cp = std::cos(pitch);
    return {{cy, sy, 0.0}, {-sy * sp, cy * sp, cp}, {-sy * cp, cy * cp, -sp}};
}

inline std::array<double, 2> MapCameraAngles(double normalX, double normalY, double normalZ) {
    const double magnitude = std::sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
    if (!std::isfinite(magnitude) || magnitude < 1e-10) return {0.0, std::acos(-1.0) * 0.5};
    const double yaw = std::hypot(normalX, normalY) < 1e-10 ? 0.0 : std::atan2(normalX, -normalY);
    return {yaw, std::asin(std::clamp(normalZ / magnitude, -1.0, 1.0))};
}

struct Projection {
    double halfSpanX;
    double halfSpanY;
    double metersPerPixel;
    double halfDepth;
};

inline Projection MakeProjection(double viewRadiusM, int viewportWidth, int viewportHeight) {
    const int width = std::max(viewportWidth, 1);
    const int height = std::max(viewportHeight, 1);
    const double minimumDimension = static_cast<double>(std::min(width, height));
    const double radius = std::isfinite(viewRadiusM) ? std::max(viewRadiusM, 1.0) : 1.0;
    return {
        radius * static_cast<double>(width) / minimumDimension,
        radius * static_cast<double>(height) / minimumDimension,
        2.0 * radius / minimumDimension,
        radius * 48.0,
    };
}

struct SphereHit {
    bool insideVisibleDisc;
    double distanceSquaredPx;
    double surfaceDepthM;
};

inline std::optional<SphereHit> HitSphere(
    double offsetXPx,
    double offsetYPx,
    double visibleRadiusPx,
    double centerDepthM,
    const Projection& projection,
    double minimumTouchRadiusPx = 18.0) {
    if (!std::isfinite(offsetXPx) || !std::isfinite(offsetYPx) ||
        !std::isfinite(visibleRadiusPx) || !std::isfinite(centerDepthM)) {
        return std::nullopt;
    }
    const double radiusPx = std::max(visibleRadiusPx, 0.0);
    const double radiusM = radiusPx * projection.metersPerPixel;
    if (centerDepthM - radiusM > projection.halfDepth ||
        centerDepthM + radiusM < -projection.halfDepth) {
        return std::nullopt;
    }
    const double distanceSquared = offsetXPx * offsetXPx + offsetYPx * offsetYPx;
    const double touchRadius = std::max(radiusPx * 1.45, minimumTouchRadiusPx);
    if (distanceSquared > touchRadius * touchRadius) {
        return std::nullopt;
    }
    const bool inside = distanceSquared <= radiusPx * radiusPx;
    const double sphereDepth = inside
        ? std::sqrt(std::max(0.0, radiusPx * radiusPx - distanceSquared)) * projection.metersPerPixel
        : 0.0;
    const double surfaceDepth = centerDepthM - sphereDepth;
    if (surfaceDepth < -projection.halfDepth || surfaceDepth > projection.halfDepth) {
        return std::nullopt;
    }
    return SphereHit{inside, distanceSquared, surfaceDepth};
}

inline bool PreferHit(const SphereHit& candidate, const SphereHit& current) {
    // A visible surface wins over another object's invisible touch padding.
    if (candidate.insideVisibleDisc != current.insideVisibleDisc) {
        return candidate.insideVisibleDisc;
    }
    if (candidate.insideVisibleDisc) {
        if (candidate.surfaceDepthM != current.surfaceDepthM) {
            return candidate.surfaceDepthM < current.surfaceDepthM;
        }
    } else if (candidate.distanceSquaredPx != current.distanceSquaredPx) {
        return candidate.distanceSquaredPx < current.distanceSquaredPx;
    }
    return candidate.surfaceDepthM < current.surfaceDepthM;
}

inline double MonotonicSeconds() {
    return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

inline double InterpolationFraction(double now, double startedAt, double duration) {
    if (!std::isfinite(now) || !std::isfinite(startedAt) ||
        !std::isfinite(duration) || duration <= 0.0) {
        return 1.0;
    }
    return std::clamp((now - startedAt) / duration, 0.0, 1.0);
}

inline double Mix(double previous, double current, double fraction) {
    return previous + (current - previous) * fraction;
}

inline double ContextFrameRadius(double preferredRadiusM, double contextDistanceM, double contextRadiusM) {
    return std::max(preferredRadiusM, (contextDistanceM + contextRadiusM) * 1.18);
}

inline double ContextSurfaceDistance(double centerDistanceM, double radiusM) {
    return std::max(0.0, centerDistanceM - radiusM);
}

// Only BodyClass::Tracer is exported into the scene's tracer stream.
// Spacecraft do NOT have a matching SceneTracer and therefore must remain
// authoritative billboards even when unselected. A tracer's selected state
// promotes it to an inspectable billboard, with its tracer sample suppressed.
// Picking proxies remain available regardless of the draw owner.
inline bool DrawAsAuthoritativeBody(
    bool isTracer, bool isSpacecraft, bool selected, float radiusM, float emissiveLuminance) {
    if (isTracer) return selected;
    if (isSpacecraft) return true;
    return selected || emissiveLuminance > 1000.0f || radiusM >= 100'000.0f;
}

// Ownership records what was actually submitted, not a possibly newer UI
// selection. Packet and UI selection may differ during a command handoff.
class TracerDrawOwnership {
public:
    void Record(const std::string& id) { ids_.insert(id); }
    bool Contains(const std::string& id) const { return ids_.contains(id); }
private:
    std::unordered_set<std::string> ids_;
};

struct ReferenceCameraCoordinate {
    double current;
    double previousOffset;
};

inline ReferenceCameraCoordinate CameraInReference(double localOffset, double previous, double current) {
    // The same display fraction must translate the reference, camera and picking geometry.
    return {current + localOffset, previous - current};
}

inline double TranslateWithReference(double coordinate, double previousReference, double currentReference) {
    return coordinate + (currentReference - previousReference);
}

inline double PresentationReferenceOffset(double previousMinusCurrent, double fraction) {
    return previousMinusCurrent * (1.0 - fraction);
}

}  // namespace solarlab::presentation
