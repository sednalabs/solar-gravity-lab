package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * Public seam for shell/runtime orchestration.
 */
interface RuntimeFacade {
    // State stream is immutable from the UI perspective and sourced from runtime signals.
    val uiState: StateFlow<ShellUiState>

    // One-time bind/handshake into native runtime.
    suspend fun startSession()

    // Pull latest runtime snapshot/render packet on demand.
    suspend fun refresh()

    // Apply shell-level command through the runtime boundary.
    suspend fun applyCommand(command: RuntimeCommand)
}

data class ShellUiState(
    val statusLine: String,
    val detailLine: String? = null,
    val sessionHandle: Long? = null,
    val renderPacketSummary: String? = null,
    val snapshotSummary: String? = null,
    val renderFrame: RenderFrame? = null,
)
