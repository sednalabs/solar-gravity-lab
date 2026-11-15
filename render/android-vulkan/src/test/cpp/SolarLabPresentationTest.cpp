#include "../../main/cpp/SolarLabPresentation.h"

#include <cassert>
#include <cmath>
#include <limits>

using namespace solarlab::presentation;

int main() {
    const auto portrait = MakeProjection(400.0, 600, 1200);
    const auto landscape = MakeProjection(400.0, 1200, 600);
    assert(portrait.halfSpanX == 400.0);
    assert(portrait.halfSpanY == 800.0);
    assert(landscape.halfSpanX == 800.0);
    assert(landscape.halfSpanY == 400.0);
    assert(portrait.metersPerPixel == landscape.metersPerPixel);
    // A point 200 m above centre moves by the same pixel distance in either orientation.
    const double portraitY = (1.0 - (200.0 / portrait.halfSpanY * 0.5 + 0.5)) * 1200.0;
    const double landscapeY = (1.0 - (200.0 / landscape.halfSpanY * 0.5 + 0.5)) * 600.0;
    assert(std::abs((600.0 - portraitY) - (300.0 - landscapeY)) < 1e-10);

    const auto near = HitSphere(10.0, 0.0, 30.0, -100.0, portrait);
    const auto far = HitSphere(0.0, 0.0, 30.0, 100.0, portrait);
    assert(near && far && PreferHit(*near, *far));
    assert(!PreferHit(*far, *near));
    const auto padding = HitSphere(10.0, 0.0, 2.0, -1000.0, portrait);
    assert(padding && !padding->insideVisibleDisc);
    assert(PreferHit(*far, *padding));
    assert(!HitSphere(19.0, 0.0, 2.0, 0.0, portrait));
    assert(!HitSphere(0.0, 0.0, 2.0, portrait.halfDepth + 10.0, portrait));
    assert(!HitSphere(std::numeric_limits<double>::quiet_NaN(), 0.0, 2.0, 0.0, portrait));
    const auto largeRear = HitSphere(0.0, 0.0, 200.0, 0.0, portrait);
    assert(largeRear && PreferHit(*largeRear, *near));

    assert(InterpolationFraction(1.0, 1.0, 0.5) == 0.0);
    assert(InterpolationFraction(1.25, 1.0, 0.5) == 0.5);
    assert(InterpolationFraction(2.0, 1.0, 0.5) == 1.0);
    assert(InterpolationFraction(0.0, 1.0, 0.5) == 0.0);
    assert(InterpolationFraction(1.0, 1.0, 0.0) == 1.0);
    assert(Mix(100.0, 200.0, 0.5) == 150.0);
    const double earthRadiusM = 6'371'010.0;
    const double probeDistanceM = earthRadiusM + 800'000.0;
    // A frame centred on the probe includes the far edge of Earth, with padding.
    assert(ContextFrameRadius(1'000.0, probeDistanceM, earthRadiusM) > probeDistanceM + earthRadiusM);
    assert(ContextFrameRadius(50'000'000.0, probeDistanceM, earthRadiusM) == 50'000'000.0);
}
