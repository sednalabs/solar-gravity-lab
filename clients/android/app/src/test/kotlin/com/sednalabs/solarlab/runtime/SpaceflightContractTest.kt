package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceflightContractTest {
    @Test fun radialAssistCodesRemainStableAcrossTheNativeBoundary() {
        assertEquals(6, FlightAssist.RadialOut.nativeCode)
        assertEquals(7, FlightAssist.RadialIn.nativeCode)
        assertEquals(FlightAssist.RadialOut, FlightAssist.fromNative(6))
        assertEquals(FlightAssist.RadialIn, FlightAssist.fromNative(7))
        assertEquals(7.0, FlightControl(assist = FlightAssist.RadialIn).toNativeValues()[7], 0.0)
    }
    @Test fun controlPacketHasStableAxesAndRejectsInvalidInputs() {
        val command = FlightControl(.6, -.2, .3, .4, -.5, .6, -.7, FlightAssist.Retrograde)
        assertArrayEquals(doubleArrayOf(.6, -.2, .3, .4, -.5, .6, -.7, 3.0), command.toNativeValues(), 0.0)
        assertTrue(command.requiresRealTime)
        assertFalse(FlightControl.Cut.requiresRealTime)
        listOf(FlightControl(throttle = Double.NaN), FlightControl(rotationZ = 1.01), FlightControl(throttle = -0.1)).forEach {
            var rejected = false
            try { it.toNativeValues() } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }

    @Test fun telemetryDecodesVersionedPhysicalValuesWithoutChangingUnits() {
        val values = referenceSample()
        val sample = flightStateFromNative(values)
        assertEquals(800_000.25, sample.altitudeM, 0.0)
        assertEquals(2400.0, sample.propellantMassKg, 0.0)
        assertEquals(FlightAssist.Prograde, sample.assist)
        assertEquals(0.25, sample.commandedThrottle, 0.0)
        assertEquals(6.0, sample.actualMainAccelerationMps2, 0.0)
        assertEquals(values[37], sample.control.translationX, 0.0)
        assertEquals(values[42], sample.control.rotationZ, 0.0)
        assertTrue(sample.hasBoundOrbit)
    }

    @Test fun escapeTrajectoryDoesNotInventAnApoapsisOrPeriod() {
        val sample = flightStateFromNative(referenceSample().apply {
            this[13] = Double.POSITIVE_INFINITY; this[16] = Double.NaN; this[36] = 0.0; this[27] = -1.0
        })
        assertTrue(sample.hasValidOrbit)
        assertFalse(sample.hasBoundOrbit)
        assertNull(sample.apoapsisAltitudeM)
        assertNull(sample.periodSeconds)
        assertNull(sample.moonSurfaceDistanceM)
    }

    @Test fun incompatibleNativeSchemaIsRejected() {
        for (values in listOf(DoubleArray(43), referenceSample().apply { this[0] = 2.0 },
            referenceSample().apply { this[19] = Double.NaN })) {
            var rejected = false
            try { flightStateFromNative(values) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }

    private fun referenceSample() = DoubleArray(44).apply {
        this[0] = 1.0; this[1] = 123.5; this[2] = 23.0
        this[3] = 1600.0; this[4] = 2400.0; this[5] = 4000.0; this[6] = 96_000.0
        this[7] = .25; this[8] = 2400.0; this[9] = 800_000.25; this[10] = 7450.0
        this[12] = 800_000.0; this[13] = 900_000.0; this[14] = .007; this[15] = .1; this[16] = 6100.0
        this[20] = 1.0; this[24] = 2.0; this[25] = 7.0; this[26] = 200.0; this[27] = 380_000_000.0
        this[28] = 7_171_000.25; this[32] = 7450.0; this[34] = 6_371_000.0
        this[35] = 1.0; this[36] = 1.0; this[37] = .3; this[42] = -.2; this[43] = 6.0
    }
}
