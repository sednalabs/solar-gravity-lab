package com.sednalabs.solarlab.runtime

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeBackedRuntimeFacadeTest {
    @Test
    fun startSession_routesBoundaryConnectOffCallerThread() = runBlocking {
        val callerThreadName = Thread.currentThread().name
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "runtime-boundary-test")
        }.asCoroutineDispatcher().use { dispatcher ->
            val bridge = FakeRuntimeBridge(
                connectSignals = flowOf(RuntimeSignal.Connected(handle = 5L)),
            )
            val facade = BridgeBackedRuntimeFacade(
                bridge = bridge,
                developerTelemetryRecorder = DeveloperTelemetryRecorder(
                    enabled = false,
                    sinks = emptyList(),
                ),
                boundaryDispatcher = dispatcher,
            )

            facade.startSession()

            assertTrue(
                bridge.connectThreadNames.distinct().all { it.startsWith("runtime-boundary-test") },
            )
            assertTrue(bridge.connectThreadNames.none { it == callerThreadName })
        }
    }

    @Test
    fun startSession_recordsDeveloperTelemetryForBoundarySignals() = runBlocking {
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(
                RuntimeSignal.Connected(handle = 7L),
                RuntimeSignal.RuntimeInfoAvailable(
                    cpuBackendLabel = "simd-arm64",
                    gpuBackendLabel = "none",
                ),
                RuntimeSignal.Notice(
                    message = "Boundary ready",
                    level = RuntimeNoticeLevel.Success,
                ),
                RuntimeSignal.SnapshotUpdated(snapshotSummary(bodyCount = 3)),
                RuntimeSignal.RenderUnavailable("Render export unavailable: no packet"),
            ),
        )
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = emptyList(),
            ),
        )

        facade.startSession()

        val state = facade.uiState.value
        assertTrue(state.developerTelemetry.enabled)
        assertTrue(
            state.developerTelemetry.entries.map { it.category }.containsAll(
                listOf(
                    "session.start",
                    "session.connected",
                    "runtime.info",
                    "runtime.notice",
                    "snapshot.updated",
                    "render.unavailable",
                ),
            ),
        )
        assertEquals(RenderHostReadiness.Unavailable, state.renderStatus.readiness)
    }

    @Test
    fun startSession_preservesRequestedAndEffectiveGpuBackendTruth() = runBlocking {
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(
                RuntimeSignal.RuntimeInfoAvailable(
                    cpuBackendLabel = "simd-arm64",
                    requestedGpuBackendLabel = "vulkan+opencl",
                    gpuBackendLabel = "opencl",
                    workloadSummary = "opencl long-horizon assist",
                ),
            ),
        )
        val sink = RecordingTelemetrySink()
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = listOf(sink),
            ),
        )

        facade.startSession()

        val state = facade.uiState.value
        assertEquals(
            "cpu=simd-arm64 | gpu=requested vulkan+opencl -> effective opencl | workloads: opencl long-horizon assist",
            state.backendSummary,
        )
        assertTrue(
            sink.events
                .filter { it.category == "runtime.info" }
                .any { it.message.contains("requested-gpu=vulkan+opencl, gpu=opencl") },
        )
    }

    @Test
    fun startSession_preservesRequestedAndEffectiveCpuBackendTruth() = runBlocking {
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(
                RuntimeSignal.RuntimeInfoAvailable(
                    requestedCpuBackendLabel = "simd-arm64",
                    cpuBackendLabel = "reference-scalar",
                    cpuSolverPathLabel = "scalar.reference",
                    cpuFeatureSummary = "neon+sve2",
                    cpuFallbackSummary = "simd-arm64 requested on non-aarch64 host",
                    cpuScheduleSummary = "single-worker active, adaptive tiled candidate 8 workers",
                    cpuKernelCatalogSummary = "kernel catalog: 15 paths, active 1, eligible candidates 2, blocked candidates 10",
                    gpuBackendLabel = "none",
                ),
            ),
        )
        val sink = RecordingTelemetrySink()
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = listOf(sink),
            ),
        )

        facade.startSession()

        val state = facade.uiState.value
        assertEquals(
            "cpu=requested simd-arm64 -> effective reference-scalar | gpu=none | " +
                "solver: scalar.reference | cpu features: neon+sve2 | " +
                "cpu fallback: simd-arm64 requested on non-aarch64 host | " +
                "cpu scheduler: single-worker active, adaptive tiled candidate 8 workers | " +
                "cpu kernels: kernel catalog: 15 paths, active 1, eligible candidates 2, blocked candidates 10",
            state.backendSummary,
        )
        assertTrue(
            sink.events
                .filter { it.category == "runtime.info" }
                .any {
                    it.message.contains(
                        "requested-cpu=simd-arm64, cpu=reference-scalar, solver=scalar.reference",
                    ) && it.message.contains(
                        "cpu-scheduler=single-worker active, adaptive tiled candidate 8 workers",
                    ) && it.message.contains(
                        "cpu-kernels=kernel catalog: 15 paths, active 1, eligible candidates 2, blocked candidates 10",
                    )
                },
        )
    }

    @Test
    fun renderPacketReady_withZeroBodyPacketIsNotReady() = runBlocking {
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(
                RuntimeSignal.RenderPacketReady(
                    packetLease(
                        bodyCount = 0,
                        tracerCount = 0,
                        trailSpanCount = 0,
                        trailVertexCount = 0,
                        directionalLightCount = 0,
                    ),
                ),
            ),
        )
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = emptyList(),
            ),
        )

        facade.startSession()

        val state = facade.uiState.value
        assertEquals(RenderHostReadiness.Refreshing, state.renderStatus.readiness)
        assertEquals("Render packet empty", state.statusLine)
        assertNotNull(state.renderStatus.issue)
    }

    @Test
    fun renderPacketReady_compactsOversizedSceneRevisionForUi() = runBlocking {
        val hugeSceneRevision = "scenario=sol-system|" + "body|".repeat(80)
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(
                RuntimeSignal.RenderPacketReady(
                    packetLease(
                        bodyCount = 1,
                        tracerCount = 0,
                        trailSpanCount = 0,
                        trailVertexCount = 0,
                        directionalLightCount = 0,
                        sceneRevision = hugeSceneRevision,
                    ),
                ),
            ),
        )
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = emptyList(),
            ),
        )

        facade.startSession()

        val state = facade.uiState.value
        assertTrue((state.detailLine ?: "").length < hugeSceneRevision.length)
        assertTrue((state.renderStatus.sceneRevision ?: "").length < hugeSceneRevision.length)
        assertTrue((state.detailLine ?: "").contains("chars"))
    }

    @Test
    fun renderPacketReady_dedupesTelemetryWhenOnlyOversizedRevisionTailChanges() = runBlocking {
        val commonPrefix = "scenario=sol-system|" + "sun|class=Star|".repeat(12)
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(
                RuntimeSignal.RenderPacketReady(
                    packetLease(
                        bodyCount = 365,
                        tracerCount = 336,
                        trailSpanCount = 365,
                        trailVertexCount = 365,
                        directionalLightCount = 1,
                        sceneRevision = commonPrefix + "tail-a".repeat(120),
                    ),
                ),
                RuntimeSignal.RenderPacketReady(
                    packetLease(
                        bodyCount = 365,
                        tracerCount = 336,
                        trailSpanCount = 365,
                        trailVertexCount = 365,
                        directionalLightCount = 1,
                        sceneRevision = commonPrefix + "tail-b".repeat(120),
                    ),
                ),
            ),
        )
        val sink = RecordingTelemetrySink()
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = listOf(sink),
            ),
        )

        facade.startSession()

        val renderReadyEvents = sink.events.filter { it.category == "render.ready" }
        assertEquals(1, renderReadyEvents.size)
    }

    @Test
    fun applyCommand_recordsDeveloperTelemetryForCommandLifecycle() = runBlocking {
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(
                RuntimeSignal.Connected(handle = 9L),
                RuntimeSignal.SnapshotUpdated(snapshotSummary(bodyCount = 1)),
            ),
            applyCommandSignals = listOf(
                RuntimeSignal.CommandApplied(
                    command = RuntimeCommand.PausePlayback,
                    commandLabel = RuntimeCommand.PausePlayback.label,
                    summary = snapshotSummary(bodyCount = 1, paused = true),
                ),
                RuntimeSignal.RenderUnavailable("Render export unavailable: not ready"),
            ),
        )
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = emptyList(),
            ),
        )

        facade.startSession()
        facade.applyCommand(RuntimeCommand.PausePlayback)

        val categories = facade.uiState.value.developerTelemetry.entries.map { it.category }
        assertTrue(categories.contains("command.requested"))
        assertTrue(categories.contains("command.applied"))
        assertEquals(listOf(RuntimeCommand.PausePlayback), bridge.appliedCommands)
    }

    @Test
    fun applyCommand_routesBoundaryWorkOffCallerThread() = runBlocking {
        val callerThreadName = Thread.currentThread().name
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "runtime-command-boundary-test")
        }.asCoroutineDispatcher().use { dispatcher ->
            val bridge = FakeRuntimeBridge(
                connectSignals = flowOf(RuntimeSignal.Connected(handle = 21L)),
                applyCommandSignals = listOf(
                    RuntimeSignal.CommandApplied(
                        command = RuntimeCommand.PausePlayback,
                        commandLabel = RuntimeCommand.PausePlayback.label,
                        summary = snapshotSummary(bodyCount = 1, paused = true),
                    ),
                ),
            )
            val facade = BridgeBackedRuntimeFacade(
                bridge = bridge,
                developerTelemetryRecorder = DeveloperTelemetryRecorder(
                    enabled = false,
                    sinks = emptyList(),
                ),
                boundaryDispatcher = dispatcher,
            )

            facade.startSession()
            facade.applyCommand(RuntimeCommand.PausePlayback)

            assertTrue(
                bridge.applyCommandThreadNames.distinct().all {
                    it.startsWith("runtime-command-boundary-test")
                },
            )
            assertTrue(bridge.applyCommandThreadNames.none { it == callerThreadName })
        }
    }

    @Test
    fun loadScenario_resetsUiStateAndRoutesBoundaryWork() = runBlocking {
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(RuntimeSignal.Connected(handle = 21L)),
            loadScenarioSignals = listOf(
                RuntimeSignal.Connected(handle = 33L),
                RuntimeSignal.CommandApplied(
                    command = RuntimeCommand.FocusBody("jupiter"),
                    commandLabel = RuntimeCommand.FocusBody("jupiter").label,
                    summary = snapshotSummary(
                        scenarioId = "showcase.jupiter-system",
                        bodyCount = 31,
                        focusTargetBodyId = "jupiter",
                    ),
                ),
            ),
        )
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = emptyList(),
            ),
        )

        facade.startSession()
        facade.loadScenario("showcase.jupiter-system")

        val state = facade.uiState.value
        assertEquals(listOf("showcase.jupiter-system"), bridge.loadedScenarioIds)
        assertEquals(33L, state.sessionHandle)
        assertEquals("showcase.jupiter-system", state.snapshot?.scenarioId)
        assertEquals("jupiter", state.focusedBodyId)
        assertTrue(state.developerTelemetry.entries.any { it.category == "scenario.load.requested" })
    }

    @Test
    fun loadScenario_rejectsUnknownScenarioWithoutCallingBridge() = runBlocking {
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(RuntimeSignal.Connected(handle = 21L)),
        )
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = emptyList(),
            ),
        )

        facade.startSession()
        facade.loadScenario("missing-pack")

        val state = facade.uiState.value
        assertTrue(bridge.loadedScenarioIds.isEmpty())
        assertEquals(ShellNoticeTone.Caution, state.noticeTone)
        assertTrue(state.noticeLine?.contains("Unknown scenario pack") == true)
        assertTrue(state.developerTelemetry.entries.any { it.category == "scenario.load.rejected" })
    }


    @Test
    fun focusHistory_keepsMostRecentDistinctBodies() = runBlocking {
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(
                RuntimeSignal.Connected(handle = 11L),
                RuntimeSignal.CommandApplied(
                    command = RuntimeCommand.FocusBody("earth"),
                    commandLabel = RuntimeCommand.FocusBody("earth").label,
                    summary = snapshotSummary(bodyCount = 2, focusTargetBodyId = "earth"),
                ),
                RuntimeSignal.CommandApplied(
                    command = RuntimeCommand.FocusBody("moon"),
                    commandLabel = RuntimeCommand.FocusBody("moon").label,
                    summary = snapshotSummary(bodyCount = 2, focusTargetBodyId = "moon"),
                ),
                RuntimeSignal.CommandApplied(
                    command = RuntimeCommand.FocusBody("earth"),
                    commandLabel = RuntimeCommand.FocusBody("earth").label,
                    summary = snapshotSummary(bodyCount = 2, focusTargetBodyId = "earth"),
                ),
            ),
        )
        val facade = BridgeBackedRuntimeFacade(
            bridge = bridge,
            developerTelemetryRecorder = DeveloperTelemetryRecorder(
                enabled = true,
                sinks = emptyList(),
            ),
        )

        facade.startSession()

        assertEquals("earth", facade.uiState.value.focusedBodyId)
        assertEquals(listOf("earth", "moon"), facade.uiState.value.recentFocusedBodyIds)
    }

    @Test
    fun canceledRefreshAndCommandPreserveTheActiveSession() = runBlocking {
        for (command in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val bridge = object : RuntimeBridge {
                override fun connect(): Flow<RuntimeSignal> = flowOf(RuntimeSignal.Connected(17L))
                override suspend fun loadScenario(scenarioId: String): List<RuntimeSignal> = emptyList()
                override suspend fun refresh(): List<RuntimeSignal> { entered.complete(Unit); CompletableDeferred<Unit>().await(); return emptyList() }
                override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> { entered.complete(Unit); CompletableDeferred<Unit>().await(); return emptyList() }
            }
            val facade = BridgeBackedRuntimeFacade(bridge, DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()))
            facade.startSession()
            val operation = async {
                if (command) facade.applyCommand(RuntimeCommand.PausePlayback) else facade.refresh()
            }
            entered.await()
            operation.cancelAndJoin()
            assertTrue(operation.isCancelled)
            assertEquals(SessionConnectionState.Active, facade.uiState.value.connectionState)
            assertEquals(17L, facade.uiState.value.sessionHandle)
            assertEquals(null, facade.uiState.value.pendingActionLabel)
        }
    }

    @Test
    fun boundaryCancellationIsRethrown() = runBlocking {
        val bridge = object : RuntimeBridge {
            override fun connect(): Flow<RuntimeSignal> = flowOf(RuntimeSignal.Connected(17L))
            override suspend fun loadScenario(scenarioId: String): List<RuntimeSignal> = emptyList()
            override suspend fun refresh(): List<RuntimeSignal> = throw CancellationException("cancel refresh")
            override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> = throw CancellationException("cancel command")
        }
        val facade = BridgeBackedRuntimeFacade(bridge, DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()))
        facade.startSession()
        for (command in listOf(false, true)) {
            var propagated = false
            try {
                if (command) facade.applyCommand(RuntimeCommand.PausePlayback) else facade.refresh()
            } catch (error: CancellationException) {
                propagated = true
            }
            assertTrue(propagated)
            assertEquals(SessionConnectionState.Active, facade.uiState.value.connectionState)
        }
    }

    @Test
    fun concurrentActionsRecordEachRequestOnce() = runBlocking {
        val sink = RecordingTelemetrySink()
        val facade = BridgeBackedRuntimeFacade(
            FakeRuntimeBridge(flowOf(RuntimeSignal.Connected(17L))),
            DeveloperTelemetryRecorder(maxEntries = 128, enabled = true, sinks = listOf(sink)),
        )
        facade.startSession()
        (0 until 64).map { async(Dispatchers.Default) { facade.refresh() } }.awaitAll()
        assertEquals(64, sink.events.count { it.category == "session.refresh" })
        assertEquals(64, facade.uiState.value.developerTelemetry.entries.count { it.category == "session.refresh" })
    }

    @Test
    fun refreshBoundaryFailureStillSurfaces() = runBlocking {
        val bridge = object : RuntimeBridge {
            override fun connect(): Flow<RuntimeSignal> = flowOf(RuntimeSignal.Connected(17L))
            override suspend fun loadScenario(scenarioId: String): List<RuntimeSignal> = emptyList()
            override suspend fun refresh(): List<RuntimeSignal> = throw IllegalStateException("boundary failed")
            override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> = emptyList()
        }
        val facade = BridgeBackedRuntimeFacade(bridge, DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()))
        facade.startSession()
        facade.refresh()
        assertEquals(SessionConnectionState.Unavailable, facade.uiState.value.connectionState)
        assertEquals("boundary failed", facade.uiState.value.detailLine)
    }

    @Test
    fun nativeSnapshotMetadataOverridesCommandIntentAndPreviousState() = runBlocking {
        val summaries = listOf(
            snapshotSummary(bodyCount = 1, focusTargetBodyId = "earth"),
            snapshotSummary(bodyCount = 0),
            snapshotSummary(bodyCount = 0, activeCheckpointId = "checkpoint-1", activeCheckpointLabel = "first"),
            snapshotSummary(bodyCount = 0, activeCheckpointId = "checkpoint-2", activeCheckpointLabel = "second"),
        ).iterator()
        val bridge = object : RuntimeBridge {
            override fun connect(): Flow<RuntimeSignal> = flowOf(RuntimeSignal.Connected(17L))
            override suspend fun loadScenario(scenarioId: String): List<RuntimeSignal> = emptyList()
            override suspend fun refresh(): List<RuntimeSignal> = listOf(RuntimeSignal.SnapshotUpdated(
                snapshotSummary(bodyCount = 0, activeCheckpointId = "checkpoint-2", activeCheckpointLabel = "second")))
            override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> =
                listOf(RuntimeSignal.CommandApplied(command, command.label, summaries.next()))
        }
        val facade = BridgeBackedRuntimeFacade(bridge, DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()))
        facade.startSession()
        facade.applyCommand(RuntimeCommand.FocusBody("earth"))
        assertEquals("earth", facade.uiState.value.focusedBodyId)
        facade.applyCommand(RuntimeCommand.RemoveBody("earth"))
        assertEquals(null, facade.uiState.value.focusedBodyId)
        facade.applyCommand(RuntimeCommand.CreateCheckpoint())
        assertEquals("checkpoint-1", facade.uiState.value.activeCheckpointId)
        assertEquals("first", facade.uiState.value.activeCheckpointLabel)
        facade.applyCommand(RuntimeCommand.CreateBranchFromCheckpoint("checkpoint-2"))
        assertEquals("checkpoint-2", facade.uiState.value.activeCheckpointId)
        assertEquals("second", facade.uiState.value.activeCheckpointLabel)
        facade.refresh()
        assertEquals("checkpoint-2", facade.uiState.value.activeCheckpointId)
        assertEquals("second", facade.uiState.value.activeCheckpointLabel)
    }

    private fun packetLease(
        bodyCount: Int,
        tracerCount: Int,
        trailSpanCount: Int,
        trailVertexCount: Int,
        directionalLightCount: Int,
        sceneRevision: String = "v1",
    ): PacketLease = PacketLease(
        packetHandle = 42L,
        packet = NativeVulkanScenePacket(
            packetHandle = 42L,
            sceneRevision = sceneRevision,
            epochSeconds = 1.0,
            observerMode = 0,
            timelineSemantics = 0,
            camera = NativeVulkanCameraPacket(
                frameOriginX = 0.0,
                frameOriginY = 0.0,
                frameOriginZ = 0.0,
                positionFromOriginX = 0f,
                positionFromOriginY = 0f,
                positionFromOriginZ = 0f,
                targetFromOriginX = 0f,
                targetFromOriginY = 0f,
                targetFromOriginZ = 0f,
                upX = 0f,
                upY = 1f,
                upZ = 0f,
                verticalFovDegrees = 60f,
                exposure = 1f,
            ),
            bodyCount = bodyCount,
            tracerCount = tracerCount,
            trailSpanCount = trailSpanCount,
            trailVertexCount = trailVertexCount,
            directionalLightCount = directionalLightCount,
            diagnostics = NativeRenderDiagnostics(
                frameNumber = 0L,
                cpuExtractMs = 0f,
                gpuUploadMs = 0f,
                droppedFrames = 0,
            ),
            provenanceSource = null,
            provenanceVersion = null,
            provenanceManifestId = null,
            provenanceManifestDigest = null,
            provenancePackageDigest = null,
            bodyInstances = null,
            tracerInstances = null,
            trailSpans = null,
            trailVertices = null,
            directionalLights = null,
        ),
        summaryLineValue = "scenario=sol-system, bodies=0, tracers=0, trails=0/0, lights=0, uploadBytes=0",
        releaseAction = {},
    )

    private class FakeRuntimeBridge(
        private val connectSignals: Flow<RuntimeSignal>,
        private val refreshSignals: List<RuntimeSignal> = emptyList(),
        private val applyCommandSignals: List<RuntimeSignal> = emptyList(),
        private val loadScenarioSignals: List<RuntimeSignal> = emptyList(),
    ) : RuntimeBridge {
        val appliedCommands = mutableListOf<RuntimeCommand>()
        val loadedScenarioIds = mutableListOf<String>()
        val connectThreadNames = mutableListOf<String>()
        val refreshThreadNames = mutableListOf<String>()
        val applyCommandThreadNames = mutableListOf<String>()
        val loadScenarioThreadNames = mutableListOf<String>()

        override fun connect(): Flow<RuntimeSignal> {
            connectThreadNames += Thread.currentThread().name
            return connectSignals
        }

        override suspend fun loadScenario(scenarioId: String): List<RuntimeSignal> {
            loadScenarioThreadNames += Thread.currentThread().name
            loadedScenarioIds += scenarioId
            return loadScenarioSignals
        }

        override suspend fun refresh(): List<RuntimeSignal> {
            refreshThreadNames += Thread.currentThread().name
            return refreshSignals
        }

        override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> {
            applyCommandThreadNames += Thread.currentThread().name
            appliedCommands += command
            return applyCommandSignals
        }
    }

    private companion object {
        fun snapshotSummary(
            scenarioId: String = "sol-system",
            bodyCount: Int,
            paused: Boolean = false,
            focusTargetBodyId: String? = null,
            activeCheckpointId: String? = null,
            activeCheckpointLabel: String? = null,
        ): NativeSnapshotSummaryResult = NativeSnapshotSummaryResult(
            result = NativeResult(code = 0),
            scenarioId = scenarioId,
            activeBranchId = "main",
            bodyCount = bodyCount,
            epochSeconds = 0.0,
            paused = paused,
            simSecondsPerRealSecond = 1.0,
            observerMode = RuntimeObserverMode.SystemFrame.nativeCode,
            timelineSemantics = 1,
            focusTargetBodyId = focusTargetBodyId,
            activeCheckpointId = activeCheckpointId,
            activeCheckpointLabel = activeCheckpointLabel,
        )
    }

    private class RecordingTelemetrySink : DeveloperTelemetrySink {
        val events = mutableListOf<DeveloperTelemetryEvent>()

        override fun publish(event: DeveloperTelemetryEvent) {
            events += event
        }
    }
}
