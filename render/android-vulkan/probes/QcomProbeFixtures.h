#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace solarlab::probes {

inline std::vector<uint8_t> BoxPixels() {
    std::vector<uint8_t> pixels(64U * 64U * 4U);
    for (uint32_t y = 0; y < 64U; ++y) {
        for (uint32_t x = 0; x < 64U; ++x) {
            for (uint32_t channel = 0; channel < 3U; ++channel) {
                // Nonlinear deterministic texels distinguish a 4x4 average
                // from a centred 2x2/bilinear sample or an off-by-one centre.
                uint32_t hash = x + 64U * y + channel * 0x9e3779b9U;
                hash ^= hash >> 16U;
                hash *= 0x7feb352dU;
                hash ^= hash >> 15U;
                hash *= 0x846ca68bU;
                hash ^= hash >> 16U;
                pixels[(y * 64U + x) * 4U + channel] = static_cast<uint8_t>(hash);
            }
            pixels[(y * 64U + x) * 4U + 3U] = 255U;
        }
    }
    return pixels;
}

inline double BoxAverage(const std::vector<uint8_t>& pixels, uint32_t x, uint32_t y,
                         uint32_t width, uint32_t channel) {
    double sum = 0.0;
    for (uint32_t dy = 0; dy < width; ++dy) {
        for (uint32_t dx = 0; dx < width; ++dx) {
            // Match the sampler's clamp-to-edge mode for shifted alternatives.
            sum += pixels[(std::min(y + dy, 63U) * 64U + std::min(x + dx, 63U)) * 4U + channel];
        }
    }
    return sum / (255.0 * width * width);
}

inline std::array<double, 3> BoxFixtureSeparation(const std::vector<uint8_t>& pixels) {
    std::array<double, 3> margins{};
    for (uint32_t y = 0; y < 16U; ++y) {
        for (uint32_t x = 0; x < 16U; ++x) {
            for (uint32_t channel = 0; channel < 3U; ++channel) {
                const double expected = BoxAverage(pixels, x * 4U, y * 4U, 4U, channel);
                const std::array<double, 3> wrong{
                    BoxAverage(pixels, x * 4U + 2U, y * 4U + 2U, 1U, channel),
                    BoxAverage(pixels, x * 4U + 1U, y * 4U + 1U, 2U, channel),
                    BoxAverage(pixels, x * 4U + 1U, y * 4U + 1U, 4U, channel)};
                for (size_t index = 0; index < margins.size(); ++index) {
                    margins[index] = std::max(margins[index], std::abs(wrong[index] - expected));
                }
            }
        }
    }
    return margins;
}

inline void ValidateBoxFixture(const std::vector<uint8_t>& pixels) {
    for (double margin : BoxFixtureSeparation(pixels)) {
        if (margin <= 8.0 / 255.0) throw std::runtime_error("box-fixture-does-not-distinguish-wrong-kernels");
    }
}

} // namespace solarlab::probes
