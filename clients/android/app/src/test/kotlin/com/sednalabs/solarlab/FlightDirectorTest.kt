package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightState
import com.sednalabs.solarlab.runtime.flightStateFromNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class FlightDirectorTest {
    @Test fun knownBodyAxesPlaceTargetsAtTheCorrectSightPositions() {
        val state = sample() // Position +X, motion +Z; identity attitude faces +Z.
        val prograde = requireNotNull(flightDirector(state, FlightAssist.Prograde))
        assertTrue(prograde.aligned)
        assertEquals(0.0, prograde.markerX, 1e-12)
        assertEquals(0.0, prograde.markerY, 1e-12)
        val radial = requireNotNull(flightDirector(state, FlightAssist.RadialOut))
        assertEquals(90.0, radial.errorDegrees, 1e-10)
        assertEquals(1.0, radial.markerX, 1e-12)
        assertEquals(-1.0, requireNotNull(flightDirector(state, FlightAssist.Normal)).markerY, 1e-12)
        assertEquals(1.0, requireNotNull(flightDirector(state, FlightAssist.AntiNormal)).markerY, 1e-12)
    }

    @Test fun inverseAttitudeAndQuaternionSignPreserveThePhysicalTarget() {
        // +90 degrees about +Y maps the ship nose from +Z to +X.
        val angle = sqrt(.5)
        val state = sample().copy(attitudeY = angle, attitudeW = angle)
        assertTrue(requireNotNull(flightDirector(state, FlightAssist.RadialOut)).aligned)
        val prograde = requireNotNull(flightDirector(state, FlightAssist.Prograde))
        assertEquals(-1.0, prograde.markerX, 1e-12)
        assertEquals(prograde, flightDirector(state.copy(attitudeY = -angle, attitudeW = -angle), FlightAssist.Prograde))
    }

    @Test fun exactAftIsNeverPresentedAsAlignedAndManualUsesReferenceOnly() {
        val retro = requireNotNull(flightDirector(sample(), FlightAssist.Retrograde))
        assertTrue(retro.targetBehind)
        assertFalse(retro.aligned)
        assertEquals(180.0, retro.errorDegrees, 1e-10)
        assertEquals(1.0, retro.markerY, 1e-12)
        assertEquals(FlightAssist.Prograde, flightDirector(sample(), FlightAssist.Hold)?.target)
        assertEquals(FlightAssist.Prograde, flightDirector(sample(), FlightAssist.Manual)?.target)
    }

    @Test fun degenerateMotionDoesNotInventAnOrbitalPlaneOrAQuaternion() {
        val stopped = sample().copy(relativeVelocityZ = 0.0)
        assertNull(flightDirector(stopped, FlightAssist.Prograde))
        assertNull(flightDirector(stopped, FlightAssist.Normal))
        assertNotNull(flightDirector(stopped, FlightAssist.RadialOut))
        assertNull(flightDirector(sample().copy(attitudeW = 0.0), FlightAssist.Prograde))
        assertNull(flightDirector(sample().copy(relativeVelocityX = 100.0, relativeVelocityZ = 0.0), FlightAssist.Normal))
    }

    @Test fun warningsFollowNativeOrbitValidityAndSurfaceIntersection() {
        assertNull(flightOrbitCaution(sample()))
        assertTrue(requireNotNull(flightOrbitCaution(sample().copy(periapsisAltitudeM = -1.0))).contains("Surface-crossing"))
        assertTrue(requireNotNull(flightOrbitCaution(sample().copy(hasBoundOrbit = false))).contains("no finite apoapsis"))
        assertTrue(requireNotNull(flightOrbitCaution(sample().copy(hasValidOrbit = false))).contains("unavailable"))
        assertEquals(1.0, requireNotNull(flightDirector(sample().copy(angularVelocityX = PI / 180.0), FlightAssist.Hold)).spinDegreesPerSecond, 1e-10)
    }

    private fun sample(): FlightState = flightStateFromNative(DoubleArray(44).apply {
        this[0] = 1.0; this[1] = 100.0; this[2] = 4.0
        this[3] = 1600.0; this[4] = 2400.0; this[5] = 4000.0; this[6] = 96_000.0
        this[8] = 2400.0; this[9] = 800_000.0; this[10] = 7450.0
        this[12] = 800_000.0; this[13] = 900_000.0; this[14] = .007; this[15] = .1; this[16] = 6100.0
        this[20] = 1.0; this[24] = 2.0; this[26] = 200.0
        this[28] = 7_171_000.0; this[33] = 7450.0; this[34] = 6_371_000.0
        this[35] = 1.0; this[36] = 1.0
    })
}
