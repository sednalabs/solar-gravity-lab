package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockingContractTest {
    @Test fun portFrameSignsAndNativeLimitsSurviveBoundary() {
        val state = dockingStateFromNative(sample().apply {
            this[1] = 42.5; this[2] = 1.0; this[3] = 2.0; this[5] = 1.0
            this[8] = -.03; this[10] = .03; this[11] = -.04; this[12] = .12
            this[13] = -.001; this[14] = .002; this[15] = .03; this[16] = .04; this[17] = .005
        }, "station.native.identity")
        assertEquals("station.native.identity", state.targetBodyId)
        assertEquals(DockingGuidance.Approach, state.guidance)
        assertEquals(DockingStatus.Approaching, state.status)
        assertEquals(.05, state.lateralErrorM, 1e-15)
        assertEquals(-.04, state.positionPortYM, 0.0)
        assertEquals(.12, state.positionPortZM, 0.0)
        assertEquals(-.03, state.closingSpeedMps, 0.0)
        assertEquals(.03, state.velocityPortZMps, 0.0)
        assertEquals(.04, state.alignmentErrorRad, 0.0)
        assertEquals(.05, state.captureLateralLimitM, 0.0)
        assertEquals(.15, state.captureGapLimitM, 0.0)
        assertEquals(.1, state.captureSpeedLimitMps, 0.0)
        assertTrue(state.armed)
        assertFalse(state.attached)
    }

    @Test fun attachmentAndUnsafeContactAreNativeObservationsNotInferredFromGap() {
        val near = dockingStateFromNative(sample().apply { this[12] = .01 }, "station")
        assertFalse(near.attached)
        assertFalse(near.captureReady)
        val attached = dockingStateFromNative(sample().apply {
            this[3] = 4.0; this[6] = 1.0; this[19] = 1.0; this[12] = -.001
        }, "station")
        assertTrue(attached.attached)
        assertTrue(attached.unsafeContact)
        assertEquals(-.001, attached.positionPortZM, 0.0)
        assertEquals(DockingStatus.Docked, attached.status)
    }

    @Test fun missingTargetRetainsIdentityWithoutInventingAnAttachment() {
        val state = dockingStateFromNative(sample().apply { this[3] = 8.0; this[4] = 0.0 }, "missing.station")
        assertFalse(state.targetAvailable)
        assertFalse(state.attached)
        assertEquals(DockingStatus.TargetUnavailable, state.status)
        assertEquals("missing.station", state.targetBodyId)
    }

    @Test fun malformedNativePacketsAndUnknownEnumsFailClosed() {
        val invalid = listOf(DoubleArray(24), sample().apply { this[0] = 2.0 },
            sample().apply { this[2] = .5 }, sample().apply { this[3] = 9.0 },
            sample().apply { this[5] = .5 }, sample().apply { this[6] = -1.0 },
            sample().apply { this[8] = Double.NaN }, sample().apply { this[9] = -1.0 },
            sample().apply { this[16] = 4.0 }, sample().apply { this[17] = -1.0 },
            sample().apply { this[19] = 2.0 }, sample().apply { this[20] = -1.0 },
            sample().apply { this[22] = 0.0 }, sample().apply { this[24] = -.1 })
        invalid.forEach { assertTrue(runCatching { dockingStateFromNative(it, "station") }.isFailure) }
        assertTrue(runCatching { dockingStateFromNative(sample(), " ") }.isFailure)
    }

    private fun sample() = DoubleArray(25).apply {
        this[0] = 1.0; this[4] = 1.0; this[7] = 30.0; this[12] = 30.0
        this[20] = 8_000.0; this[21] = 37.56; this[22] = .05; this[23] = .15; this[24] = .1
    }
}
