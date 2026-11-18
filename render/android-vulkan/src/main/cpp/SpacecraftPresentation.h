#pragma once
#include <array>
#include <algorithm>
#include <cmath>

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
} // namespace solarlab::spacecraft
