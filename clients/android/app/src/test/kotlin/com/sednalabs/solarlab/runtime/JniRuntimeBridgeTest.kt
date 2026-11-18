package com.sednalabs.solarlab.runtime

import com.sednalabs.solarlab.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class JniRuntimeBridgeTest {
    @Test fun invalidRendezvousTargetCannotReplaceTheCurrentWorld() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(snapshotSummary(bodyCount = 3))),
            sessionHandles = ArrayDeque(listOf(42L, 84L)))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        transport.rendezvousTarget = null
        assertTrue(runCatching { bridge.startRendezvousFlight(REFERENCE_SPACECRAFT_ID) }.isFailure)
        assertTrue(bridge.isCurrentSession(42L))
        assertEquals(listOf(84L), transport.destroyedHandles)
    }

    @Test fun rendezvousBindsACompleteMissionAndPreservesItsNativeTargetIdentity() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(snapshotSummary(bodyCount = 3))),
            sessionHandles = ArrayDeque(listOf(42L, 84L)))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        transport.rendezvousTarget = "saved.station.identity"
        val signals = bridge.startRendezvousFlight(REFERENCE_SPACECRAFT_ID)
        assertTrue(bridge.isCurrentSession(84L))
        assertEquals(listOf(42L), transport.destroyedHandles)
        assertEquals(listOf(84L to REFERENCE_SPACECRAFT_ID), transport.rendezvousStarts)
        assertEquals("saved.station.identity", requireNotNull(bridge.rendezvousState(REFERENCE_SPACECRAFT_ID)).targetBodyId)
        assertTrue(signals.any { it is RuntimeSignal.Connected && it.handle == 84L })
    }

    @Test fun lunarCandidateFailurePreservesTheCurrentSession() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(snapshotSummary(bodyCount = 3))),
            sessionHandles = ArrayDeque(listOf(42L, 84L)))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        transport.lunarFlightError = IllegalStateException("Mission authoring rejected")
        var rejected = false
        try { bridge.startLunarFlight(REFERENCE_SPACECRAFT_ID) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertTrue(bridge.isCurrentSession(42L))
        assertEquals(listOf(84L), transport.destroyedHandles)
    }

    @Test fun lunarCandidateIsSeededAndValidatedBeforeReplacingTheLaboratory() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(snapshotSummary(bodyCount = 3))),
            sessionHandles = ArrayDeque(listOf(42L, 84L)))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        val signals = bridge.startLunarFlight(REFERENCE_SPACECRAFT_ID)
        assertTrue(bridge.isCurrentSession(84L))
        assertEquals(listOf(42L), transport.destroyedHandles)
        assertEquals(listOf(84L to REFERENCE_SPACECRAFT_ID), transport.lunarStarts)
        assertTrue(signals.any { it is RuntimeSignal.Connected && it.handle == 84L })
    }

    @Test
    fun transportCancellationPropagatesThroughRefreshAndCommand() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(snapshotSummary(bodyCount = 1))))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        for (command in listOf(false, true)) {
            val cancellation = CancellationException("transport canceled")
            if (command) transport.commandError = cancellation else transport.refreshError = cancellation
            var caught: CancellationException? = null
            try {
                if (command) bridge.applyCommand(RuntimeCommand.PausePlayback) else bridge.refresh()
            } catch (error: CancellationException) {
                caught = error
            }
            assertTrue(caught === cancellation)
            transport.commandError = null
            transport.refreshError = null
        }
        transport.commandError = IllegalStateException("command failed")
        val commandNotice = bridge.applyCommand(RuntimeCommand.PausePlayback).filterIsInstance<RuntimeSignal.Notice>().single()
        assertTrue(commandNotice.message.contains("command failed"))
        transport.commandError = null
        transport.refreshError = IllegalStateException("refresh failed")
        val refreshNotice = bridge.refresh().filterIsInstance<RuntimeSignal.Notice>().single()
        assertTrue(refreshNotice.message.contains("refresh failed"))
    }

    @Test
    fun nativeRenderExportRethrowsCancellation() {
        val transport = FakeNativeRuntimeTransport(ArrayDeque())
        val adapter = NativeRenderHostAdapter(transport)
        adapter.bindSession(42L)
        val cancellation = CancellationException("export canceled")
        transport.exportError = cancellation
        var caught: CancellationException? = null
        try {
            adapter.refreshPacket()
        } catch (error: CancellationException) {
            caught = error
        }
        assertTrue(caught === cancellation)
        transport.exportError = IllegalStateException("export failed")
        assertTrue(adapter.refreshPacket().unavailableReason!!.contains("export failed"))
    }

    @Test
    fun connect_seedsPausedEarthMoonLab_whenInitialSnapshotIsEmpty() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(
                    snapshotSummary(bodyCount = 0),
                    snapshotSummary(bodyCount = STARTUP_EXPECTED_BODY_COUNT),
                )
            ),
        )
        val renderHostAdapter = FakeRenderHostAdapter()
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = renderHostAdapter,
        )

        val signals = collectSignalsUntil(bridge) { collected ->
            val seeded = collected
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == STARTUP_EXPECTED_BODY_COUNT }
            val startupPlaybackConfigured = collected
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Startup playback rate set") }
            seeded && startupPlaybackConfigured
        }

        assertEquals(listOf(42L), transport.runtimeInfoHandles)
        assertEquals(listOf("lab.earth-moon"), transport.createdScenarioIds)
        assertEquals(listOf(42L, 42L, 42L), transport.refreshedHandles)
        assertEquals(listOf(42L), renderHostAdapter.boundSessionHandles)
        assertEquals(3, renderHostAdapter.refreshCount)
        assertEquals(1, renderHostAdapter.releaseCount)

        assertTrue(
            "Expected startup to seed the selected scenario through Rust",
            transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_SEED_CANONICAL_SOLAR_SYSTEM) },
        )
        assertFalse(
            "Expected the laboratory to remain paused for inspection",
            transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_RESUME_PLAYBACK) },
        )
        assertTrue(
            "Expected startup to set visible playback rate",
            transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_SET_PLAYBACK_RATE) },
        )
        assertTrue(
            signals
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Seeded scenario pack via Rust authority") }
        )
        assertTrue(
            signals
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == STARTUP_EXPECTED_BODY_COUNT }
        )
        assertTrue(signals.filterIsInstance<RuntimeSignal.SnapshotUpdated>().last().summary.paused)
    }

    @Test
    fun connect_doesNotReseedLab_whenSnapshotAlreadyContainsBodies() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(snapshotSummary(bodyCount = 2))
            ),
        )
        val renderHostAdapter = FakeRenderHostAdapter()
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = renderHostAdapter,
        )

        val signals = collectSignalsUntil(bridge) { collected ->
            val snapshotReady = collected
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == 2 }
            val startupPlaybackConfigured = collected
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Startup playback rate set") }
            snapshotReady && startupPlaybackConfigured
        }

        assertFalse(
            "Seed should not be issued when snapshot already has bodies",
            transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_SEED_CANONICAL_SOLAR_SYSTEM) },
        )
        assertFalse(
            "Expected the laboratory to remain paused for inspection",
            transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_RESUME_PLAYBACK) },
        )
        assertTrue(
            "Expected startup to set visible playback rate",
            transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_SET_PLAYBACK_RATE) },
        )
        assertEquals(listOf(42L, 42L), transport.refreshedHandles)
        assertEquals(2, renderHostAdapter.refreshCount)
        assertEquals(1, renderHostAdapter.releaseCount)
        assertFalse(
            signals
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Seeded scenario pack via Rust authority") }
        )
    }

    @Test
    fun applyCommand_forwardsExplicitSpawnSourceMass() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(listOf(snapshotSummary(bodyCount = 2))),
        )
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = FakeRenderHostAdapter(),
        )
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val job = scope.launch {
            bridge.connect().collect { /* keep native session alive for command dispatch */ }
        }

        withTimeout(2_000) {
            while (
                transport.refreshedHandles.size < 2 ||
                transport.appliedCommands.none { hasCommandKind(it, COMMAND_KIND_SET_PLAYBACK_RATE) }
            ) {
                delay(25)
            }
        }
        transport.appliedCommands.clear()

        bridge.applyCommand(
            RuntimeCommand.SpawnBody(
                bodyId = "visual-probe",
                bodyClass = RuntimeBodyClass.Planet,
                massKg = 1.0e24,
                radiusM = 1_000.0,
                sourceMassKg = 0.0,
            )
        )

        val command = transport.appliedCommands.single()
        assertEquals(COMMAND_KIND_SPAWN_BODY, command.kind)
        assertEquals(1.0e24, command.bodyMassKg, 0.0)
        assertEquals(0.0, command.bodySourceMassKg, 0.0)

        job.cancel()
        job.join()
        scope.cancel()
    }

    @Test
    fun connect_emitsOpenClRuntimeInfoLabel_whenNativeReportsOpenClBackend() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(snapshotSummary(bodyCount = 1))
            ),
            runtimeInfoGpuBackend = 4,
        )
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = FakeRenderHostAdapter(),
        )

        val signals = collectSignalsUntil(bridge) { collected ->
            collected.any { it is RuntimeSignal.RuntimeInfoAvailable }
        }

        val runtimeInfo = signals
            .filterIsInstance<RuntimeSignal.RuntimeInfoAvailable>()
            .last()

        assertEquals("simd-arm64", runtimeInfo.cpuBackendLabel)
        assertEquals(preferredGpuBackendLabel(BuildConfig.PREFERRED_GPU_BACKEND), runtimeInfo.requestedGpuBackendLabel)
        assertEquals("opencl", runtimeInfo.gpuBackendLabel)
        assertTrue(runtimeInfo.workloadSummary?.contains("long-horizon") == true)
        assertTrue(runtimeInfo.interopErrorBudgetSummary?.contains("checkpoint-publication") == true)
    }

    @Test
    fun loadScenario_canonicalSolarSystemRetainsFullCatalogAndLivePlayback() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(
            snapshotSummary(scenarioId = "sol-system", bodyCount = 0),
            snapshotSummary(scenarioId = "sol-system", bodyCount = 365),
        )))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())

        val signals = bridge.loadScenario("sol-system")

        assertEquals(listOf("sol-system"), transport.createdScenarioIds)
        assertTrue(transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_SEED_CANONICAL_SOLAR_SYSTEM) })
        assertTrue(transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_RESUME_PLAYBACK) })
        val finalSummary = signals.filterIsInstance<RuntimeSignal.SnapshotUpdated>().last().summary
        assertEquals("sol-system", finalSummary.scenarioId)
        assertEquals(365, finalSummary.bodyCount)
        assertFalse(finalSummary.paused)
        assertEquals(21_600.0, finalSummary.simSecondsPerRealSecond, 0.0)
    }

    @Test
    fun loadScenario_replacesActiveSessionAndAppliesScenarioDefaults() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(
                    snapshotSummary(
                        scenarioId = "showcase.jupiter-system",
                        bodyCount = 0,
                    ),
                    snapshotSummary(
                        scenarioId = "showcase.jupiter-system",
                        bodyCount = 31,
                    ),
                )
            ),
        )
        val renderHostAdapter = FakeRenderHostAdapter()
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = renderHostAdapter,
        )

        val signals = bridge.loadScenario("showcase.jupiter-system")

        assertEquals(listOf("showcase.jupiter-system"), transport.createdScenarioIds)
        assertEquals(listOf(42L), renderHostAdapter.boundSessionHandles)
        assertTrue(transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_SEED_CANONICAL_SOLAR_SYSTEM) })
        assertTrue(transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_SET_PLAYBACK_RATE) })
        assertTrue(transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_FOCUS_BODY) })
        assertTrue(transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_SET_OBSERVER_MODE) })
        assertTrue(transport.appliedCommands.any { hasCommandKind(it, COMMAND_KIND_RESUME_PLAYBACK) })
        assertTrue(
            signals
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.scenarioId == "showcase.jupiter-system" && it.summary.bodyCount == 31 }
        )
    }

    @Test
    fun loadScenario_rejectsUnknownScenarioWithoutCreatingSession() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(listOf(snapshotSummary(bodyCount = 1))),
        )
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = FakeRenderHostAdapter(),
        )

        val signals = bridge.loadScenario("missing-pack")

        assertTrue(signals.any { it is RuntimeSignal.Unavailable })
        assertTrue(transport.createdScenarioIds.isEmpty())
    }

    @Test
    fun loadScenario_releasesPreviousSessionWhenReplacingActiveHandle() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(
                    snapshotSummary(bodyCount = 2, paused = false, simSecondsPerRealSecond = 21_600.0),
                    snapshotSummary(
                        scenarioId = "showcase.jupiter-system",
                        bodyCount = 31,
                        paused = false,
                        simSecondsPerRealSecond = 14_400.0,
                    ),
                )
            ),
            sessionHandles = ArrayDeque(listOf(42L, 84L)),
        )
        val renderHostAdapter = FakeRenderHostAdapter()
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = renderHostAdapter,
        )
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val job = scope.launch {
            bridge.connect().collect { /* keep default session alive */ }
        }

        withTimeout(2_000) {
            while (transport.runtimeInfoHandles.isEmpty()) {
                delay(25)
            }
        }

        bridge.loadScenario("showcase.jupiter-system")
        assertEquals(listOf("lab.earth-moon", "showcase.jupiter-system"), transport.createdScenarioIds)
        assertEquals(listOf(42L), transport.destroyedHandles)
        assertEquals(listOf(42L, 84L), renderHostAdapter.boundSessionHandles)

        job.cancel()
        job.join()
        scope.cancel()
        assertTrue(
            "Expected closing the runtime bridge flow to release the current scenario session",
            transport.destroyedHandles.contains(84L),
        )
    }


    @Test
    fun connect_keepsRefreshing_whenPlaybackIsLive() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(snapshotSummary(bodyCount = STARTUP_EXPECTED_BODY_COUNT))
            ),
        )
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = FakeRenderHostAdapter(),
        )

        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val job = scope.launch {
            bridge.connect().collect { /* keep session alive for periodic refresh */ }
        }

        withTimeout(2_000) {
            while (transport.refreshedHandles.size < 2) delay(25)
        }
        bridge.applyCommand(RuntimeCommand.ResumePlayback)

        withTimeout(2_500) {
            while (
                transport.refreshedHandles.size < 3 ||
                transport.playbackTicks == 0
            ) {
                delay(25)
            }
        }

        job.cancel()
        scope.cancel()

        assertTrue(
            "Expected live playback to keep issuing periodic refreshes",
            transport.refreshedHandles.size >= 3,
        )
        assertTrue(
            "Expected live playback to keep issuing bounded ticks",
            transport.playbackTicks > 0,
        )
    }

    @Test
    fun postTickSnapshotFailureClearsDisplayedMeasurementAtNonzeroRevision() = runBlocking {
        exerciseMeasurementFailure(postTick = true)
    }

    @Test
    fun initialSnapshotExceptionClearsDisplayedMeasurementAtNonzeroRevision() = runBlocking {
        exerciseMeasurementFailure(postTick = false)
    }

    private suspend fun exerciseMeasurementFailure(postTick: Boolean) {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(
            snapshotSummary(bodyCount = STARTUP_EXPECTED_BODY_COUNT).copy(revision = 10),
        )))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        val facade = BridgeBackedRuntimeFacade(bridge)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val job = scope.launch { facade.startSession() }
        try {
            withTimeout(2_000) {
                while (transport.refreshedHandles.size < 2) delay(25)
            }
            facade.applyCommand(RuntimeCommand.ResumePlayback)
            withTimeout(8_000) {
                while (facade.uiState.value.playbackMeasurement?.achievedRate == null) delay(25)
            }
            val previous = requireNotNull(facade.uiState.value.playbackMeasurement)
            assertTrue(previous.revision > 0)
            if (postTick) transport.failAfterTick = true else transport.refreshError = IllegalStateException("initial refresh failed")
            withTimeout(8_000) {
                while (facade.uiState.value.playbackMeasurement != null) delay(25)
            }
            assertEquals(null, facade.uiState.value.playbackMeasurement)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    private suspend fun collectSignalsUntil(
        bridge: RuntimeBridge,
        predicate: (List<RuntimeSignal>) -> Boolean,
    ): List<RuntimeSignal> {
        val collected = mutableListOf<RuntimeSignal>()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val job = scope.launch {
            bridge.connect().collect { signal ->
                collected += signal
                if (predicate(collected)) {
                    cancel()
                }
            }
        }

        withTimeout(2_000) {
            job.join()
        }

        scope.cancel()
        return collected.toList()
    }

    @Test
    fun probePreviewAndCommitForwardExactCandidateAndNativeBaseline() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(snapshotSummary(bodyCount = 3).copy(revision = 17))))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        val candidate = probeCandidate()
        val preview = bridge.previewProbe(candidate, "earth", 10.0)
        assertEquals(candidate, preview.candidate)
        assertEquals(17L, preview.baselineRevision)
        assertEquals(2, preview.samples.size)
        assertEquals("earth", preview.referenceBody.bodyId)
        assertTrue(transport.committedProbeCandidates.isEmpty())
        val (receipt, signals) = bridge.commitProbe(preview)
        assertEquals(listOf(candidate), transport.committedProbeCandidates)
        assertEquals(19L, receipt.committedRevision)
        assertEquals(42L, receipt.sessionHandle)
        assertEquals(4, signals.filterIsInstance<RuntimeSignal.SnapshotUpdated>().single().summary.bodyCount)
        bridge.undoProbe(receipt)
        assertEquals(listOf("probe-checkpoint" to 19L), transport.probeUndoRequests)
    }

    @Test
    fun probeCommitRejectsSupersededCopiedAndReplacedSessionDrafts() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            ArrayDeque(listOf(snapshotSummary(bodyCount = 3))),
            sessionHandles = ArrayDeque(listOf(42L, 43L)),
        )
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        val first = bridge.previewProbe(probeCandidate(), "earth", 10.0)
        val second = bridge.previewProbe(probeCandidate().copy(positionX = 3.0e11), "earth", 10.0)
        assertTrue(runCatching { bridge.commitProbe(first) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { bridge.commitProbe(second.copy()) }.exceptionOrNull() is IllegalStateException)
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        assertTrue(runCatching { bridge.commitProbe(second) }.exceptionOrNull() is IllegalStateException)
        assertTrue(transport.committedProbeCandidates.isEmpty())
    }

    @Test
    fun failedNewPreviewInvalidatesAnEarlierDraftAndNativeCommitFailurePropagates() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(snapshotSummary(bodyCount = 3))))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        val old = bridge.previewProbe(probeCandidate(), "earth", 10.0)
        transport.probePreviewError = IllegalStateException("outside the reference surface")
        assertTrue(runCatching { bridge.previewProbe(probeCandidate(), "earth", 10.0) }.exceptionOrNull()?.message!!.contains("surface"))
        assertTrue(runCatching { bridge.commitProbe(old) }.exceptionOrNull() is IllegalStateException)
        transport.probePreviewError = null
        val current = bridge.previewProbe(probeCandidate(), "earth", 10.0)
        transport.probeCommitError = IllegalStateException("The experiment changed")
        val commandsBefore = transport.appliedCommands.toList()
        assertEquals("The experiment changed", runCatching { bridge.commitProbe(current) }.exceptionOrNull()?.message)
        assertEquals(commandsBefore, transport.appliedCommands.toList())
        assertTrue(transport.committedProbeCandidates.isEmpty())
    }

    @Test
    fun probeUndoReceiptCannotActOnAReplacementExperiment() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(snapshotSummary(bodyCount = 3))),
            sessionHandles = ArrayDeque(listOf(42L, 43L)))
        val bridge = JniRuntimeBridge(transport, FakeRenderHostAdapter())
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        val preview = bridge.previewProbe(probeCandidate(), "earth", 10.0)
        val receipt = bridge.commitProbe(preview).first
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        assertTrue(runCatching { bridge.undoProbe(receipt) }.exceptionOrNull() is IllegalStateException)
        assertTrue(transport.probeUndoRequests.isEmpty())
    }

    @Test
    fun documentValidationOwnsOnlyTemporarySessionAndNeverRebindsRenderer() = runBlocking {
        val transport = FakeNativeRuntimeTransport(ArrayDeque(listOf(snapshotSummary(bodyCount = 3))))
        val renderer = FakeRenderHostAdapter()
        val bridge = JniRuntimeBridge(transport, renderer)
        bridge.loadScenario(RuntimeScenarioPacks.default.scenarioId)
        val binds = renderer.boundSessionHandles.toList()
        val releases = renderer.releaseCount
        val commands = transport.appliedCommands.toList()
        assertEquals("lab.earth-moon", bridge.validateSavedSession(byteArrayOf(1)))
        assertEquals(listOf(84L), transport.destroyedHandles)
        assertEquals(binds, renderer.boundSessionHandles)
        assertEquals(releases, renderer.releaseCount)
        assertEquals(commands, transport.appliedCommands.toList())
        assertTrue(bridge.isCurrentSession(42L))
        transport.validationSummaryError = IllegalStateException("Malformed native state")
        assertTrue(runCatching { bridge.validateSavedSession(byteArrayOf(2)) }.isFailure)
        assertEquals(listOf(84L, 84L), transport.destroyedHandles)
        transport.validationSummaryError = null
        transport.validationHandle = 42L
        assertTrue(runCatching { bridge.validateSavedSession(byteArrayOf(3)) }.isFailure)
        assertEquals(listOf(84L, 84L), transport.destroyedHandles)
        assertTrue(bridge.isCurrentSession(42L))
        assertEquals(binds, renderer.boundSessionHandles)
    }

    private class FakeRenderHostAdapter : RenderHostAdapter {
        val boundSessionHandles = mutableListOf<Long>()
        var refreshCount: Int = 0
            private set
        var releaseCount: Int = 0
            private set

        override fun bindSession(sessionHandle: Long) {
            boundSessionHandles += sessionHandle
        }

        override fun refreshPacket(): RenderPacketRefreshResult {
            refreshCount += 1
            return RenderPacketRefreshResult(
                unavailableReason = "Render export unavailable: test stub"
            )
        }

        override fun releasePacket() {
            releaseCount += 1
        }
    }

    private class FakeNativeRuntimeTransport(
        private val refreshResults: ArrayDeque<NativeSnapshotSummaryResult>,
        private val runtimeInfoCpuBackend: Int = 1,
        private val runtimeInfoGpuBackend: Int = 0,
        private val sessionHandles: ArrayDeque<Long> = ArrayDeque(listOf(42L)),
    ) : NativeRuntimeTransport {
        val createdScenarioIds = CopyOnWriteArrayList<String>()
        val destroyedHandles = CopyOnWriteArrayList<Long>()
        val runtimeInfoHandles = CopyOnWriteArrayList<Long>()
        val refreshedHandles = CopyOnWriteArrayList<Long>()
        val appliedCommands = CopyOnWriteArrayList<NativeRuntimeCommandPayload>()
        private var latestSummary: NativeSnapshotSummaryResult? = null
        @Volatile var refreshError: Throwable? = null
        @Volatile var failAfterTick = false
        @Volatile var playbackTicks = 0
        private var failRefresh = false
        var commandError: Throwable? = null
        var exportError: Throwable? = null
        var validationHandle = 84L
        var validationSummaryError: Throwable? = null
        var probePreviewError: Throwable? = null
        var probeCommitError: Throwable? = null
        var lunarFlightError: Throwable? = null
        val lunarStarts = mutableListOf<Pair<Long, String>>()
        val rendezvousStarts = mutableListOf<Pair<Long, String>>()
        var rendezvousTarget: String? = "rendezvous.station"
        val committedProbeCandidates = mutableListOf<RuntimeBodyState>()
        val probeUndoRequests = mutableListOf<Pair<String, Long>>()

        override fun startLunarFlight(handle: Long, shipId: String, recordedAtUnixMs: Long) {
            lunarFlightError?.let { throw it }
            lunarStarts += handle to shipId
        }
        override fun startRendezvousFlight(handle: Long, shipId: String, recordedAtUnixMs: Long) {
            rendezvousStarts += handle to shipId
        }
        override fun rendezvousTargetId(handle: Long, shipId: String) = rendezvousTarget
        override fun rendezvousState(handle: Long, shipId: String) = DoubleArray(27).apply {
            this[0] = 1.0; this[5] = 1.0; this[6] = 1_000.0
            this[18] = 25.0; this[19] = 75.0; this[21] = 1_000.0; this[24] = 8_000.0
        }
        override fun flightState(handle: Long, shipId: String) = DoubleArray(44).apply {
            this[0] = 1.0; this[3] = 100.0; this[4] = 500.0; this[5] = 600.0; this[20] = 1.0
        }

        override fun ensureLibraryLoaded(): NativeLibraryLoadOutcome = NativeLibraryLoadOutcome.Success

        override fun createSession(
            scenarioId: String,
            rootBranchId: String,
        ): NativeCreateSessionResult {
            createdScenarioIds += scenarioId
            return NativeCreateSessionResult(
                result = NativeResult(code = 0),
                handle = sessionHandles.removeFirstOrNull() ?: 42L,
                abiVersion = 13,
                cpuBackend = runtimeInfoCpuBackend,
                gpuBackend = runtimeInfoGpuBackend,
            )
        }

        override fun runtimeInfo(handle: Long): NativeRuntimeInfoResult {
            runtimeInfoHandles += handle
            return NativeRuntimeInfoResult(
                result = NativeResult(code = 0),
                abiVersion = 13,
                requestedCpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
                cpuBackend = runtimeInfoCpuBackend,
                gpuBackend = runtimeInfoGpuBackend,
                cpuFeatureFlags = 1,
                cpuSolverPath = 1,
                cpuFallbackCode = 0,
            )
        }

        override fun snapshotSummary(handle: Long): NativeSnapshotSummaryResult {
            validationSummaryError?.let { throw it }
            return snapshotSummary(bodyCount = 3)
        }

        override fun importSavedSession(templateHandle: Long, bytes: ByteArray) = NativeCreateSessionResult(
            result = NativeResult(code = 0), handle = validationHandle, abiVersion = 13,
            cpuBackend = runtimeInfoCpuBackend, gpuBackend = runtimeInfoGpuBackend,
        )

        override fun refreshSession(handle: Long): NativeSnapshotSummaryResult {
            refreshError?.let { throw it }
            if (failRefresh) {
                failRefresh = false
                return snapshotSummary(bodyCount = 0).copy(result = NativeResult(code = 3), revision = 0)
            }
            refreshedHandles += handle
            val summary = refreshResults.removeFirstOrNull()
                ?: latestSummary
                ?: snapshotSummary(bodyCount = STARTUP_EXPECTED_BODY_COUNT)
            latestSummary = summary
            return summary
        }

        override fun applyCommand(
            handle: Long,
            command: NativeRuntimeCommandPayload,
        ): NativeSnapshotSummaryResult {
            commandError?.let { throw it }
            appliedCommands += command
            val current = latestSummary ?: snapshotSummary(bodyCount = STARTUP_EXPECTED_BODY_COUNT)
            val summary = when (command.kind) {
                1 -> current.copy(paused = true)
                2 -> current.copy(paused = false)
                3 -> current.copy(
                    simSecondsPerRealSecond = command.simSecondsPerRealSecond
                )
                4 -> current.copy(observerMode = command.observerMode)
                0 -> current.copy(
                    epochSeconds = current.epochSeconds + command.deltaSeconds
                )
                else -> current
            }
            latestSummary = summary
            return summary
        }

        override fun playbackTick(handle: Long, realSeconds: Double, recordedAtUnixMs: Long): NativePlaybackTickResult {
            playbackTicks++
            val current = requireNotNull(latestSummary)
            val delta = realSeconds * current.simSecondsPerRealSecond
            val next = current.copy(epochSeconds = current.epochSeconds + delta, revision = current.revision + 1)
            latestSummary = next
            if (failAfterTick) {
                failAfterTick = false
                failRefresh = true
            }
            return NativePlaybackTickResult(0, delta, delta, false, next.epochSeconds, next.revision)
        }

        override fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult? {
            exportError?.let { throw it }
            error("exportVulkanScene is unused in this test")
        }

        override fun previewProbe(handle: Long, candidate: RuntimeBodyState, referenceBodyId: String, horizonSeconds: Double): NativeProbePreviewResult {
            probePreviewError?.let { throw it }
            val summary = requireNotNull(latestSummary)
            val reference = candidate.copy(bodyId = referenceBodyId, bodyClass = RuntimeBodyClass.Planet,
                massKg = 5.972e24, sourceMassKg = 5.972e24, radiusM = 6_371_000.0)
            return NativeProbePreviewResult(summary.revision, summary.epochSeconds, 10.0, false, false,
                reference.toNativeBodyValues(), doubleArrayOf(0.0, 7.0e6, 0.0, 0.0, 10.0, 7.0e6, 1.0e4, 0.0))
        }

        override fun commitProbe(handle: Long, candidate: RuntimeBodyState, expectedRevision: Long, expectedEpochSeconds: Double, recordedAtUnixMs: Long): NativeProbeCommitResult {
            probeCommitError?.let { throw it }
            val current = requireNotNull(latestSummary)
            check(current.revision == expectedRevision && current.epochSeconds == expectedEpochSeconds && current.paused)
            committedProbeCandidates += candidate
            latestSummary = current.copy(bodyCount = current.bodyCount + 1, revision = current.revision + 2)
            return NativeProbeCommitResult("probe-checkpoint", current.revision + 2, current.epochSeconds)
        }

        override fun undoProbe(handle: Long, checkpointId: String, committedRevision: Long, recordedAtUnixMs: Long) {
            probeUndoRequests += checkpointId to committedRevision
            latestSummary = requireNotNull(latestSummary).let { it.copy(bodyCount = it.bodyCount - 1, paused = true, revision = it.revision + 1) }
        }

        override fun releaseVulkanScene(packetHandle: Long) = Unit

        override fun destroySession(handle: Long) {
            destroyedHandles += handle
        }
    }

    private companion object {
        const val COMMAND_KIND_ADVANCE_EPOCH = 0
        const val COMMAND_KIND_SEED_CANONICAL_SOLAR_SYSTEM = 11
        const val COMMAND_KIND_RESUME_PLAYBACK = 2
        const val COMMAND_KIND_SET_PLAYBACK_RATE = 3
        const val COMMAND_KIND_SET_OBSERVER_MODE = 4
        const val COMMAND_KIND_FOCUS_BODY = 5
        const val COMMAND_KIND_SPAWN_BODY = 6

        fun hasCommandKind(command: NativeRuntimeCommandPayload, kind: Int): Boolean =
            command.kind == kind

        fun isProgressingAdvanceEpochCommand(command: NativeRuntimeCommandPayload): Boolean =
            command.kind == COMMAND_KIND_ADVANCE_EPOCH && command.deltaSeconds > 0.0

        const val STARTUP_EXPECTED_BODY_COUNT = 3

        fun probeCandidate() = RuntimeBodyState("probe", RuntimeBodyClass.Spacecraft, 1000.0, 17.0, 2.0,
            1.23456789012345e11, 2.34567890123456e10, 3.45678901234567e8, 12.3456789, 23.4567891, 34.5678912)

        fun snapshotSummary(
            scenarioId: String = "lab.earth-moon",
            bodyCount: Int,
            paused: Boolean = true,
            simSecondsPerRealSecond: Double = 1.0,
        ): NativeSnapshotSummaryResult = NativeSnapshotSummaryResult(
            result = NativeResult(code = 0),
            scenarioId = scenarioId,
            activeBranchId = "main",
            bodyCount = bodyCount,
            epochSeconds = 0.0,
            paused = paused,
            simSecondsPerRealSecond = simSecondsPerRealSecond,
            observerMode = RuntimeObserverMode.SystemFrame.nativeCode,
            timelineSemantics = 1,
        )
    }
}
