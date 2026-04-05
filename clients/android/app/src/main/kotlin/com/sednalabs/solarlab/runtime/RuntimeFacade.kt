package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.StateFlow

interface RuntimeFacade {
    val uiState: StateFlow<ShellUiState>

    suspend fun startSession()

    suspend fun refresh()

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
