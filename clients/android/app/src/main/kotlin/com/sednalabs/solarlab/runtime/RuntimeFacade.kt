package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.StateFlow

interface RuntimeFacade {
    val uiState: StateFlow<ShellUiState>

    suspend fun startSession()
}

data class ShellUiState(
    val statusLine: String,
    val detailLine: String? = null
)
