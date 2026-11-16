package com.sednalabs.solarlab.render.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPerformanceHintsTest {
    private class FakeSession : RenderPerformanceHints.Session {
        val targets = mutableListOf<Long>()
        val reports = mutableListOf<Long>()
        var closes = 0
        var failReport = false
        override fun updateTarget(durationNanos: Long) { targets += durationNanos }
        override fun reportActual(durationNanos: Long) {
            if (failReport) throw IllegalStateException("Hint service unavailable")
            reports += durationNanos
        }
        override fun close() { closes += 1 }
    }

    @Test
    fun `reports measured work and changes target only when display period changes`() {
        var now = 1_000L
        val session = FakeSession()
        val initialTargets = mutableListOf<Long>()
        val hints = RenderPerformanceHints(
            factory = { initialTargets += it; RenderPerformanceHints.Creation(session) },
            nanoTime = { now },
        )
        val first = hints.begin(16_666_666L)
        now += 4_500_000L
        hints.end(first, true)
        val second = hints.begin(16_666_666L)
        now += 2_000_000L
        hints.end(second, true)
        hints.begin(8_333_333L)
        assertEquals(listOf(16_666_666L), initialTargets)
        assertEquals(listOf(4_500_000L, 2_000_000L), session.reports)
        assertEquals(listOf(8_333_333L), session.targets)
        assertTrue(hints.summary().contains("state=active,reported=2"))
    }

    @Test
    fun `unsupported device falls back without a create attempt every frame`() {
        var attempts = 0
        val hints = RenderPerformanceHints(factory = {
            attempts += 1
            RenderPerformanceHints.Creation(null, "unsupported")
        })
        repeat(100) { hints.end(hints.begin(16_666_666L), true) }
        assertEquals(1, attempts)
        assertTrue(hints.summary().contains("state=unsupported,reported=0"))
        hints.close()
        hints.begin(16_666_666L)
        assertEquals(2, attempts)
    }

    @Test
    fun `service failure closes session and never reports to a closed session`() {
        var now = 1L
        var attempts = 0
        val session = FakeSession().apply { failReport = true }
        val hints = RenderPerformanceHints(factory = {
            attempts += 1
            RenderPerformanceHints.Creation(session)
        }, nanoTime = { now })
        val start = hints.begin(10L)
        now = 20L
        hints.end(start, true)
        repeat(3) { hints.begin(10L) }
        assertEquals(1, session.closes)
        assertEquals(1, attempts)
        assertTrue(hints.summary().contains("state=unavailable_error"))
        hints.close()
        hints.close()
        assertEquals(1, session.closes)
    }

    @Test
    fun `pause teardown and failed frame release the hint session`() {
        val sessions = mutableListOf<FakeSession>()
        val hints = RenderPerformanceHints(factory = {
            RenderPerformanceHints.Creation(FakeSession().also { sessions += it })
        })
        hints.begin(100L)
        hints.close()
        val resumed = hints.begin(200L)
        hints.end(resumed, false)
        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.closes == 1 && it.reports.isEmpty() })
        assertTrue(hints.summary().contains("state=inactive"))
    }

    @Test
    fun `replacement executor thread receives a fresh session`() {
        var thread = 10L
        val sessions = mutableListOf<FakeSession>()
        val hints = RenderPerformanceHints(factory = {
            RenderPerformanceHints.Creation(FakeSession().also { sessions += it })
        }, threadId = { thread })
        hints.begin(100L)
        thread = 11L
        hints.begin(100L)
        assertEquals(2, sessions.size)
        assertEquals(1, sessions[0].closes)
        assertEquals(0, sessions[1].closes)
    }

    @Test
    fun `display target follows actual refresh rate and rejects invalid values`() {
        assertEquals(16_666_666L, displayFrameTargetNanos(60f))
        assertEquals(8_333_333L, displayFrameTargetNanos(120f))
        assertEquals(11_111_111L, displayFrameTargetNanos(90f))
        assertEquals(16_666_666L, displayFrameTargetNanos(Float.NaN))
        assertEquals(16_666_666L, displayFrameTargetNanos(0f))
    }
}
