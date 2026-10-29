package com.sednalabs.solarlab

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sednalabs.solarlab.runtime.JniNativeRuntimeTransport
import com.sednalabs.solarlab.runtime.ManualAdvanceOutcome
import com.sednalabs.solarlab.runtime.NativeLibraryLoadOutcome
import com.sednalabs.solarlab.runtime.NativeRuntimeCommandPayload
import com.sednalabs.solarlab.runtime.NATIVE_COMMAND_SEED_CANONICAL_SOLAR_SYSTEM
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoundedAdvanceInstrumentationTest {
    @Test
    fun nativeChunksStayPrivateUntilCommitAndCancellationPreservesSnapshot() {
        val native = JniNativeRuntimeTransport
        assertTrue(native.ensureLibraryLoaded() is NativeLibraryLoadOutcome.Success)
        val created = native.createSession("sol-system", "root")
        assertTrue(created.result.isOk())
        val handle = created.handle
        try {
            assertTrue(native.applyCommand(handle, NativeRuntimeCommandPayload(kind = NATIVE_COMMAND_SEED_CANONICAL_SOLAR_SYSTEM)).result.isOk())
            assertTrue(native.applyCommand(handle, NativeRuntimeCommandPayload(kind = 1)).result.isOk())
            val displayed = native.refreshSession(handle)
            assertTrue(displayed.paused)
            val initial = native.queryAdvance(handle)
            assertEquals(0, initial.code)
            val rejected = native.applyCommand(handle, NativeRuntimeCommandPayload(kind = 7, bodyIdUtf8 = "not-a-body".toByteArray()))
            assertTrue(!rejected.result.isOk())
            val stale = native.beginAdvance(handle, 10L, 20L, initial.generation, displayed.revision + 1, 130.0, 41L)
            assertEquals(5, stale.code)
            val afterRejectedBegin = native.queryAdvance(handle)
            assertEquals(initial.state, afterRejectedBegin.state)
            assertEquals(initial.generation, afterRejectedBegin.generation)
            assertEquals(initial.currentNextOrdinal + 1, afterRejectedBegin.currentNextOrdinal)
            val before = native.refreshSession(handle)
            val current = native.queryAdvance(handle)
            assertEquals(before.revision + 1, current.currentNextOrdinal)
            assertEquals(displayed.revision + 1, before.revision)
            assertEquals(displayed.epochSeconds, before.epochSeconds, 0.0)
            val started = native.beginAdvance(handle, 12L, 34L, current.generation, current.currentNextOrdinal, 130.0, 42L)
            assertEquals(0, started.code)
            assertEquals(1, started.state)
            assertTrue(started.totalSteps > 64)
            val chunk = native.continueAdvance(handle, started.generation, 0)
            assertEquals(1, chunk.state)
            assertTrue(chunk.completedSteps > 0)
            val staged = native.refreshSession(handle)
            assertEquals(before.revision, staged.revision)
            assertEquals(before.epochSeconds, staged.epochSeconds, 0.0)
            assertEquals(chunk, native.queryAdvance(handle))
            val cancelled = native.cancelAdvance(handle, chunk.generation)
            assertEquals(3, cancelled.state)
            val afterCancel = native.refreshSession(handle)
            assertEquals(before.revision, afterCancel.revision)
            assertEquals(before.epochSeconds, afterCancel.epochSeconds, 0.0)
            var commit = native.beginAdvance(handle, 56L, 78L, cancelled.generation, cancelled.currentNextOrdinal, 130.0, 43L)
            assertEquals(1, commit.state)
            while (commit.state == 1) {
                commit = native.continueAdvance(handle, commit.generation, commit.completedSteps)
                assertEquals(0, commit.code)
                if (commit.state == 1) assertEquals(before.revision, native.refreshSession(handle).revision)
            }
            assertEquals(2, commit.state)
            val afterCommit = native.refreshSession(handle)
            assertEquals(before.revision + 1, afterCommit.revision)
            assertEquals(before.epochSeconds + 130.0, afterCommit.epochSeconds, 0.001)
            val terminal = native.queryAdvance(handle)
            assertEquals(commit, terminal)
            assertEquals(commit.baseOrdinal + 1, terminal.currentNextOrdinal)
            assertTrue(native.applyCommand(handle, NativeRuntimeCommandPayload(kind = 2)).result.isOk())
            val tick = native.playbackTick(handle, 0.5, 44L)
            assertEquals(0, tick.code)
            assertTrue(tick.requestedSeconds > 0.0)
            assertTrue(tick.appliedSeconds > 0.0 && tick.appliedSeconds <= tick.requestedSeconds)
            assertEquals(tick.appliedSeconds < tick.requestedSeconds, tick.limited)
            val tickSnapshot = native.refreshSession(handle)
            assertEquals(tick.committedRevision, tickSnapshot.revision)
            assertEquals(tick.committedEpochSeconds, tickSnapshot.epochSeconds, 0.0)
        } finally {
            native.destroySession(handle)
        }
    }

    @Test
    fun activityManualAdvanceCommitsOnceThroughRealSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var facade: RuntimeFacade? = null
            scenario.onActivity { facade = it.runtimeFacadeForTesting }
            val runtime = requireNotNull(facade)
            waitUntil { (runtime.uiState.value.snapshot?.bodyCount ?: 0) > 0 && runtime.uiState.value.renderFrame != null }
            runBlocking { runtime.applyCommand(RuntimeCommand.PausePlayback) }
            waitUntil { runtime.uiState.value.snapshot?.paused == true }
            val before = requireNotNull(runtime.uiState.value.snapshot)
            runBlocking { runtime.applyCommand(RuntimeCommand.AdvanceEpoch(2.0)) }
            waitUntil { runtime.uiState.value.manualAdvanceOutcome == ManualAdvanceOutcome.Complete && runtime.uiState.value.snapshot?.epochSeconds == before.epochSeconds + 2.0 }
            val after = requireNotNull(runtime.uiState.value.snapshot)
            assertEquals(before.revision + 1, after.revision)
            assertTrue(after.paused)
        }
    }

    @Test
    fun activeAdvanceSurvivesActivityRecreationAndCancelsWithoutPublishingPartialWorld() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var facade: RuntimeFacade? = null
            scenario.onActivity { facade = it.runtimeFacadeForTesting }
            val runtime = requireNotNull(facade)
            waitUntil { (runtime.uiState.value.snapshot?.bodyCount ?: 0) > 0 && runtime.uiState.value.renderFrame != null }
            runBlocking { runtime.applyCommand(RuntimeCommand.PausePlayback) }
            waitUntil { runtime.uiState.value.snapshot?.paused == true }
            val before = requireNotNull(runtime.uiState.value.snapshot)
            val handle = requireNotNull(runtime.uiState.value.sessionHandle)
            val native = JniNativeRuntimeTransport
            val previous = native.queryAdvance(handle)
            assertEquals(0, previous.code)
            assertTrue(previous.state != 1)
            try {
                // Large enough to remain staged through recreation; always cancelled below.
                runBlocking { runtime.applyCommand(RuntimeCommand.AdvanceEpoch(1_000_000_000.0)) }
                waitUntil {
                    val operation = native.queryAdvance(handle)
                    operation.code == 0 && operation.state == 1 && operation.completedSteps > 0
                }
                val running = native.queryAdvance(handle)
                assertEquals(1, running.state)
                assertEquals(previous.generation + 1, running.generation)
                assertEquals(previous.currentNextOrdinal, running.baseOrdinal)
                assertTrue(running.completedSteps < running.totalSteps)
                val stagedBefore = native.refreshSession(handle)
                assertEquals(before.epochSeconds, stagedBefore.epochSeconds, 0.0)
                assertEquals(before.revision, stagedBefore.revision)

                scenario.recreate()
                var recreatedFacade: RuntimeFacade? = null
                scenario.onActivity { recreatedFacade = it.runtimeFacadeForTesting }
                assertSame(runtime, recreatedFacade)
                assertEquals(handle, runtime.uiState.value.sessionHandle)
                val reattached = native.queryAdvance(handle)
                assertEquals(0, reattached.code)
                assertEquals(1, reattached.state)
                assertEquals(running.generation, reattached.generation)
                assertEquals(running.intentHigh, reattached.intentHigh)
                assertEquals(running.intentLow, reattached.intentLow)
                assertEquals(running.baseOrdinal, reattached.baseOrdinal)
                assertEquals(running.currentNextOrdinal, reattached.currentNextOrdinal)
                assertTrue(reattached.completedSteps >= running.completedSteps)
                assertTrue(runtime.uiState.value.manualAdvance != null)
                val stagedAfter = native.refreshSession(handle)
                assertEquals(before.epochSeconds, stagedAfter.epochSeconds, 0.0)
                assertEquals(before.revision, stagedAfter.revision)

                runtime.cancelManualAdvance()
                waitUntil { runtime.uiState.value.manualAdvanceOutcome == ManualAdvanceOutcome.Cancelled }
                val cancelled = native.queryAdvance(handle)
                assertEquals(3, cancelled.state)
                assertEquals(running.generation, cancelled.generation)
                assertEquals(running.intentHigh, cancelled.intentHigh)
                assertEquals(running.intentLow, cancelled.intentLow)
                val finalSnapshot = native.refreshSession(handle)
                assertEquals(before.epochSeconds, finalSnapshot.epochSeconds, 0.0)
                assertEquals(before.revision, finalSnapshot.revision)
                assertEquals(previous.currentNextOrdinal, cancelled.currentNextOrdinal)
            } finally {
                runtime.cancelManualAdvance()
                waitUntil { native.queryAdvance(handle).state != 1 && runtime.uiState.value.manualAdvance == null }
            }
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for bounded advance")
    }
}
