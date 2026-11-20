package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.OrbitGuidance
import com.sednalabs.solarlab.runtime.OrbitRegime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class FlightPlanningPresentationTest {
    @Test fun oneScalePreservesNativeRadiiAndFocus() {
        val s = requireNotNull(flightOrbitShape(6e6, 4e5, 4e5, 4e5, 2e6))
        val a = requireNotNull(s.before); val b = requireNotNull(s.after)
        assertEquals(.75, s.hostRadius, 1e-12)
        assertEquals(0.0, a.centreX, 1e-12); assertEquals(.8, a.major, 1e-12); assertEquals(.8, a.minor, 1e-12)
        assertEquals(-.1, b.centreX, 1e-12); assertEquals(.9, b.major, 1e-12); assertEquals(sqrt(.8), b.minor, 1e-12)
        assertEquals(.8, b.centreX + b.major, 1e-12); assertEquals(-1.0, b.centreX - b.major, 1e-12)
        assertEquals(1.0, s.left, 1e-12); assertEquals(.8, s.right, 1e-12)
    }
    @Test fun openInvalidAndSurfaceCrossingOrbitsRemainHonest() {
        val s = requireNotNull(flightOrbitShape(6.0, 1.0, 2.0, 1.0, null))
        assertNotNull(s.before); assertNull(s.after)
        assertNull(flightOrbitShape(6.0, 1.0, null, 1.0, null))
        assertNull(flightOrbitShape(6.0, 2.0, 1.0, Double.NaN, 4.0))
        assertNull(flightOrbitShape(Double.POSITIVE_INFINITY, 1.0, 2.0, 1.0, 2.0))
        assertNull(flightOrbitShape(-1.0, 2.0, 3.0, 2.0, 3.0))
        assertNull(flightOrbitShape(6.0, -6.0, 8.0, null, null))
        val crossing = requireNotNull(flightOrbitShape(6.0, -2.0, 3.0, null, null))
        assertEquals(4.0 / 9.0, requireNotNull(crossing.before).let { it.centreX + it.major }, 1e-12)
        assertTrue(crossing.hostRadius >= crossing.right)
    }
    @Test fun extremeRadiiAreNormalisedBeforeArithmetic() {
        val s = requireNotNull(flightOrbitShape(1e307, 2e307, 1e308, 4e307, 1.2e308))
        listOfNotNull(s.before, s.after).forEach { assertTrue(listOf(it.centreX, it.major, it.minor).all(Double::isFinite)) }
        assertNull(flightOrbitShape(1e308, 1e308, 1e308, null, null))
    }
    @Test fun timingDoesNotInventMissingEvents() {
        assertEquals("now", flightCountdown(0.0)); assertEquals("in <1 s", flightCountdown(.2))
        assertEquals("in 1 m 0 s", flightCountdown(59.2)); assertEquals("in 1 h 0 m", flightCountdown(3599.2))
        assertEquals("in 1.0 d", flightCountdown(90000.0))
        listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { assertNull(flightCountdown(it)) }
        assertEquals("Apoapsis in 1 m 40 s", flightNavigationHeading(g(OrbitRegime.Elliptic, 500.0, 100.0)))
        assertEquals("Periapsis now", flightNavigationHeading(g(OrbitRegime.Elliptic, 0.0, 100.0)))
        assertEquals("Nearly circular · no distinct apsis", flightNavigationHeading(g(OrbitRegime.NearCircular)))
        assertEquals("no distinct apsis", flightApsisTiming(g(OrbitRegime.NearCircular), true))
        assertEquals("Periapsis in 1 m 40 s", flightNavigationHeading(g(OrbitRegime.Hyperbolic, 100.0)))
        assertEquals("no apoapsis", flightApsisTiming(g(OrbitRegime.Hyperbolic), false))
        assertEquals("Open trajectory · no future periapsis estimate", flightNavigationHeading(g(OrbitRegime.Hyperbolic)))
        assertEquals("Apsis timing unavailable near escape speed", flightNavigationHeading(g(OrbitRegime.NearParabolic)))
        assertEquals("Apsis timing unavailable", flightNavigationHeading(g(OrbitRegime.Elliptic)))
    }
    @Test fun effectsDoNotRoundChangesToZeroOrInferMissingApsides() {
        assertEquals("Periapsis rises 20 m", flightApsisChange("Periapsis", 100.0, 120.0))
        assertEquals("Periapsis falls 200 m", flightApsisChange("Periapsis", 100.0, -100.0))
        assertEquals("Periapsis changes by less than 1 m", flightApsisChange("Periapsis", 100.0, 100.4))
        assertEquals("Apoapsis has no finite estimate after the burn", flightApsisChange("Apoapsis", 100.0, null))
        assertEquals("Apoapsis becomes 100 m", flightApsisChange("Apoapsis", null, 100.0))
        assertEquals("Apoapsis changes beyond the display range", flightApsisChange("Apoapsis", -1e308, 1e308))
    }
    private fun g(regime: OrbitRegime, p: Double? = null, a: Double? = null) = OrbitGuidance(regime, p, a, 20.0)
}
