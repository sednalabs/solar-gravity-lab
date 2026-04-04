package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

class BridgeBackedRuntimeFacade(
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
            _uiState.value = when (signal) {
                is RuntimeSignal.Connected -> _uiState.value.copy(
                    statusLine = "Connected to runtime boundary",
                    detailLine = "Native session handle=${signal.handle}",
                    sessionHandle = signal.handle
                )
                is RuntimeSignal.Status -> _uiState.value.copy(
                    detailLine = signal.message
                )
                is RuntimeSignal.RenderPacketReady -> _uiState.value.copy(
                    statusLine = "Vulkan render packet exported",
                    detailLine = "Scene revision=${signal.packet.sceneRevision}",
                    renderPacketSummary = signal.packet.summaryLine()
                )
                is RuntimeSignal.Unavailable -> _uiState.value.copy(
                    statusLine = signal.message,
                    detailLine = signal.detail
                )
            }
        }
    }
}
