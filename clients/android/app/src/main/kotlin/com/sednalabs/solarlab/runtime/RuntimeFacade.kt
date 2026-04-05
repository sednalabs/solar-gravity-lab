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

enum class SessionConnectionState {
    Connecting,
    Active,
    Unavailable,
}

enum class ShellNoticeTone {
    Neutral,
    Positive,
    Caution,
    Critical,
}

enum class RenderHostReadiness {
    WaitingForSession,
    Refreshing,
    Ready,
    Unavailable,
    Failed,
}

data class SnapshotPresentation(
    val scenarioId: String,
    val activeBranchId: String,
    val bodyCount: Int,
    val epochSeconds: Double,
    val paused: Boolean,
    val simSecondsPerRealSecond: Double,
    val observerModeLabel: String,
)

data class RenderStatusPresentation(
    val readiness: RenderHostReadiness = RenderHostReadiness.WaitingForSession,
    val sceneRevision: String? = null,
    val summary: String? = null,
    val renderedBodyCount: Int = 0,
    val renderedTracerCount: Int = 0,
    val renderedTrailCount: Int = 0,
    val issue: String? = null,
)

data class ShellUiState(
    val connectionState: SessionConnectionState = SessionConnectionState.Connecting,
    val statusLine: String,
    val detailLine: String? = null,
    val noticeLine: String? = null,
    val noticeTone: ShellNoticeTone = ShellNoticeTone.Neutral,
    val pendingActionLabel: String? = null,
    val sessionHandle: Long? = null,
    val backendSummary: String? = null,
    val snapshot: SnapshotPresentation? = null,
    val renderStatus: RenderStatusPresentation = RenderStatusPresentation(),
    val renderFrame: RenderFrame? = null,
)
