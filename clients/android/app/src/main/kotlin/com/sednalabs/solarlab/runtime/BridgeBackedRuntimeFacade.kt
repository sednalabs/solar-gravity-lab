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
                    issue = null,
                    isDegraded = false,
                    degradationReason = null,
                ),
                renderPacketSummary = null,
                snapshotSummary = null,
                observerModeCode = null,
                cameraFacingSummary = null,
                focusedBodyId = null,
                activeCheckpointId = null,
                activeCheckpointLabel = null,
                renderFrame = null,
            )
        }
        try {
            bridge.connect().collect(::applySignal)
        } catch (error: Throwable) {
            surfaceShellFailure(
                statusLine = "Runtime startup failed",
                detailLine = error.message ?: error::class.java.simpleName,
                noticeLine = "Android shell caught an unhandled startup failure instead of crashing",
            )
        }
    }

    // Explicit refresh and command paths are intentionally mapped 1:1 from UI intent to
    // runtime boundary outputs and then to immutable UI copies.
    override suspend fun refresh() {
        try {
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
        } catch (error: Throwable) {
            surfaceShellFailure(
                statusLine = "Runtime refresh failed",
                detailLine = error.message ?: error::class.java.simpleName,
                noticeLine = "Android shell caught an unhandled refresh failure instead of crashing",
            )
        }
    }

    override suspend fun applyCommand(command: RuntimeCommand) {
        val actionLabel = command.userFacingAction()
        try {
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
        } catch (error: Throwable) {
            surfaceShellFailure(
                statusLine = "Runtime command failed",
                detailLine = error.message ?: error::class.java.simpleName,
                noticeLine = "Android shell caught an unhandled command failure instead of crashing",
            )
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
                val snapshot = signal.summary.toSnapshotPresentation(
                    focusTargetBodyId = current.focusedBodyId,
                    activeCheckpointId = current.activeCheckpointId,
                    activeCheckpointLabel = current.activeCheckpointLabel,
                )
                current.copy(
                    connectionState = SessionConnectionState.Active,
                    statusLine = if (signal.summary.paused) {
                        "Paused runtime snapshot ready"
                    } else {
                        "Live runtime snapshot refreshed"
                    },
                    detailLine = "Epoch ${signal.summary.epochSeconds.asEpochLabel()} with ${signal.summary.bodyCount} authoritative bodies",
                    pendingActionLabel = null,
                    snapshot = snapshot,
                    snapshotSummary = snapshot.toSnapshotSummaryLine(),
                    observerModeCode = signal.summary.observerMode,
                    focusedBodyId = snapshot.focusTargetBodyId,
                    activeCheckpointId = snapshot.activeCheckpointId,
                    activeCheckpointLabel = snapshot.activeCheckpointLabel,
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
                val focusedBodyId = signal.command.focusTargetBodyId(current.focusedBodyId)
                val checkpointId = signal.command.activeCheckpointId(current.activeCheckpointId)
                val checkpointLabel = signal.command.activeCheckpointLabel(current.activeCheckpointLabel)
                val snapshot = signal.summary.toSnapshotPresentation(
                    focusTargetBodyId = focusedBodyId,
                    activeCheckpointId = checkpointId,
                    activeCheckpointLabel = checkpointLabel,
                )
                current.copy(
                    connectionState = SessionConnectionState.Active,
                    statusLine = "Runtime command applied",
                    detailLine = "${signal.commandLabel} at epoch ${signal.summary.epochSeconds.asEpochLabel()}",
                    noticeLine = "Runtime accepted ${signal.commandLabel}",
                    noticeTone = ShellNoticeTone.Positive,
                    pendingActionLabel = null,
                    snapshot = snapshot,
                    snapshotSummary = snapshot.toSnapshotSummaryLine(),
                    observerModeCode = signal.summary.observerMode,
                    focusedBodyId = focusedBodyId,
                    activeCheckpointId = checkpointId,
                    activeCheckpointLabel = checkpointLabel,
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
                val packet = lease.packet
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
                            renderStatus = packet.toRenderStatusPresentation(
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
                                isDegraded = true,
                                degradationReason = error.message ?: error::class.java.simpleName,
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
                        isDegraded = true,
                        degradationReason = signal.reason,
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
                    focusedBodyId = null,
                    activeCheckpointId = null,
                    activeCheckpointLabel = null,
                    renderStatus = current.renderStatus.copy(
                        readiness = RenderHostReadiness.Unavailable,
                        isDegraded = true,
                        degradationReason = signal.detail ?: signal.message,
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

    private fun surfaceShellFailure(
        statusLine: String,
        detailLine: String,
        noticeLine: String,
    ) {
        _uiState.update { current ->
            current.copy(
                connectionState = SessionConnectionState.Unavailable,
                statusLine = statusLine,
                detailLine = detailLine,
                noticeLine = noticeLine,
                noticeTone = ShellNoticeTone.Critical,
                pendingActionLabel = null,
                renderStatus = current.renderStatus.copy(
                    readiness = RenderHostReadiness.Unavailable,
                    isDegraded = true,
                    degradationReason = detailLine,
                    issue = detailLine,
                ),
                renderFrame = null,
            )
        }
    }
}

private fun NativeSnapshotSummaryResult.toSnapshotPresentation(): SnapshotPresentation {
    return toSnapshotPresentation(
        focusTargetBodyId = null,
        activeCheckpointId = null,
        activeCheckpointLabel = null,
    )
}

private fun NativeSnapshotSummaryResult.toSnapshotPresentation(
    focusTargetBodyId: String?,
    activeCheckpointId: String?,
    activeCheckpointLabel: String?,
): SnapshotPresentation {
    return SnapshotPresentation(
        scenarioId = scenarioId,
        activeBranchId = activeBranchId,
        bodyCount = bodyCount,
        epochSeconds = epochSeconds,
        paused = paused,
        simSecondsPerRealSecond = simSecondsPerRealSecond,
        focusTargetBodyId = focusTargetBodyId,
        activeCheckpointId = activeCheckpointId,
        activeCheckpointLabel = activeCheckpointLabel,
        timelineSemantics = timelineSemantics,
        timelineSemanticsLabel = timelineSemantics.toTimelineSemanticsLabel(),
        observerModeLabel = RuntimeObserverMode.values()
            .firstOrNull { it.nativeCode == observerMode }
            ?.displayLabel()
            ?: "Unknown mode ($observerMode)",
    )
}

private fun SnapshotPresentation.toSnapshotSummaryLine(): String {
    val focusText = focusTargetBodyId?.let { ", focus=$it" } ?: ""
    val checkpointText = activeCheckpointId?.let {
        if (activeCheckpointLabel.isNullOrBlank()) {
            ", checkpoint=$it"
        } else {
            ", checkpoint=$it (${activeCheckpointLabel})"
        }
    } ?: ""
    return "scenario=$scenarioId branch=$activeBranchId bodies=$bodyCount epoch=${epochSeconds.asEpochLabel()}" +
        ", timeline=${timelineSemanticsLabel}, mode=$observerModeLabel$focusText$checkpointText"
}

private fun Int.toTimelineSemanticsLabel(): String = when (this) {
    1 -> "Branched sandbox"
    else -> "Timeline semantics $this"
}

private fun NativeRenderDiagnostics.toRenderIssue(): String? {
    if (droppedFrames <= 0) {
        return null
    }

    return buildString {
        append("frame#")
        append(frameNumber)
        append(": dropped_frames=")
        append(droppedFrames)
    }
}

private fun NativeRenderDiagnostics.isDegraded(): Boolean = droppedFrames > 0

private fun NativeVulkanScenePacket.toRenderStatusPresentation(
    readiness: RenderHostReadiness,
    sceneRevision: String,
    summary: String,
    renderedBodyCount: Int,
    renderedTracerCount: Int,
    renderedTrailCount: Int,
): RenderStatusPresentation {
    return RenderStatusPresentation(
        readiness = readiness,
        sceneRevision = sceneRevision,
        summary = summary,
        renderedBodyCount = renderedBodyCount,
        renderedTracerCount = renderedTracerCount,
        renderedTrailCount = renderedTrailCount,
        directionalLightCount = directionalLightCount,
        diagnosticsFrameNumber = diagnostics.frameNumber,
        diagnosticsCpuExtractMs = diagnostics.cpuExtractMs,
        diagnosticsGpuUploadMs = diagnostics.gpuUploadMs,
        diagnosticsDroppedFrames = diagnostics.droppedFrames,
        provenanceSource = provenanceSource,
        provenanceVersion = provenanceVersion,
        provenanceManifestId = provenanceManifestId,
        provenanceManifestDigest = provenanceManifestDigest,
        provenancePackageDigest = provenancePackageDigest,
        isDegraded = diagnostics.isDegraded(),
        degradationReason = diagnostics.toRenderIssue(),
        issue = diagnostics.toRenderIssue(),
    )
}

private fun RuntimeCommand.focusTargetBodyId(existing: String?): String? {
    return when (this) {
        is RuntimeCommand.FocusBody -> bodyId
        else -> existing
    }
}

private fun RuntimeCommand.activeCheckpointId(existing: String?): String? {
    return when (this) {
        is RuntimeCommand.CreateCheckpoint -> checkpointId ?: existing
        is RuntimeCommand.CreateBranchFromCheckpoint -> checkpointId
        else -> existing
    }
}

private fun RuntimeCommand.activeCheckpointLabel(existing: String?): String? {
    return when (this) {
        is RuntimeCommand.CreateCheckpoint -> checkpointLabel ?: existing
        else -> existing
    }
}

private fun RuntimeCommand.userFacingAction(): String = when (this) {
    is RuntimeCommand.AdvanceEpoch -> "Advance by ${deltaSeconds.asEpochLabel()}"
    RuntimeCommand.PausePlayback -> "Pause playback"
    RuntimeCommand.ResumePlayback -> "Resume playback"
    is RuntimeCommand.SetPlaybackRate -> "Set playback rate to ${simSecondsPerRealSecond.asRateLabel()}"
    is RuntimeCommand.SetObserverMode -> "Switch observer to ${mode.displayLabel()}"
    is RuntimeCommand.FocusBody -> "Focus ${bodyId ?: "active selection"}"
    is RuntimeCommand.SpawnBody -> "Spawn ${bodyId}"
    is RuntimeCommand.RemoveBody -> "Remove ${bodyId}"
    is RuntimeCommand.SetBodyKinematics -> "Update kinematics for ${bodyId}"
    is RuntimeCommand.CreateCheckpoint -> "Create checkpoint ${checkpointId ?: "(auto)"}"
    is RuntimeCommand.CreateBranchFromCheckpoint -> "Create branch from ${checkpointId}"
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
