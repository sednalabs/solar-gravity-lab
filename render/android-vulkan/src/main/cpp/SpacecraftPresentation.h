#pragma once
#include <array>
#include <algorithm>
#include <cmath>
#include <optional>

namespace solarlab::spacecraft {
struct SplitCoordinate { float high; float low; };
inline SplitCoordinate Split(double value) {
    const float high = static_cast<float>(value);
    return {high, static_cast<float>(value - static_cast<double>(high))};
}
inline std::array<float, 4> NormalizedAttitude(const std::array<float, 4>& q) {
    const double length = std::sqrt(static_cast<double>(q[0])*q[0] +
        static_cast<double>(q[1])*q[1] + static_cast<double>(q[2])*q[2] + static_cast<double>(q[3])*q[3]);
    if (!std::isfinite(length) || length < 1e-12) return {0, 0, 0, 1};
    return {static_cast<float>(q[0]/length), static_cast<float>(q[1]/length),
        static_cast<float>(q[2]/length), static_cast<float>(q[3]/length)};
}
inline std::array<double, 3> Rotate(const std::array<float, 4>& raw, const std::array<double, 3>& v) {
    const auto q = NormalizedAttitude(raw);
    const std::array<double, 3> t{2*(q[1]*v[2]-q[2]*v[1]), 2*(q[2]*v[0]-q[0]*v[2]), 2*(q[0]*v[1]-q[1]*v[0])};
    return {v[0]+q[3]*t[0]+q[1]*t[2]-q[2]*t[1],
        v[1]+q[3]*t[1]+q[2]*t[0]-q[0]*t[2], v[2]+q[3]*t[2]+q[0]*t[1]-q[1]*t[0]};
}
inline std::array<double, 2> ChaseAngles(const std::array<float, 4>& attitude) {
    // Behind and slightly above the ship, looking along its +Z nose.
    const auto direction = Rotate(attitude, {0, -0.30, 1.0});
    const double norm = std::sqrt(direction[0]*direction[0]+direction[1]*direction[1]+direction[2]*direction[2]);
    return {std::atan2(-direction[0], direction[1]), -std::asin(std::clamp(direction[2]/norm, -1.0, 1.0))};
}
inline double SmoothAngle(double previous, double next, double fraction) {
    return previous + std::remainder(next-previous, 6.283185307179586) * std::clamp(fraction, 0.0, 1.0);
}
inline double FlightEyeDistance(double viewRadiusM) { return viewRadiusM / std::tan(3.14159265358979323846 / 6.0); }

struct EncounterCameraFrame {
    std::array<double, 3> center;
    std::array<double, 3> previousOffset;
    double viewRadiusM;
    double yawRadians;
    double pitchRadians;
};

inline std::optional<EncounterCameraFrame> EncounterFrame(
    const std::array<double, 3>& ship, const std::array<double, 3>& target,
    const std::array<double, 3>& previousShip, const std::array<double, 3>& previousTarget,
    double shipRadiusM, double targetRadiusM) {
    if (!std::isfinite(shipRadiusM) || !std::isfinite(targetRadiusM) ||
        shipRadiusM <= 0 || targetRadiusM <= 0) return std::nullopt;
    EncounterCameraFrame frame{};
    std::array<double, 3> delta{}, previousDelta{};
    for (size_t axis = 0; axis < 3; ++axis) {
        if (!std::isfinite(ship[axis]) || !std::isfinite(target[axis]) ||
            !std::isfinite(previousShip[axis]) || !std::isfinite(previousTarget[axis])) return std::nullopt;
        delta[axis] = target[axis] - ship[axis];
        previousDelta[axis] = previousTarget[axis] - previousShip[axis];
        frame.center[axis] = ship[axis] + delta[axis] * 0.5;
        frame.previousOffset[axis] = (previousShip[axis] - ship[axis]) * 0.5 +
            (previousTarget[axis] - target[axis]) * 0.5;
        if (!std::isfinite(delta[axis]) || !std::isfinite(previousDelta[axis]) ||
            !std::isfinite(frame.center[axis]) || !std::isfinite(frame.previousOffset[axis])) return std::nullopt;
    }
    // Fit both physical envelopes at both ends of the submitted pose interval.
    // 1.35 leaves margin beyond the perspective sphere-fit factor 1/cos(30deg).
    const double extent = std::max(std::hypot(delta[0], delta[1], delta[2]),
        std::hypot(previousDelta[0], previousDelta[1], previousDelta[2])) * 0.5 +
        std::max(shipRadiusM, targetRadiusM);
    frame.viewRadiusM = std::max(20.0, extent * 1.35);
    if (!std::isfinite(extent) || !std::isfinite(frame.viewRadiusM)) return std::nullopt;
    frame.yawRadians = std::atan2(delta[1], delta[0]);
    frame.pitchRadians = 0.55;
    return frame;
}

inline std::array<double, 3> FlightCameraRay(double x, double y, int width, int height) {
    const double shortDimension = std::max(1, std::min(width, height));
    const double tangent = std::tan(3.14159265358979323846 / 6.0);
    const double dx = (2.0*x-width) / shortDimension * tangent;
    const double dy = (height-2.0*y) / shortDimension * tangent;
    const double length = std::sqrt(dx*dx+dy*dy+1.0);
    return {dx/length, dy/length, 1.0/length};
}
inline std::optional<double> RaySphere(const std::array<double,3>& ray,
    const std::array<double,3>& center, double radius) {
    const double along=ray[0]*center[0]+ray[1]*center[1]+ray[2]*center[2];
    const std::array<double,3> closest{center[0]-ray[0]*along, center[1]-ray[1]*along, center[2]-ray[2]*along};
    const double discriminant=radius*radius-(closest[0]*closest[0]+closest[1]*closest[1]+closest[2]*closest[2]);
    if(discriminant<0 || !std::isfinite(discriminant)) return std::nullopt;
    const double root=std::sqrt(std::max(discriminant,0.0));
    double distance=along-root;
    if(distance<0.1) distance=along+root;
    return distance>=0.1 ? std::optional<double>(distance) : std::nullopt;
}
inline double FlightDepth(double cameraDepth) { return std::log2(1.0+std::max(cameraDepth,0.0))/std::log2(1.0e16); }

} // namespace solarlab::spacecraft
