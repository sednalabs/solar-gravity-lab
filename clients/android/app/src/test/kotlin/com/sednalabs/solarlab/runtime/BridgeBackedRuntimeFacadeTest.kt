package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeBackedRuntimeFacadeTest {
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

    private class FakeRuntimeBridge(
        private val connectSignals: Flow<RuntimeSignal>,
        private val refreshSignals: List<RuntimeSignal> = emptyList(),
        private val applyCommandSignals: List<RuntimeSignal> = emptyList(),
    ) : RuntimeBridge {
        val appliedCommands = mutableListOf<RuntimeCommand>()

        override fun connect(): Flow<RuntimeSignal> = connectSignals

        override suspend fun refresh(): List<RuntimeSignal> = refreshSignals

        override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> {
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
}
