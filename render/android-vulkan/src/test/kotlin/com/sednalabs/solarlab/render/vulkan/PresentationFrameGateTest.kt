package com.sednalabs.solarlab.render.vulkan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationFrameGateTest {
    @Test
    fun inputAndSnapshotBurstCoalescesWithoutLosingRefresh() {
        val gate = PresentationFrameGate()
        gate.request(false)
        assertFalse(gate.begin(true)!!.refreshRuntimeScene)
        repeat(20) { gate.request(false) }
        gate.request(true)
        assertNull(gate.begin(true))
        gate.complete(false)
        assertTrue(gate.begin(true)!!.refreshRuntimeScene)
        gate.complete(false)
        assertNull(gate.begin(true))
    }

    @Test
    fun interpolationOnlyFramesReuseTheSceneThenStop() {
        val gate = PresentationFrameGate()
        gate.request()
        assertTrue(gate.begin(true)!!.refreshRuntimeScene)
        gate.complete(true)
        assertFalse(gate.begin(true)!!.refreshRuntimeScene)
        gate.complete(false)
        assertFalse(gate.canSchedule(true))
    }

    @Test
    fun inactiveSurfaceDefersWorkUntilResumeAndDoesNotRunAnimation() {
        val gate = PresentationFrameGate()
        gate.request()
        assertNull(gate.begin(false))
        assertTrue(gate.begin(true)!!.refreshRuntimeScene)
        gate.complete(true)
        gate.stopPresentation()
        assertFalse(gate.canSchedule(true))
        gate.request()
        assertNull(gate.begin(false))
        assertNotNull(gate.begin(true))
    }
}
