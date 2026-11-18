#include "../../main/cpp/SolarLabPresentation.h"
#include "../../main/cpp/SolarLabGpuReuse.h"

#include <cassert>
#include <array>
#include <cmath>
#include <limits>

using namespace solarlab::presentation;

void TestMapPlaneAndOverlayProjection() {
    const double pi = std::acos(-1.0);
    for (const auto normal : {std::array<double, 3>{0.0, 0.0, 1.0},
             std::array<double, 3>{1.0, 2.0, 3.0}, std::array<double, 3>{1.0, 2.0, -3.0},
             std::array<double, 3>{1.0, 0.0, 0.0}}) {
        const double magnitude = std::sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
        const auto angles = MapCameraAngles(normal[0], normal[1], normal[2]);
        const auto basis = MakeCameraBasis(angles[0], angles[1]);
        for (size_t axis = 0; axis < 3; ++axis) {
            assert(std::abs(basis.forward[axis] + normal[axis] / magnitude) < 1e-12);
        }
    }

    // Known fixtures shared with the Kotlin viewport project/unproject tests.
    const std::array<double, 3> point{200.0, -100.0, 400.0};
    const auto dot = [&](const std::array<double, 3>& axis) {
        return point[0] * axis[0] + point[1] * axis[1] + point[2] * axis[2];
    };
    for (const auto dimensions : {std::array<int, 2>{600, 1200}, std::array<int, 2>{1200, 600}}) {
        const auto projection = MakeProjection(400.0, dimensions[0], dimensions[1]);
        const auto overhead = MakeCameraBasis(0.0, pi * 0.5);
        assert(std::abs(dot(overhead.right) / projection.metersPerPixel - 150.0) < 1e-8);
        assert(std::abs(-dot(overhead.up) / projection.metersPerPixel - 75.0) < 1e-8);
        assert(std::abs(dot(overhead.forward) + 400.0) < 1e-8);
        const auto inclined = MakeCameraBasis(pi * 0.5, pi / 6.0);
        assert(std::abs(dot(inclined.right) / projection.metersPerPixel + 75.0) < 1e-8);
        assert(std::abs(-dot(inclined.up) / projection.metersPerPixel + 184.8076211353316) < 1e-8);
        assert(std::abs(dot(inclined.forward) + 373.2050807568877) < 1e-8);
    }
}

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

void TestFramingWhileTheReferenceMoves() {
    constexpr double earthRadiusM = 6'371'010.0;
    constexpr double initialReference = 132'000'000'000.0;
    constexpr double tickTranslation = 30'000.0 * 3'600.0 * 0.1;
    constexpr double startOffset = 80'000'000.0;
    const auto projection = MakeProjection(earthRadiusM * 2.0, 1080, 1800);
    // Frame a moving body during multiple 100 ms snapshots. The last step centres it
    // exactly even though its absolute motion has exceeded the whole close-up viewport.
    for (int tick = 0; tick < 4; ++tick) {
        const double previous = initialReference + tick * tickTranslation;
        const double current = previous + tickTranslation;
        for (double displayFraction : {0.0, 0.3, 0.7, 1.0}) {
            for (double transitionFraction : {0.0, 0.25, 0.75, 1.0}) {
                const double localOffset = Mix(startOffset, 0.0, transitionFraction);
                const auto camera = CameraInReference(localOffset, previous, current);
                const double displayedCamera = camera.current +
                    PresentationReferenceOffset(camera.previousOffset, displayFraction);
                const double displayedBody = Mix(previous, current, displayFraction);
                assert(std::abs(displayedCamera - displayedBody - localOffset) < 1e-4);
                if (transitionFraction == 1.0) {
                    assert(std::abs((displayedBody - displayedCamera) / projection.metersPerPixel) < 1e-6);
                }
            }
        }
    }
    assert(4.0 * tickTranslation > 2.0 * projection.halfSpanY);
    // A lunar probe should retain nearby lunar context, not widen to an Earth-centred view.
    constexpr double lunarRadius = 1'737'400.0;
    constexpr double lunarProbeDistance = lunarRadius + 800'000.0;
    constexpr double earthProbeDistance = 384'400'000.0 - lunarProbeDistance;
    assert(ContextSurfaceDistance(lunarProbeDistance, lunarRadius) <
        ContextSurfaceDistance(earthProbeDistance, earthRadiusM));
    assert(ContextFrameRadius(1'000.0, lunarProbeDistance, lunarRadius) < 6'000'000.0);
}

void TestGpuBufferAndCommandReuse() {
    using namespace solarlab::rendering;
    // Scene hashes use all 64 bits. A paused rendezvous advance changes poses
    // without moving the camera controls, so it must still invalidate GPU data
    // when both old and new hashes have their high bit set.
    constexpr uint64_t departure = 0x8000000000000061ULL;
    constexpr uint64_t arrival = 0x80000000000002EBULL;
    const auto departureRevision = SceneUploadRevision(departure, 17, 0, 1, 0);
    const auto arrivalRevision = SceneUploadRevision(arrival, 17, 0, 1, 0);
    assert(departureRevision != arrivalRevision);
    assert(arrivalRevision == SceneUploadRevision(arrival, 17, 0, 1, 0));
    assert(arrivalRevision != SceneUploadRevision(arrival, 18, 0, 1, 0));
    assert(arrivalRevision != SceneUploadRevision(arrival ^ (1ULL << 63), 17, 0, 1, 0));
    assert(arrivalRevision > 0 && arrivalRevision <= static_cast<uint64_t>(std::numeric_limits<int64_t>::max()));
    // A buffer is reusable only for the required capacity, binding usage and actual
    // memory capabilities. Host-visible fallback cannot masquerade as device-local.
    assert(CanReuseBuffer(1024, 768, 3, 3, 7, 6));
    assert(!CanReuseBuffer(1024, 1025, 3, 3, 7, 6));
    assert(!CanReuseBuffer(1024, 768, 3, 5, 7, 6));
    assert(!CanReuseBuffer(1024, 768, 3, 3, 6, 1));
    assert(BufferCapacity(256) == 256);
    assert(BufferCapacity(257) == 512);
    assert(BufferCapacity(std::numeric_limits<uint64_t>::max()) == std::numeric_limits<uint64_t>::max());
    uint64_t capacity = 0;
    int allocations = 0;
    for (uint64_t vertices = 1; vertices <= 512; ++vertices) {
        const uint64_t required = vertices * 20;
        if (!CanReuseBuffer(capacity, required, 1, 1, 6, 6)) {
            capacity = BufferCapacity(required);
            ++allocations;
        }
        assert(capacity >= required);
        assert(capacity <= std::max<uint64_t>(256, 2 * required));
    }
    // The former full destruction path allocated 512 times for this growing stream.
    assert(allocations == 7);

    SceneCommandState recorded;
    recorded.draws[0] = {1, 3};
    recorded.draws[4] = {2, 256};
    recorded.trailStrips = {128, 128};
    recorded.medium = {true, {3, 4, 5, 6, 0, 0}, 1024, 16, 0, 0};
    recorded.far = {true, {7, 8, 9, 10, 11, 12}, 4096, 64, 800, 100};
    assert(recorded == SceneCommandState(recorded));
    // New payload bytes and camera uniforms use these same bindings; every recorded
    // count, indirect dependency, fallback or strip boundary must invalidate reuse.
    auto changed = recorded;
    ++changed.draws[0].vertexCount;
    assert(changed != recorded);
    changed = recorded;
    ++changed.draws[0].bufferGeneration;
    assert(changed != recorded);
    for (size_t index = 0; index < recorded.far.bufferGenerations.size(); ++index) {
        changed = recorded;
        ++changed.far.bufferGenerations[index];
        assert(changed != recorded);
    }
    changed = recorded;
    changed.medium.enabled = false;
    assert(changed != recorded);
    changed = recorded;
    ++changed.medium.sourceCount;
    assert(changed != recorded);
    changed = recorded;
    ++changed.far.dispatchCount;
    assert(changed != recorded);
    changed = recorded;
    ++changed.far.outputCapacity;
    assert(changed != recorded);
    changed = recorded;
    ++changed.far.tileCounterCount;
    assert(changed != recorded);
    changed = recorded;
    changed.trailStrips = {127, 129};
    assert(changed != recorded);
}

void TestExclusiveTracerPresentation() {
    TracerDrawOwnership owners;
    assert(!owners.Contains("packet-selected"));
    owners.Record("packet-selected");
    owners.Record("ui-selected");
    owners.Record("packet-selected");
    assert(owners.Contains("packet-selected"));
    assert(owners.Contains("ui-selected"));
    assert(!owners.Contains("ordinary-tracer"));
    // Ordinary sources stay in the authoritative surface layer.
    assert(DrawAsAuthoritativeBody(false, false, false, 6'371'000.0f, 0.0f));
    assert(DrawAsAuthoritativeBody(false, false, false, 1.0f, 1'500.0f));
    assert(!DrawAsAuthoritativeBody(false, false, false, 1.0f, 0.0f));
    // BodyClass::Tracer has one ordinary GPU stream regardless of radius.
    assert(!DrawAsAuthoritativeBody(true, false, false, 5'000'000.0f, 2'000.0f));
    assert(!DrawAsAuthoritativeBody(true, false, false, 10.0f, 0.0f));
    // Selected tracer moves to the billboard layer and is filtered by ID
    // from the ordinary GPU tracer stream.
    assert(DrawAsAuthoritativeBody(true, false, true, 10.0f, 0.0f));
    // Unlike tracers, spacecraft are never emitted by extract_scene_tracers:
    // they must stay visible as billboards even without selection.
    assert(DrawAsAuthoritativeBody(false, true, false, 10.0f, 0.0f));
    assert(DrawAsAuthoritativeBody(false, true, true, 10.0f, 0.0f));
    assert(DrawAsAuthoritativeBody(false, false, true, 10.0f, 0.0f));
}

int main() {
    TestExclusiveTracerPresentation();
    TestMapPlaneAndOverlayProjection();
    TestOneDayInEarthReferenceFrame();
    TestProbeTrailFollowsDisplayedEarth();
    TestFramingWhileTheReferenceMoves();
    TestGpuBufferAndCommandReuse();
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
    // A 48 dp target retains its physical touch area on high-density displays.
    assert(!HitSphere(40.0, 0.0, 2.0, 0.0, portrait, 24.0));
    assert(HitSphere(40.0, 0.0, 2.0, 0.0, portrait, 24.0 * 2.0));
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
