package com.sednalabs.solarlab.runtime

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeAuthoringTest {
    private val reference = RuntimeBodyState("earth", RuntimeBodyClass.Planet, 5.972e24, 5.972e24, 6_371_000.0,
        1.0e11, 2.0e10, 3.0e8, 100.0, 20_000.0, 10.0)
    private val candidate = reference.copy(bodyId = "probe", bodyClass = RuntimeBodyClass.Spacecraft,
        massKg = 1000.0, sourceMassKg = 0.0, radiusM = 2.0, positionX = reference.positionX + 7_171_000.0)
    private fun native() = NativeProbePreviewResult(7, 0.0, 10.0, true, false,
        reference.toNativeBodyValues(), doubleArrayOf(0.0, 7_171_000.0, 0.0, 0.0, 10.0, 7_170_000.0, 70_000.0, 0.0))

    @Test
    fun bodyAndPreviewDtoRetainF64ValuesAndRejectMalformedNativePayloads() {
        assertEquals(reference, bodyStateFromNative(reference.bodyId, reference.toNativeBodyValues()))
        val result = native().toPreview(42, candidate, "earth")
        assertEquals(reference, result.referenceBody)
        assertEquals(candidate, result.candidate)
        assertEquals(7_171_000.0, result.samples[0].positionX, 0.0)
        val invalid = listOf(
            native().copy(baselineRevision = -1), native().copy(epochSeconds = Double.NaN),
            native().copy(horizonSeconds = 0.0), native().copy(samples = doubleArrayOf(0.0, 1.0, 2.0, 3.0)),
            native().copy(samples = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 0.0, 4.0, 5.0, 6.0)),
            native().copy(samples = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 10.0, Double.NaN, 5.0, 6.0)),
            native().copy(referenceBodyValues = doubleArrayOf(1.0)),
        )
        invalid.forEach { assertTrue(runCatching { it.toPreview(42, candidate, "earth") }.isFailure) }
    }

    @Test
    fun facadeRunsPreviewOffCallerWithoutChangingDisplayedStateAndPublishesCommittedTruth() = runBlocking {
        Executors.newSingleThreadExecutor { task -> Thread(task, "probe-boundary") }.asCoroutineDispatcher().use { dispatcher ->
            val bridge = ProbeBridge(native().toPreview(42, candidate, "earth"))
            val facade = BridgeBackedRuntimeFacade(bridge, boundaryDispatcher = dispatcher,
                developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()))
            facade.startSession()
            val before = facade.uiState.value
            val preview = facade.previewProbe(candidate, "earth", 10.0)
            assertEquals(before, facade.uiState.value)
            assertTrue(bridge.threads.all { it == "probe-boundary" })
            val receipt = facade.commitProbe(preview)
            assertEquals(receipt, facade.lastProbeCommit.value)
            assertEquals(4, facade.uiState.value.snapshot!!.bodyCount)
            assertEquals(9L, facade.uiState.value.snapshot!!.revision)
            facade.undoProbe(receipt)
            assertNull(facade.lastProbeCommit.value)
            assertEquals(3, facade.uiState.value.snapshot!!.bodyCount)
            assertEquals("undo-branch", facade.uiState.value.snapshot!!.activeBranchId)
            assertTrue(facade.uiState.value.snapshot!!.paused)
        }
    }

    @Test
    fun facadePropagatesPreviewAndCommitFailuresToPlacementUi() = runBlocking {
        val bridge = ProbeBridge(native().toPreview(42, candidate, "earth"))
        val facade = BridgeBackedRuntimeFacade(bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()))
        facade.startSession()
        val before = facade.uiState.value
        bridge.failure = IllegalStateException("The experiment changed")
        assertEquals("The experiment changed", runCatching { facade.previewProbe(candidate, "earth", 10.0) }.exceptionOrNull()?.message)
        assertEquals("The experiment changed", runCatching { facade.commitProbe(bridge.preview) }.exceptionOrNull()?.message)
        assertEquals(before, facade.uiState.value)
    }

    @Test
    fun successfulCommitKeepsReceiptWhenOriginatingActivityScopeIsCancelled() = runBlocking {
        val bridge = ProbeBridge(native().toPreview(42, candidate, "earth"))
        bridge.commitStarted = CompletableDeferred()
        bridge.allowCommit = CompletableDeferred()
        val facade = BridgeBackedRuntimeFacade(bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()))
        facade.startSession()
        val activityJob = launch { facade.commitProbe(bridge.preview) }
        withTimeout(5_000) { bridge.commitStarted!!.await() }
        activityJob.cancel()
        bridge.allowCommit!!.complete(Unit)
        withTimeout(5_000) { activityJob.join() }
        assertTrue(activityJob.isCancelled)
        // A replacement UI collecting the same ViewModel facade can discover success
        // without having received the cancelled caller's return value.
        val retained = requireNotNull(facade.lastProbeCommit.value)
        assertEquals("probe", retained.bodyId)
        assertEquals(4, facade.uiState.value.snapshot!!.bodyCount)
        facade.undoProbe(retained)
        assertNull(facade.lastProbeCommit.value)
        assertEquals(3, facade.uiState.value.snapshot!!.bodyCount)
    }

    @Test
    fun receiptSurvivesPlaybackCameraAndFailedEditButClearsOnAppliedAuthoring() = runBlocking {
        val bridge = ProbeBridge(native().toPreview(42, candidate, "earth"))
        val facade = BridgeBackedRuntimeFacade(bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()))
        facade.startSession()
        val receipt = facade.commitProbe(bridge.preview)
        val allowed = listOf(RuntimeCommand.FocusBody("probe"), RuntimeCommand.SetObserverMode(RuntimeObserverMode.Free),
            RuntimeCommand.ResumePlayback, RuntimeCommand.PausePlayback, RuntimeCommand.SetPlaybackRate(3600.0))
        allowed.forEach { command ->
            bridge.commandSignals = listOf(bridge.applied(command))
            facade.applyCommand(command)
            assertEquals(receipt, facade.lastProbeCommit.value)
        }
        bridge.commandSignals = listOf(RuntimeSignal.Notice("Edit rejected", RuntimeNoticeLevel.Error))
        facade.applyCommand(RuntimeCommand.RemoveBody("missing"))
        assertEquals(receipt, facade.lastProbeCommit.value)
        bridge.commandSignals = listOf(bridge.applied(RuntimeCommand.SetBodyKinematics("moon", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)))
        facade.applyCommand(RuntimeCommand.SetBodyKinematics("moon", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
        assertNull(facade.lastProbeCommit.value)
    }

    @Test
    fun acceptedSessionReplacementClearsReceiptButRejectedReplacementDoesNot() = runBlocking {
        val bridge = ProbeBridge(native().toPreview(42, candidate, "earth"))
        val facade = BridgeBackedRuntimeFacade(bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()))
        facade.startSession()
        val receipt = facade.commitProbe(bridge.preview)
        bridge.loadSignals = listOf(RuntimeSignal.Notice("Replacement rejected", RuntimeNoticeLevel.Error))
        facade.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        assertEquals(receipt, facade.lastProbeCommit.value)
        bridge.loadSignals = listOf(RuntimeSignal.Connected(43))
        facade.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        assertNull(facade.lastProbeCommit.value)
    }

    private class ProbeBridge(val preview: ProbePreviewResult) : RuntimeBridge {
        var failure: Throwable? = null
        var commitStarted: CompletableDeferred<Unit>? = null
        var allowCommit: CompletableDeferred<Unit>? = null
        var commandSignals: List<RuntimeSignal> = emptyList()
        var loadSignals: List<RuntimeSignal> = emptyList()
        val threads = mutableListOf<String>()
        private val snapshot = NativeSnapshotSummaryResult(NativeResult(0), "lab.earth-moon", "main", 3, 0.0, true, 1.0, 0, 1, revision = 7)
        override fun connect() = flowOf(RuntimeSignal.Connected(42), RuntimeSignal.SnapshotUpdated(snapshot, 42))
        override suspend fun loadScenario(scenarioId: String): List<RuntimeSignal> = loadSignals
        override suspend fun refresh(): List<RuntimeSignal> = emptyList()
        override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> = commandSignals
        fun applied(command: RuntimeCommand) = RuntimeSignal.CommandApplied(command, command.label,
            snapshot.copy(bodyCount = 4, revision = 10), 42)
        override suspend fun previewProbe(candidate: RuntimeBodyState, referenceBodyId: String, horizonSeconds: Double): ProbePreviewResult {
            failure?.let { throw it }
            threads += Thread.currentThread().name
            return preview
        }
        override suspend fun commitProbe(preview: ProbePreviewResult): Pair<ProbeCommitResult, List<RuntimeSignal>> {
            failure?.let { throw it }
            commitStarted?.complete(Unit)
            allowCommit?.await()
            return ProbeCommitResult(42, "before-probe", preview.candidate.bodyId, 0.0, 9) to
                listOf(RuntimeSignal.SnapshotUpdated(snapshot.copy(bodyCount = 4, revision = 9), 42))
        }
        override suspend fun undoProbe(commit: ProbeCommitResult): List<RuntimeSignal> =
            listOf(RuntimeSignal.SnapshotUpdated(snapshot.copy(activeBranchId = "undo-branch", revision = 10), 42))
    }
}
