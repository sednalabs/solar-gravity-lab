package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Android-local implementation of `RuntimeFacade`.
 *
 * It is the shell's runtime adapter: receives boundary signals and materializes UI state
 * while keeping all business/physics behavior inside the native runtime.
 */
class BridgeBackedRuntimeFacade internal constructor(
    private val bridge: RuntimeBridge
) : RuntimeFacade {
    constructor() : this(JniRuntimeBridge())

    private val _uiState = MutableStateFlow(
        ShellUiState(
            statusLine = "Preparing Rust runtime session",
            detailLine = "Android shell waits for authoritative runtime state from Rust",
            noticeLine = "Android owns presentation, controls, and host rendering only",
            pendingActionLabel = "Connecting to runtime boundary",
        )
    )

    override val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    // Session handoff is one-way from bridge to UI state.
    // The flow is treated as the only driver for initial connection lifecycle.
    override suspend fun startSession() {
        _uiState.update { current ->
            current.copy(
                connectionState = SessionConnectionState.Connecting,
                statusLine = "Preparing Rust runtime session",
                detailLine = "Opening the Android shell against the Rust-owned runtime boundary",
                noticeLine = "Android owns presentation, controls, and host rendering only",
                noticeTone = ShellNoticeTone.Neutral,
                pendingActionLabel = "Connecting to runtime boundary",
                renderStatus = RenderStatusPresentation(
                    readiness = RenderHostReadiness.WaitingForSession,
                ),
                renderPacketSummary = null,
                snapshotSummary = null,
                observerModeCode = null,
                cameraFacingSummary = null,
                renderFrame = null,
            )
        }
        bridge.connect().collect(::applySignal)
    }

    // Explicit refresh and command paths are intentionally mapped 1:1 from UI intent to
    // runtime boundary outputs and then to immutable UI copies.
    override suspend fun refresh() {
        runShellAction(
            label = "Refreshing runtime snapshot",
            onStart = { current ->
                current.copy(
                    statusLine = "Refreshing runtime snapshot",
                    detailLine = "Pulling the latest authoritative snapshot and render packet",
                    pendingActionLabel = "Refreshing runtime snapshot",
                    renderStatus = current.renderStatus.copy(
                        readiness = if (current.sessionHandle != null) {
                            RenderHostReadiness.Refreshing
                        } else {
                            RenderHostReadiness.WaitingForSession
                        },
                        issue = null,
                    ),
                )
            }
        ) {
            bridge.refresh().forEach(::applySignal)
        }
    }

    override suspend fun applyCommand(command: RuntimeCommand) {
        val actionLabel = command.userFacingAction()
        runShellAction(
            label = actionLabel,
            onStart = { current ->
                current.copy(
                    statusLine = actionLabel,
                    detailLine = "Sending ${command.label} across the runtime boundary",
                    noticeLine = "Command intent: $actionLabel",
                    noticeTone = ShellNoticeTone.Neutral,
                    pendingActionLabel = actionLabel,
                )
            }
        ) {
            bridge.applyCommand(command).forEach(::applySignal)
        }
    }

    private fun applySignal(signal: RuntimeSignal) {
        when (signal) {
            is RuntimeSignal.Connected -> _uiState.update { current ->
                current.copy(
                    connectionState = SessionConnectionState.Active,
                    statusLine = "Runtime session connected",
                    detailLine = "Session handle ${signal.handle} is now owned by the Rust boundary",
                    noticeLine = "Session bridge established",
                    noticeTone = ShellNoticeTone.Positive,
                    pendingActionLabel = null,
                    sessionHandle = signal.handle,
                    renderStatus = current.renderStatus.copy(
                        readiness = RenderHostReadiness.Refreshing,
                        issue = null,
                    ),
                )
            }

            is RuntimeSignal.RuntimeInfoAvailable -> _uiState.update { current ->
                current.copy(
                    backendSummary = "${signal.cpuBackendLabel} + ${signal.gpuBackendLabel}",
                    noticeLine = "Runtime backend: ${signal.cpuBackendLabel} + ${signal.gpuBackendLabel}",
                    noticeTone = ShellNoticeTone.Positive,
                )
            }

            is RuntimeSignal.Notice -> _uiState.update { current ->
                current.copy(
                    noticeLine = signal.message,
                    noticeTone = signal.level.toShellTone(),
                )
            }

            is RuntimeSignal.SnapshotUpdated -> _uiState.update { current ->
                current.copy(
                    connectionState = SessionConnectionState.Active,
                    statusLine = if (signal.summary.paused) {
                        "Paused runtime snapshot ready"
                    } else {
                        "Live runtime snapshot refreshed"
                    },
                    detailLine = "Epoch ${signal.summary.epochSeconds.asEpochLabel()} with ${signal.summary.bodyCount} authoritative bodies",
                    pendingActionLabel = null,
                    snapshot = signal.summary.toSnapshotPresentation(),
                    snapshotSummary = "scenario=${signal.summary.scenarioId}, branch=${signal.summary.activeBranchId}, paused=${signal.summary.paused}",
                    observerModeCode = signal.summary.observerMode,
                    renderStatus = current.renderStatus.copy(
                        readiness = if (current.renderFrame != null) {
                            current.renderStatus.readiness
                        } else {
                            RenderHostReadiness.Refreshing
                        },
                    ),
                )
            }

            is RuntimeSignal.CommandApplied -> _uiState.update { current ->
                current.copy(
                    connectionState = SessionConnectionState.Active,
                    statusLine = "Runtime command applied",
                    detailLine = "${signal.commandLabel} at epoch ${signal.summary.epochSeconds.asEpochLabel()}",
                    noticeLine = "Runtime accepted ${signal.commandLabel}",
                    noticeTone = ShellNoticeTone.Positive,
                    pendingActionLabel = null,
                    snapshot = signal.summary.toSnapshotPresentation(),
                    snapshotSummary = "scenario=${signal.summary.scenarioId}, branch=${signal.summary.activeBranchId}, paused=${signal.summary.paused}",
                    observerModeCode = signal.summary.observerMode,
                    renderStatus = current.renderStatus.copy(
                        readiness = if (current.renderFrame != null) {
                            current.renderStatus.readiness
                        } else {
                            RenderHostReadiness.Refreshing
                        },
                    ),
                )
            }

            is RuntimeSignal.RenderPacketReady -> {
                val lease = signal.lease
                try {
                    val renderFrame = VulkanPacketRenderFrameDecoder.decode(lease.packet)
                    _uiState.update { current ->
                        current.copy(
                            connectionState = SessionConnectionState.Active,
                            statusLine = "Render host ready",
                            detailLine = "Scene revision ${lease.sceneRevision}",
                            noticeLine = "Fresh packet decoded for the Android render host",
                            noticeTone = ShellNoticeTone.Positive,
                            pendingActionLabel = null,
                            renderPacketSummary = lease.summaryLine,
                            observerModeCode = lease.packet.observerMode,
                            cameraFacingSummary = lease.packet.camera.toFacingSummary(),
                            renderStatus = RenderStatusPresentation(
                                readiness = RenderHostReadiness.Ready,
                                sceneRevision = lease.sceneRevision,
                                summary = lease.summaryLine,
                                renderedBodyCount = renderFrame.bodies.size,
                                renderedTracerCount = renderFrame.tracers.size,
                                renderedTrailCount = renderFrame.trails.size,
                            ),
                            renderFrame = renderFrame,
                        )
                    }
                } catch (error: Throwable) {
                    _uiState.update { current ->
                        current.copy(
                            statusLine = "Render packet decode failed",
                            detailLine = error.message ?: error::class.java.simpleName,
                            noticeLine = "The render host received a packet but could not decode it",
                            noticeTone = ShellNoticeTone.Critical,
                            pendingActionLabel = null,
                            renderPacketSummary = lease.summaryLine,
                            observerModeCode = lease.packet.observerMode,
                            cameraFacingSummary = lease.packet.camera.toFacingSummary(),
                            renderStatus = current.renderStatus.copy(
                                readiness = RenderHostReadiness.Failed,
                                sceneRevision = lease.sceneRevision,
                                summary = lease.summaryLine,
                                issue = error.message ?: error::class.java.simpleName,
                            ),
                            renderFrame = null,
                        )
                    }
                } finally {
                    lease.close()
                }
            }

            is RuntimeSignal.RenderUnavailable -> _uiState.update { current ->
                current.copy(
                    noticeLine = signal.reason,
                    noticeTone = ShellNoticeTone.Caution,
                    pendingActionLabel = null,
                    renderPacketSummary = signal.reason,
                    renderStatus = current.renderStatus.copy(
                        readiness = RenderHostReadiness.Unavailable,
                        issue = signal.reason,
                    ),
                )
            }

            is RuntimeSignal.Unavailable -> _uiState.update { current ->
                current.copy(
                    connectionState = SessionConnectionState.Unavailable,
                    statusLine = signal.message,
                    detailLine = signal.detail,
                    noticeLine = signal.detail ?: signal.message,
                    noticeTone = ShellNoticeTone.Critical,
                    pendingActionLabel = null,
                    sessionHandle = null,
                    snapshot = null,
                    snapshotSummary = null,
                    observerModeCode = null,
                    cameraFacingSummary = null,
                    renderStatus = current.renderStatus.copy(
                        readiness = RenderHostReadiness.Unavailable,
                        issue = signal.detail ?: signal.message,
                    ),
                    renderFrame = null,
                )
            }
        }
    }

    private suspend fun runShellAction(
        label: String,
        onStart: (ShellUiState) -> ShellUiState,
        action: suspend () -> Unit,
    ) {
        _uiState.update(onStart)
        try {
            action()
        } finally {
            _uiState.update { current ->
                if (current.pendingActionLabel == label) {
                    current.copy(pendingActionLabel = null)
                } else {
                    current
                }
            }
        }
    }
}

private fun NativeSnapshotSummaryResult.toSnapshotPresentation(): SnapshotPresentation {
    return SnapshotPresentation(
        scenarioId = scenarioId,
        activeBranchId = activeBranchId,
        bodyCount = bodyCount,
        epochSeconds = epochSeconds,
        paused = paused,
        simSecondsPerRealSecond = simSecondsPerRealSecond,
        observerModeLabel = RuntimeObserverMode.values()
            .firstOrNull { it.nativeCode == observerMode }
            ?.displayLabel()
            ?: "Unknown mode ($observerMode)",
    )
}

private fun RuntimeCommand.userFacingAction(): String = when (this) {
    is RuntimeCommand.AdvanceEpoch -> "Advance by ${deltaSeconds.asEpochLabel()}"
    RuntimeCommand.PausePlayback -> "Pause playback"
    RuntimeCommand.ResumePlayback -> "Resume playback"
    is RuntimeCommand.SetPlaybackRate -> "Set playback rate to ${simSecondsPerRealSecond.asRateLabel()}"
    is RuntimeCommand.SetObserverMode -> "Switch observer to ${mode.displayLabel()}"
    is RuntimeCommand.FocusBody -> "Focus ${bodyId ?: "active selection"}"
}

private fun RuntimeObserverMode.displayLabel(): String = when (this) {
    RuntimeObserverMode.Free -> "Free camera"
    RuntimeObserverMode.FollowSelected -> "Follow selected"
    RuntimeObserverMode.FollowHost -> "Follow host"
    RuntimeObserverMode.SystemFrame -> "System frame"
}

private fun RuntimeNoticeLevel.toShellTone(): ShellNoticeTone = when (this) {
    RuntimeNoticeLevel.Info -> ShellNoticeTone.Neutral
    RuntimeNoticeLevel.Success -> ShellNoticeTone.Positive
    RuntimeNoticeLevel.Warning -> ShellNoticeTone.Caution
    RuntimeNoticeLevel.Error -> ShellNoticeTone.Critical
}

private fun NativeVulkanCameraPacket.toFacingSummary(): String {
    return "target=($targetFromOriginX, $targetFromOriginY, $targetFromOriginZ), up=($upX, $upY, $upZ)"
}

private fun Double.asEpochLabel(): String = String.format(Locale.US, "%,.1f s", this)

private fun Double.asRateLabel(): String = String.format(Locale.US, "%,.2fx", this)
