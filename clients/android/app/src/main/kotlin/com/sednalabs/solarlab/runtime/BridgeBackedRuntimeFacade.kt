package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

internal class BridgeBackedRuntimeFacade(
    private val bridge: RuntimeBridge
) : RuntimeFacade {
    private val _uiState = MutableStateFlow(
        ShellUiState(
            statusLine = "Preparing Rust runtime session",
            detailLine = "Android shell owns presentation and command flow only"
        )
    )

    override val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    override suspend fun startSession() {
        bridge.connect().collect { signal ->
            applySignal(signal)
        }
    }

    override suspend fun refresh() {
        bridge.refresh().forEach(::applySignal)
    }

    override suspend fun applyCommand(command: RuntimeCommand) {
        bridge.applyCommand(command).forEach(::applySignal)
    }

    private fun applySignal(signal: RuntimeSignal) {
        _uiState.value = when (signal) {
            is RuntimeSignal.Connected -> _uiState.value.copy(
                statusLine = "Connected to runtime boundary",
                detailLine = "Native session handle=${signal.handle}",
                sessionHandle = signal.handle
            )
            is RuntimeSignal.Status -> _uiState.value.copy(
                detailLine = signal.message
            )
            is RuntimeSignal.SnapshotUpdated -> _uiState.value.copy(
                statusLine = "Runtime snapshot refreshed",
                detailLine = "epoch=${signal.summary.epochSeconds}, bodies=${signal.summary.bodyCount}",
                snapshotSummary = "scenario=${signal.summary.scenarioId}, branch=${signal.summary.activeBranchId}, paused=${signal.summary.paused}"
            )
            is RuntimeSignal.CommandApplied -> _uiState.value.copy(
                statusLine = "Command applied",
                detailLine = "${signal.commandLabel} -> epoch=${signal.summary.epochSeconds}",
                snapshotSummary = "scenario=${signal.summary.scenarioId}, branch=${signal.summary.activeBranchId}, bodies=${signal.summary.bodyCount}"
            )
            is RuntimeSignal.RenderPacketReady -> _uiState.value.copy(
                statusLine = "Vulkan render packet exported",
                detailLine = "Scene revision=${signal.lease.sceneRevision}",
                renderPacketSummary = signal.lease.summaryLine
            )
            is RuntimeSignal.Unavailable -> _uiState.value.copy(
                statusLine = signal.message,
                detailLine = signal.detail
            )
        }
    }
}
