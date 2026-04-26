package com.sednalabs.solarlab.runtime

import java.util.concurrent.Executors
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
    fun focusHistory_keepsMostRecentDistinctBodies() = runBlocking {
        val bridge = FakeRuntimeBridge(
            connectSignals = flowOf(
                RuntimeSignal.Connected(handle = 11L),
                RuntimeSignal.CommandApplied(
                    command = RuntimeCommand.FocusBody("earth"),
                    commandLabel = RuntimeCommand.FocusBody("earth").label,
                    summary = snapshotSummary(bodyCount = 2),
                ),
                RuntimeSignal.CommandApplied(
                    command = RuntimeCommand.FocusBody("moon"),
                    commandLabel = RuntimeCommand.FocusBody("moon").label,
                    summary = snapshotSummary(bodyCount = 2),
                ),
                RuntimeSignal.CommandApplied(
                    command = RuntimeCommand.FocusBody("earth"),
                    commandLabel = RuntimeCommand.FocusBody("earth").label,
                    summary = snapshotSummary(bodyCount = 2),
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
    ) : RuntimeBridge {
        val appliedCommands = mutableListOf<RuntimeCommand>()
        val connectThreadNames = mutableListOf<String>()
        val refreshThreadNames = mutableListOf<String>()
        val applyCommandThreadNames = mutableListOf<String>()

        override fun connect(): Flow<RuntimeSignal> {
            connectThreadNames += Thread.currentThread().name
            return connectSignals
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
            bodyCount: Int,
            paused: Boolean = false,
        ): NativeSnapshotSummaryResult = NativeSnapshotSummaryResult(
            result = NativeResult(code = 0),
            scenarioId = "sol-system",
            activeBranchId = "main",
            bodyCount = bodyCount,
            epochSeconds = 0.0,
            paused = paused,
            simSecondsPerRealSecond = 1.0,
            observerMode = RuntimeObserverMode.SystemFrame.nativeCode,
            timelineSemantics = 1,
        )
    }

    private class RecordingTelemetrySink : DeveloperTelemetrySink {
        val events = mutableListOf<DeveloperTelemetryEvent>()

        override fun publish(event: DeveloperTelemetryEvent) {
            events += event
        }
    }
}
