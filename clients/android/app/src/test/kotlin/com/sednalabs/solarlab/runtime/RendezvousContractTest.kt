package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendezvousContractTest {
    @Test fun nativeFramesAndPersistedObservationsSurviveDecoding() {
        val state = rendezvousStateFromNative(sample().apply {
            this[1] = 321.5; this[2] = 321.5; this[3] = 1.0; this[4] = 3.0
            this[6] = 75.2; this[7] = -.04; this[8] = .087
            this[9] = 75.2; this[13] = .003; this[15] = .6; this[17] = .8
            this[20] = .23; this[21] = 74.9; this[22] = 320.0; this[23] = 12.3; this[25] = 1.0
        }, "station.persisted.identity")
        assertEquals("station.persisted.identity", state.targetBodyId)
        assertEquals(RendezvousGuidance.Approach, state.guidance)
        assertEquals(RendezvousStatus.Holding, state.status)
        assertEquals(-.04, state.closingSpeedMps, 0.0)
        assertEquals(.003, state.velocityTargetY, 0.0)
        assertEquals(.6, state.targetDirectionX, 0.0)
        assertEquals(74.9, state.closestObservedRangeM, 0.0)
        assertEquals(320.0, state.closestObservedEpochSeconds, 0.0)
        assertTrue(state.standOffAchieved)
        assertFalse(state.keepOutEntered)
    }

    @Test fun targetLossRetainsIdentityAndCompletionWithoutInventedGeometry() {
        val state = rendezvousStateFromNative(sample().apply {
            this[4] = 7.0; this[5] = 0.0; this[6] = 0.0
            this[21] = 74.9; this[25] = 1.0; this[26] = 1.0
        }, "deleted.station")
        assertFalse(state.targetAvailable)
        assertEquals(RendezvousStatus.TargetUnavailable, state.status)
        assertEquals(RendezvousGuidance.Manual, state.guidance)
        assertEquals("deleted.station", state.targetBodyId)
        assertTrue(state.standOffAchieved && state.keepOutEntered)
    }

    @Test fun unknownModesAndMalformedNativePacketsAreRejected() {
        val malformed = listOf(DoubleArray(26), sample().apply { this[0] = 2.0 },
            sample().apply { this[3] = 1.25 }, sample().apply { this[4] = 9.0 },
            sample().apply { this[5] = .5 }, sample().apply { this[7] = Double.NaN },
            sample().apply { this[8] = -1.0 }, sample().apply { this[18] = 80.0 },
            sample().apply { this[24] = -1.0 }, sample().apply { this[25] = 2.0 })
        malformed.forEach { assertTrue(runCatching { rendezvousStateFromNative(it, "station") }.isFailure) }
        assertTrue(runCatching { rendezvousStateFromNative(sample(), " ") }.isFailure)
    }

    private fun sample() = DoubleArray(27).apply {
        this[0] = 1.0; this[5] = 1.0; this[6] = 1_000.0
        this[18] = 25.0; this[19] = 75.0; this[21] = 1_000.0; this[24] = 8_000.0
    }
}
