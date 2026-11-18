package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceHintsTest {
    private class FakeSession : RuntimePerformanceHints.Session {
        val reported = mutableListOf<Long>()
        val targets = mutableListOf<Long>()
        var closed = 0
        var failReport = false
        override fun reportActual(durationNanos: Long) {
            if (failReport) error("unsupported report")
            reported += durationNanos
        }
        override fun updateTarget(durationNanos: Long) { targets += durationNanos }
        override fun close() { closed += 1 }
    }

    @Test
    fun reportsOnlyWorkAndRecreatesForMigratedNativeThread() {
        var clock = 10L
        var tid = 1L
        val sessions = mutableListOf<FakeSession>()
        val hints = RuntimePerformanceHints(
            factory = RuntimePerformanceHints.Factory {
                clock += 1_000L // Creation cost is excluded from reported work.
                FakeSession().also(sessions::add)
            },
            nanoTime = { clock }, threadId = { tid },
        )
        assertEquals(7, hints.measure(100L, { it: Int -> it == 7 }) { clock += 25L; 7 })
        assertEquals(listOf(25L), sessions.single().reported)
        hints.measure(200L, { _: Int -> true }) { clock += 50L; 1 }
        assertEquals(listOf(200L), sessions.single().targets)
        assertEquals(listOf(25L, 50L), sessions.single().reported)
        tid = 2L
        hints.measure(200L, { _: Int -> true }) { clock += 30L; 1 }
        assertEquals(2, sessions.size)
        assertEquals(1, sessions.first().closed)
        assertEquals(listOf(30L), sessions.last().reported)
        hints.close()
        hints.close()
        assertEquals(1, sessions.last().closed)
    }

    @Test
    fun rejectedOrThrowingPhysicsWorkClosesWithoutAReport() {
        var clock = 0L
        val sessions = mutableListOf<FakeSession>()
        val hints = RuntimePerformanceHints(
            RuntimePerformanceHints.Factory { FakeSession().also(sessions::add) },
            nanoTime = { clock },
        )
        hints.measure(100L, { it: Int -> it == 0 }) { clock += 10L; -1 }
        assertTrue(sessions.single().reported.isEmpty())
        assertEquals(1, sessions.single().closed)
        val failure = IllegalStateException("physics failure")
        try {
            hints.measure(100L, { _: Int -> true }) { clock += 5L; throw failure }
            error("exception swallowed")
        } catch (error: IllegalStateException) {
            assertEquals(failure, error)
        }
        assertEquals(1, sessions.last().closed)
        assertTrue(sessions.last().reported.isEmpty())
    }

    @Test
    fun hintFailureCannotBreakPhysicsOrRetryEveryCall() {
        var clock = 0L
        var creations = 0
        val session = FakeSession().apply { failReport = true }
        val hints = RuntimePerformanceHints(
            RuntimePerformanceHints.Factory { creations += 1; session },
            nanoTime = { clock },
        )
        repeat(3) {
            assertEquals(42, hints.measure(100L, { _: Int -> true }) { clock += 10L; 42 })
        }
        assertEquals(1, creations)
        assertEquals(1, session.closed)
        assertTrue(hints.summary().contains("unavailable_error"))
    }

    @Test
    fun optionalDiagnosticsCannotBreakPhysicsOrSessionClosure() {
        var clock = 0L
        val session = FakeSession()
        val hints = RuntimePerformanceHints(
            RuntimePerformanceHints.Factory { session },
            nanoTime = { clock }, onStateChanged = { error("diagnostic sink failed") },
        )
        assertEquals(42, hints.measure(100L, { _: Int -> true }) { clock += 10L; 42 })
        assertEquals(listOf(10L), session.reported)
        hints.close()
        assertEquals(1, session.closed)
    }
}
