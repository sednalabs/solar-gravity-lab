package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAdvanceFacadeTest {
    @Test
    fun cancelBetweenChunksPreservesCommittedSnapshotAndBlocksCommands() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bridge = StagedBridge()
            val facade = BridgeBackedRuntimeFacade(bridge, operationScope = scope)
            facade.startSession()
            facade.applyCommand(RuntimeCommand.AdvanceEpoch(130.0))
            withTimeout(5_000) { bridge.chunkStarted.await() }
            assertEquals(0.0, facade.uiState.value.snapshot!!.epochSeconds, 0.0)
            facade.applyCommand(RuntimeCommand.ResumePlayback)
            assertEquals(0, bridge.commands)
            facade.cancelManualAdvance()
            bridge.allowChunk.complete(Unit)
            withTimeout(5_000) { facade.uiState.first { it.manualAdvance == null } }
            assertEquals(1, bridge.cancels)
            assertEquals(0.0, facade.uiState.value.snapshot!!.epochSeconds, 0.0)
            assertEquals(4L, facade.uiState.value.snapshot!!.revision)
            assertTrue(facade.uiState.value.snapshot!!.paused)
        } finally {
            scope.cancel()
        }
    }

    private class StagedBridge : RuntimeBridge {
        val chunkStarted = CompletableDeferred<Unit>()
        val allowChunk = CompletableDeferred<Unit>()
        var commands = 0
        var cancels = 0
        private val snapshot = NativeSnapshotSummaryResult(
            result = NativeResult(0), scenarioId = "sol-system", activeBranchId = "main",
            bodyCount = 1, epochSeconds = 0.0, paused = true, simSecondsPerRealSecond = 1.0,
            observerMode = 0, timelineSemantics = 1, revision = 4,
        )
        private var result = NativeAdvanceResult(0, 0, 0, 0, 0, 0, 0.0, 0, 0, 0)
        override fun connect() = flowOf(RuntimeSignal.Connected(1), RuntimeSignal.SnapshotUpdated(snapshot, 1))
        override suspend fun loadScenario(scenarioId: String): List<RuntimeSignal> = emptyList()
        override suspend fun refresh(): List<RuntimeSignal> = listOf(RuntimeSignal.SnapshotUpdated(snapshot, 1))
        override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> { commands++; return emptyList() }
        override suspend fun queryAdvance() = result
        override suspend fun beginAdvance(intentHigh: Long, intentLow: Long, expectedGeneration: Long, deltaSeconds: Double, recordedAtUnixMs: Long): NativeAdvanceResult {
            result = NativeAdvanceResult(0, 1, expectedGeneration + 1, intentHigh, intentLow, 5, deltaSeconds, 0, 130, recordedAtUnixMs)
            return result
        }
        override suspend fun continueAdvance(generation: Long, expectedCompleted: Long): NativeAdvanceResult {
            chunkStarted.complete(Unit)
            allowChunk.await()
            result = result.copy(completedSteps = 64)
            return result
        }
        override suspend fun cancelAdvance(generation: Long): NativeAdvanceResult {
            cancels++
            result = result.copy(state = 3)
            return result
        }
    }
}
