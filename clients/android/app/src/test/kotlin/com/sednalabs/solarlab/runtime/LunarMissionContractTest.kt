package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LunarMissionContractTest {
    @Test fun nativeBurnAndObservedApproachRemainIndependentOfWallTime() {
        val mission = lunarMissionStateFromNative(sample().apply {
            this[1] = 200.0; this[3] = 200.0; this[4] = 1.0
            this[7] = 125.25; this[8] = 174.75; this[9] = 1.0; this[10] = 174.75
        })
        assertEquals(125.25, mission.actualMainFullThrottleSeconds, 0.0)
        assertEquals(174.75, mission.guidedBurnRemainingSeconds, 0.0)
        assertEquals(.4175f, mission.departureProgress, 1e-6f)
        assertEquals(-803.5, mission.moonRangeRateMps, 0.0)
        assertEquals(LunarMissionPhase.PoweredFlight, mission.phase)
        assertTrue(mission.guidedBurnActive)
        assertFalse(mission.hasOutcome)
    }

    @Test fun completedFlybyUsesNativeOutcomeAndSavedClosestObservation() {
        val mission = lunarMissionStateFromNative(sample().apply {
            this[4] = 4.0; this[5] = 1.0; this[7] = 300.0; this[8] = 0.0
            this[15] = 481_234.567; this[16] = 225_456.75
        })
        assertTrue(mission.hasOutcome)
        assertEquals(LunarMissionOutcome.FlybyAchieved, mission.outcome)
        assertEquals(481_234.567, mission.closestObservedMoonAltitudeM, 0.0)
        assertEquals(225_456.75, mission.closestObservedEpochSeconds, 0.0)
        assertFalse(mission.surfaceResponseModelled)
    }

    @Test fun incompatibleOrMalformedMissionPacketsAreRejected() {
        val invalid = listOf(DoubleArray(20), sample().apply { this[0] = 2.0 },
            sample().apply { this[4] = 1.25 }, sample().apply { this[5] = 9.0 },
            sample().apply { this[13] = Double.NaN }, sample().apply { this[6] = 0.0 },
            sample().apply { this[9] = 2.0 }, sample().apply { this[17] = -1.0 })
        invalid.forEach { values ->
            assertTrue(runCatching { lunarMissionStateFromNative(values) }.isFailure)
        }
    }

    private fun sample() = DoubleArray(21).apply {
        this[0] = 1.0; this[6] = 300.0; this[8] = 300.0
        this[11] = 400_000.0; this[12] = 380_000_000.0; this[13] = -803.5; this[14] = 7_000.0
        this[15] = 380_000_000.0; this[17] = 8_000.0
        this[19] = 223_776.0; this[20] = 227_232.0
    }
}
