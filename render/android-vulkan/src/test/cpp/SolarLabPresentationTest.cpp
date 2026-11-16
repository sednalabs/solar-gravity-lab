#include "../../main/cpp/SolarLabPresentation.h"

#include <cassert>
#include <array>
#include <cmath>
#include <limits>

using namespace solarlab::presentation;

void TestOneDayInEarthReferenceFrame() {
    constexpr double lunarDistanceM = 384'400'000.0;
    const std::array<double, 3> earth{-26'000'000'000.0, 132'000'000'000.0, 57'000'000'000.0};
    // Synthetic common solar translation: 30 km/s for one simulated day, plus all-axis motion.
    const std::array<double, 3> dayTranslation{200'000'000.0, 30'000.0 * 86'400.0, -80'000'000.0};
    const std::array<double, 3> moonOffset{lunarDistanceM, 0.0, 0.0};
    const std::array<double, 3> camera{
        earth[0] + lunarDistanceM * 0.5 + 13'000'000.0,
        earth[1] - 10'000'000.0,
        earth[2] + 5'000'000.0,
    };
    std::array<double, 3> nextEarth{};
    std::array<double, 3> nextCamera{};
    for (size_t axis = 0; axis < earth.size(); ++axis) {
        nextEarth[axis] = earth[axis] + dayTranslation[axis];
        nextCamera[axis] = TranslateWithReference(camera[axis], earth[axis], nextEarth[axis]);
        assert(std::abs((nextEarth[axis] - nextCamera[axis]) - (earth[axis] - camera[axis])) < 1e-4);
        assert(std::abs(TranslateWithReference(nextCamera[axis], nextEarth[axis], earth[axis]) - camera[axis]) < 1e-4);
        // Camera and body interpolation preserve pair placement at every displayed fraction.
        for (double fraction : {0.0, 0.25, 0.5, 0.75, 1.0}) {
            const double displayedCamera = Mix(camera[axis], nextCamera[axis], fraction);
            const double displayedEarth = Mix(earth[axis], nextEarth[axis], fraction);
            const double displayedMoon = Mix(earth[axis] + moonOffset[axis],
                nextEarth[axis] + moonOffset[axis], fraction);
            assert(std::abs((displayedEarth - displayedCamera) - (earth[axis] - camera[axis])) < 1e-4);
            assert(std::abs((displayedMoon - displayedCamera) - (earth[axis] + moonOffset[axis] - camera[axis])) < 1e-4);
        }
    }
    const auto viewport = MakeProjection(270'000'000.0, 1080, 2400);
    // The old fixed camera loses the pair; the translated, panned camera keeps both visible.
    assert(std::abs(nextEarth[1] - camera[1]) > viewport.halfSpanY);
    assert(std::abs(nextEarth[0] - nextCamera[0]) < viewport.halfSpanX);
    assert(std::abs(nextEarth[1] - nextCamera[1]) < viewport.halfSpanY);
    // Lunar orbital motion remains visible, rather than freezing both bodies in place.
    const double lunarDayAngle = 0.23;
    const double moonDayX = nextEarth[0] + lunarDistanceM * std::cos(lunarDayAngle);
    const double moonDayY = nextEarth[1] + lunarDistanceM * std::sin(lunarDayAngle);
    assert(std::abs(moonDayX - nextCamera[0]) < viewport.halfSpanX);
    assert(std::abs(moonDayY - nextCamera[1]) < viewport.halfSpanY);
    assert(moonDayY - nextEarth[1] > 80'000'000.0);

    // Detach/pan mid-frame, then advance again: retain the displayed local offset, not a
    // displacement measured from the previous snapshot's as-yet-undisplayed endpoint.
    const double presentedEarth = Mix(earth[1], nextEarth[1], 0.4);
    const double pannedCamera = Mix(camera[1], nextCamera[1], 0.4) + 20'000'000.0;
    const double laterEarth = nextEarth[1] + dayTranslation[1];
    const double laterCamera = TranslateWithReference(pannedCamera, presentedEarth, laterEarth);
    assert(std::abs((laterCamera - laterEarth) - (pannedCamera - presentedEarth)) < 1e-4);
}

void TestProbeTrailFollowsDisplayedEarth() {
    constexpr double orbitRadiusM = 6'371'010.0 + 800'000.0;
    // Positions are rebased to the latest Earth. At 1 h/s and 30 km/s, a 100 ms
    // tick translates Earth by 10,800 km, farther than this probe's orbit radius.
    const std::array<double, 3> previousEarth{-800'000.0, -10'800'000.0, 400'000.0};
    const std::array<double, 3> currentEarth{0.0, 0.0, 0.0};
    const std::array<double, 3> pan{1'000'000.0, -500'000.0, 250'000.0};
    assert(std::abs(previousEarth[1]) > orbitRadiusM);
    for (float fraction : {0.0f, 0.1f, 0.5f, 0.9f, 1.0f}) {
        for (double angle : {0.0, 1.0, 2.0, 3.0, 4.0, 5.0}) {
            const std::array<double, 3> orbitPoint{
                orbitRadiusM * std::cos(angle), orbitRadiusM * std::sin(angle), 0.0,
            };
            for (size_t axis = 0; axis < orbitPoint.size(); ++axis) {
                const double displayedEarth = Mix(previousEarth[axis], currentEarth[axis], fraction);
                const float translation = static_cast<float>(PresentationReferenceOffset(
                    previousEarth[axis] - currentEarth[axis], fraction));
                // Mirror the actual packed vertex/uniform arithmetic, including f32 rounding.
                const float presentedTrail = static_cast<float>(orbitPoint[axis]) + translation;
                const float presentedCamera = static_cast<float>(displayedEarth + pan[axis]);
                const double localTrail = static_cast<double>(presentedTrail) - presentedCamera;
                assert(std::abs(localTrail - (orbitPoint[axis] - pan[axis])) < 2.0);
                if (fraction == 1.0f) assert(translation == 0.0f);
            }
        }
        // The default for other scenarios and legacy submissions is an exact zero shift.
        assert(PresentationReferenceOffset(0.0, fraction) == 0.0);
    }
}

int main() {
    TestOneDayInEarthReferenceFrame();
    TestProbeTrailFollowsDisplayedEarth();
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
