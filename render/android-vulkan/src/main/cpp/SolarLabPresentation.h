#pragma once

#include <algorithm>
#include <chrono>
#include <cmath>
#include <optional>

// Pure presentation geometry shared by native interaction and Vulkan uniforms.
// Distances here never feed the authoritative simulation.
namespace solarlab::presentation {

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
    const Projection& projection) {
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
    const double touchRadius = std::max(radiusPx * 1.45, 18.0);
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

inline double TranslateWithReference(double coordinate, double previousReference, double currentReference) {
    return coordinate + (currentReference - previousReference);
}

inline double PresentationReferenceOffset(double previousMinusCurrent, double fraction) {
    return previousMinusCurrent * (1.0 - fraction);
}

}  // namespace solarlab::presentation
