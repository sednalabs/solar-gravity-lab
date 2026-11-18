#include "../../../probes/QcomProbeFixtures.h"

#include <cassert>
#include <iostream>

int main() {
    const auto pixels = solarlab::probes::BoxPixels();
    solarlab::probes::ValidateBoxFixture(pixels);
    const auto margins = solarlab::probes::BoxFixtureSeparation(pixels);
    assert(pixels.size() == 64U * 64U * 4U);
    for (size_t index = 3; index < pixels.size(); index += 4) assert(pixels[index] == 255U);
    // A constant fixture must be rejected: all wrong kernels would pass it.
    bool rejected = false;
    try { solarlab::probes::ValidateBoxFixture(std::vector<uint8_t>(pixels.size(), 128U)); }
    catch (const std::runtime_error&) { rejected = true; }
    assert(rejected);
    std::cout << "QCOM box fixture separates nearest, bilinear and shifted-box alternatives: "
        << margins[0] << ", " << margins[1] << ", " << margins[2] << '\n';
}
