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
            assertEquals(ManualAdvanceOutcome.Cancelled, facade.uiState.value.manualAdvanceOutcome)
            assertEquals(0.0, facade.uiState.value.snapshot!!.epochSeconds, 0.0)
            assertEquals(4L, facade.uiState.value.snapshot!!.revision)
            assertTrue(facade.uiState.value.snapshot!!.paused)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ordinalRaceAfterQueryIsRejectedWithoutReservationAndRefreshed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bridge = StagedBridge(staleBase = true)
            val facade = BridgeBackedRuntimeFacade(bridge, operationScope = scope)
            facade.startSession()
            facade.applyCommand(RuntimeCommand.AdvanceEpoch(130.0))
            withTimeout(5_000) { bridge.beginFinished.await() }
            withTimeout(5_000) { facade.uiState.first { it.manualAdvance == null && it.snapshot?.revision == 5L } }
            assertEquals(5L, bridge.requestedBase)
            assertEquals(0, bridge.continues)
            assertEquals(0, bridge.cancels)
            assertEquals(0, bridge.queryAdvance().state)
        } finally { scope.cancel() }
    }

    @Test
    fun staleDisplayUsesAuthoritativeQueryOrdinal() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bridge = StagedBridge(ordinalAhead = true)
            val facade = BridgeBackedRuntimeFacade(bridge, operationScope = scope)
            facade.startSession()
            facade.applyCommand(RuntimeCommand.AdvanceEpoch(130.0))
            withTimeout(5_000) { bridge.chunkStarted.await() }
            assertEquals(6L, bridge.requestedBase)
            facade.cancelManualAdvance()
            bridge.allowChunk.complete(Unit)
            withTimeout(5_000) { facade.uiState.first { it.manualAdvance == null } }
            assertEquals(1, bridge.cancels)
        } finally { scope.cancel() }
    }

    @Test
    fun lostBeginReplyRecoversSameIntentAndCanCancel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bridge = StagedBridge(lostBegin = true)
            val facade = BridgeBackedRuntimeFacade(bridge, operationScope = scope)
            facade.startSession()
            facade.applyCommand(RuntimeCommand.AdvanceEpoch(130.0))
            withTimeout(5_000) { bridge.chunkStarted.await() }
            facade.cancelManualAdvance()
            bridge.allowChunk.complete(Unit)
            withTimeout(5_000) { facade.uiState.first { it.manualAdvance == null } }
            assertEquals(1, bridge.cancels)
            assertEquals(4L, facade.uiState.value.snapshot!!.revision)
        } finally { scope.cancel() }
    }

    @Test
    fun lostBeginWithMismatchedMetadataKeepsBusyAndNeverCancels() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bridge = StagedBridge(lostBegin = true, mismatchedBase = true)
            val facade = BridgeBackedRuntimeFacade(bridge, operationScope = scope)
            facade.startSession()
            facade.applyCommand(RuntimeCommand.AdvanceEpoch(130.0))
            withTimeout(5_000) { facade.uiState.first { it.manualAdvance?.status == "Advance status unknown" } }
            assertEquals(0, bridge.cancels)
            facade.applyCommand(RuntimeCommand.ResumePlayback)
            assertEquals(0, bridge.commands)
        } finally { scope.cancel() }
    }

    private class StagedBridge(
        private val staleBase: Boolean = false,
        private val ordinalAhead: Boolean = false,
        private val lostBegin: Boolean = false,
        private val mismatchedBase: Boolean = false,
    ) : RuntimeBridge {
        val beginFinished = CompletableDeferred<Unit>()
        var requestedBase = 0L
        var continues = 0
        val chunkStarted = CompletableDeferred<Unit>()
        val allowChunk = CompletableDeferred<Unit>()
        var commands = 0
        var cancels = 0
        private val snapshot = NativeSnapshotSummaryResult(
            result = NativeResult(0), scenarioId = "sol-system", activeBranchId = "main",
            bodyCount = 1, epochSeconds = 0.0, paused = true, simSecondsPerRealSecond = 1.0,
            observerMode = 0, timelineSemantics = 1, revision = 4,
        )
        private var result = NativeAdvanceResult(0, 0, 0, 0, 0, 0, 0.0, 0, 0, 0, if (ordinalAhead) 6 else 5)
        override fun connect() = flowOf(RuntimeSignal.Connected(1), RuntimeSignal.SnapshotUpdated(snapshot, 1))
        override suspend fun loadScenario(scenarioId: String): List<RuntimeSignal> = emptyList()
        override suspend fun refresh(): List<RuntimeSignal> = listOf(RuntimeSignal.SnapshotUpdated(if (staleBase) snapshot.copy(revision = 5) else snapshot, 1))
        override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> { commands++; return emptyList() }
        override suspend fun queryAdvance() = result
        override suspend fun beginAdvance(intentHigh: Long, intentLow: Long, expectedGeneration: Long, expectedBaseOrdinal: Long, deltaSeconds: Double, recordedAtUnixMs: Long): NativeAdvanceResult {
            requestedBase = expectedBaseOrdinal
            if (staleBase) {
                beginFinished.complete(Unit)
                return result.copy(code = 5)
            }
            result = NativeAdvanceResult(0, 1, expectedGeneration + 1, intentHigh, intentLow, expectedBaseOrdinal + if (mismatchedBase) 1 else 0, deltaSeconds, 0, 130, recordedAtUnixMs, expectedBaseOrdinal)
            beginFinished.complete(Unit)
            if (lostBegin) error("Lost begin reply")
            return result
        }
        override suspend fun continueAdvance(generation: Long, expectedCompleted: Long): NativeAdvanceResult {
            continues++
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
