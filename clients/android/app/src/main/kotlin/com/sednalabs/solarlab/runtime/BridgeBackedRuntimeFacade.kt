package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Android-local implementation of `RuntimeFacade`.
 *
 * It is the shell's runtime adapter: receives boundary signals and materializes UI state
 * while keeping all business/physics behavior inside the native runtime.
 * 
 * This facade serves as the "source of truth" for the Android UI, transforming 
 * low-level boundary signals (handles, revision counts) into a stable ShellUiState.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeBackedRuntimeFacade internal constructor(
    private val bridge: RuntimeBridge,
    private val developerTelemetryRecorder: DeveloperTelemetryRecorder = defaultDeveloperTelemetryRecorder(),
    private val boundaryDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : RuntimeFacade {
    constructor() : this(JniRuntimeBridge())

    private var lastSnapshotTelemetryKey: String? = null
    private var lastRenderTelemetryKey: String? = null
    private var lastRenderUnavailableReason: String? = null

    private val _uiState = MutableStateFlow(
        ShellUiState(
            statusLine = "Preparing Rust runtime session",
            detailLine = "Android shell waits for authoritative runtime state from Rust",
            noticeLine = "Android owns presentation, controls, and host rendering only",
            pendingActionLabel = "Connecting to runtime boundary",
            developerTelemetry = developerTelemetryRecorder.presentation(),
        )
    )

    override val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    /**
     * Initializes the connection to the Rust-owned runtime boundary.
     * 
     * Session handoff is one-way from the bridge to the UI state. The bridge's 
     * connection flow is the primary driver for the initial shell lifecycle.
     */
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
                recentFocusedBodyIds = emptyList(),
                activeCheckpointId = null,
                activeCheckpointLabel = null,
                renderFrame = null,
                developerTelemetry = recordTelemetry(
                    level = DeveloperTelemetryLevel.Info,
                    category = "session.start",
                    message = "Opening Rust runtime session from Android shell",
                ),
            )
        }
        try {
            withContext(boundaryDispatcher) {
                bridge.connect().collect(::applySignal)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                return
            }
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
                telemetry = TelemetryEvent(
                    level = DeveloperTelemetryLevel.Info,
                    category = "session.refresh",
                    message = "Requested runtime snapshot and render packet refresh",
                ),
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
                withContext(boundaryDispatcher) {
                    bridge.refresh(advancePlayback = true).forEach(::applySignal)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                return
            }
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
                telemetry = TelemetryEvent(
                    level = DeveloperTelemetryLevel.Info,
                    category = "command.requested",
                    message = "$actionLabel (${command.label})",
                ),
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
                withContext(boundaryDispatcher) {
                    bridge.applyCommand(command).forEach(::applySignal)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                return
            }
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
                    developerTelemetry = recordTelemetry(
                        level = DeveloperTelemetryLevel.Info,
                        category = "session.connected",
                        message = "Bound runtime session handle ${signal.handle}",
                    ),
                )
            }

            is RuntimeSignal.RuntimeInfoAvailable -> _uiState.update { current ->
                val backendSummary = backendSummaryLabel(
                    cpuBackendLabel = signal.cpuBackendLabel,
                    gpuBackendLabel = signal.gpuBackendLabel,
                    workloadSummary = signal.workloadSummary,
                    interopErrorBudgetSummary = signal.interopErrorBudgetSummary,
                )
                current.copy(
                    backendSummary = backendSummary,
                    noticeLine = "Runtime backend: $backendSummary",
                    noticeTone = ShellNoticeTone.Positive,
                    developerTelemetry = recordTelemetry(
                        level = DeveloperTelemetryLevel.Info,
                        category = "runtime.info",
                        message = runtimeInfoTelemetryMessage(signal),
                    ),
                )
            }

            is RuntimeSignal.Notice -> _uiState.update { current ->
                current.copy(
                    noticeLine = signal.message,
                    noticeTone = signal.level.toShellTone(),
                    developerTelemetry = recordTelemetry(
                        level = signal.level.toDeveloperTelemetryLevel(),
                        category = "runtime.notice",
                        message = signal.message,
                    ),
                )
            }

            is RuntimeSignal.SnapshotUpdated -> _uiState.update { current ->
                val snapshot = signal.summary.toSnapshotPresentation(
                    focusTargetBodyId = current.focusedBodyId,
                    activeCheckpointId = current.activeCheckpointId,
                    activeCheckpointLabel = current.activeCheckpointLabel,
                )
                val recentFocusedBodyIds = current.recentFocusedBodyIds
                    .updatedRecentFocusedBodyIds(snapshot.focusTargetBodyId)
                val next = current.copy(
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
                    recentFocusedBodyIds = recentFocusedBodyIds,
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
                val snapshotTelemetryKey = snapshot.toTelemetryKey()
                if (snapshotTelemetryKey == lastSnapshotTelemetryKey) {
                    next
                } else {
                    lastSnapshotTelemetryKey = snapshotTelemetryKey
                    next.copy(
                        developerTelemetry = recordTelemetry(
                            level = DeveloperTelemetryLevel.Info,
                            category = "snapshot.updated",
                            message = snapshot.toTelemetrySummary(),
                        ),
                    )
                }
            }

            is RuntimeSignal.CommandApplied -> _uiState.update { current ->
                val focusedBodyId = signal.command.focusTargetBodyId(current.focusedBodyId)
                val checkpointId = signal.command.activeCheckpointId(current.activeCheckpointId)
                val checkpointLabel = signal.command.activeCheckpointLabel(current.activeCheckpointLabel)
                val recentFocusedBodyIds = current.recentFocusedBodyIds
                    .updatedRecentFocusedBodyIds(focusedBodyId)
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
                    recentFocusedBodyIds = recentFocusedBodyIds,
                    activeCheckpointId = checkpointId,
                    activeCheckpointLabel = checkpointLabel,
                    renderStatus = current.renderStatus.copy(
                        readiness = if (current.renderFrame != null) {
                            current.renderStatus.readiness
                        } else {
                            RenderHostReadiness.Refreshing
                        },
                    ),
                    developerTelemetry = recordTelemetry(
                        level = DeveloperTelemetryLevel.Info,
                        category = "command.applied",
                        message = "${signal.commandLabel} -> ${snapshot.toTelemetrySummary()}",
                    ),
                )
            }

            is RuntimeSignal.RenderPacketReady -> {
                val lease = signal.lease
                val packet = lease.packet
                try {
                    val renderFrame = VulkanPacketRenderFrameDecoder.decode(lease.packet)
                    val sceneRevisionLabel = lease.sceneRevision.toSceneRevisionLabel()
                    val packetRenderIssue = packet.diagnostics.toRenderIssue()
                    val hasRenderableScene = packet.bodyCount > 0 && (
                        renderFrame.hasRenderableSceneContent() || packet.hasRenderablePayload()
                    )
                    val readyReadiness = if (hasRenderableScene) {
                        RenderHostReadiness.Ready
                    } else {
                        RenderHostReadiness.Refreshing
                    }
                    val sceneIssue = if (hasRenderableScene) {
                        null
                    } else {
                        "Waiting for non-empty render packet: bodies=${packet.bodyCount}, tracers=${packet.tracerCount}, trails=${packet.trailSpanCount}/${packet.trailVertexCount}, lights=${packet.directionalLightCount}"
                    }
                    _uiState.update { current ->
                        val next = current.copy(
                            connectionState = SessionConnectionState.Active,
                            statusLine = if (hasRenderableScene) "Render host ready" else "Render packet empty",
                            detailLine = "Scene revision $sceneRevisionLabel",
                            noticeLine = if (hasRenderableScene) {
                                "Fresh packet decoded for the Android render host"
                            } else {
                                "Render packet decoded but contained no renderable scene elements"
                            },
                            noticeTone = if (hasRenderableScene) {
                                ShellNoticeTone.Positive
                            } else {
                                ShellNoticeTone.Caution
                            },
                            pendingActionLabel = null,
                            renderPacketSummary = lease.summaryLine,
                            observerModeCode = lease.packet.observerMode,
                            cameraFacingSummary = lease.packet.camera.toFacingSummary(),
                            renderStatus = packet.toRenderStatusPresentation(
                                readiness = readyReadiness,
                                sceneRevision = sceneRevisionLabel,
                                summary = lease.summaryLine,
                                renderedBodyCount = renderFrame.bodies.size,
                                renderedTracerCount = renderFrame.tracers.size,
                                renderedTrailCount = renderFrame.trails.size,
                            ).copy(
                                isDegraded = sceneIssue != null || packetRenderIssue != null,
                                issue = sceneIssue ?: packetRenderIssue,
                                degradationReason = sceneIssue ?: packetRenderIssue,
                            ),
                            renderFrame = renderFrame,
                        )
                        val renderTelemetryKey = packet.toTelemetryKey(sceneRevisionLabel = sceneRevisionLabel)
                        lastRenderUnavailableReason = null
                        if (renderTelemetryKey == lastRenderTelemetryKey) {
                            next
                        } else {
                            lastRenderTelemetryKey = renderTelemetryKey
                            next.copy(
                                developerTelemetry = recordTelemetry(
                                    level = DeveloperTelemetryLevel.Info,
                                    category = "render.ready",
                                    message = packet.toTelemetrySummary(sceneRevision = sceneRevisionLabel),
                                ),
                            )
                        }
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
                            developerTelemetry = recordTelemetry(
                                level = DeveloperTelemetryLevel.Error,
                                category = "render.decode_failed",
                                message = error.message ?: error::class.java.simpleName,
                            ),
                        )
                    }
                } finally {
                    lease.close()
                }
            }

            is RuntimeSignal.RenderUnavailable -> _uiState.update { current ->
                val next = current.copy(
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
                if (signal.reason == lastRenderUnavailableReason) {
                    next
                } else {
                    lastRenderUnavailableReason = signal.reason
                    next.copy(
                        developerTelemetry = recordTelemetry(
                            level = DeveloperTelemetryLevel.Warning,
                            category = "render.unavailable",
                            message = signal.reason,
                        ),
                    )
                }
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
                    recentFocusedBodyIds = emptyList(),
                    activeCheckpointId = null,
                    activeCheckpointLabel = null,
                    renderStatus = current.renderStatus.copy(
                        readiness = RenderHostReadiness.Unavailable,
                        isDegraded = true,
                        degradationReason = signal.detail ?: signal.message,
                        issue = signal.detail ?: signal.message,
                    ),
                    renderFrame = null,
                    developerTelemetry = recordTelemetry(
                        level = DeveloperTelemetryLevel.Error,
                        category = "session.unavailable",
                        message = signal.detail ?: signal.message,
                    ),
                )
            }
        }
    }

    private suspend fun runShellAction(
        label: String,
        telemetry: TelemetryEvent? = null,
        onStart: (ShellUiState) -> ShellUiState,
        action: suspend () -> Unit,
    ) {
        _uiState.update { current ->
            val started = onStart(current)
            telemetry?.let {
                started.copy(
                    developerTelemetry = recordTelemetry(
                        level = it.level,
                        category = it.category,
                        message = it.message,
                    ),
                )
            } ?: started
        }
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
                focusedBodyId = null,
                recentFocusedBodyIds = emptyList(),
                renderStatus = current.renderStatus.copy(
                    readiness = RenderHostReadiness.Unavailable,
                    isDegraded = true,
                    degradationReason = detailLine,
                    issue = detailLine,
                ),
                renderFrame = null,
                developerTelemetry = recordTelemetry(
                    level = DeveloperTelemetryLevel.Error,
                    category = "shell.failure",
                    message = "$statusLine: $detailLine",
                ),
            )
        }
    }

    private fun recordTelemetry(
        level: DeveloperTelemetryLevel,
        category: String,
        message: String,
    ): DeveloperTelemetryPresentation {
        return developerTelemetryRecorder.record(
            level = level,
            category = category,
            message = message,
        )
    }

    private data class TelemetryEvent(
        val level: DeveloperTelemetryLevel,
        val category: String,
        val message: String,
    )
}

private const val RECENT_FOCUSED_BODY_HISTORY_LIMIT = 5

private fun List<String>.updatedRecentFocusedBodyIds(
    focusedBodyId: String?,
    limit: Int = RECENT_FOCUSED_BODY_HISTORY_LIMIT,
): List<String> {
    val normalized = focusedBodyId?.trim().orEmpty()
    if (normalized.isEmpty()) {
        return this
    }
    return buildList {
        add(normalized)
        addAll(this@updatedRecentFocusedBodyIds.filterNot { it == normalized })
    }.take(limit)
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

private fun RenderFrame.hasRenderableSceneContent(): Boolean =
    bodies.isNotEmpty() || tracers.isNotEmpty() || trails.isNotEmpty()

private fun NativeVulkanScenePacket.hasRenderablePayload(): Boolean =
    bodyCount > 0 || tracerCount > 0 || trailSpanCount > 0 || trailVertexCount > 0 || directionalLightCount > 0

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
    RuntimeCommand.SeedCanonicalSolarSystem -> "Seed canonical solar system"
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

private fun RuntimeNoticeLevel.toDeveloperTelemetryLevel(): DeveloperTelemetryLevel = when (this) {
    RuntimeNoticeLevel.Info,
    RuntimeNoticeLevel.Success,
    -> DeveloperTelemetryLevel.Info
    RuntimeNoticeLevel.Warning -> DeveloperTelemetryLevel.Warning
    RuntimeNoticeLevel.Error -> DeveloperTelemetryLevel.Error
}

private fun backendSummaryLabel(
    cpuBackendLabel: String,
    gpuBackendLabel: String,
    workloadSummary: String?,
    interopErrorBudgetSummary: String?,
): String {
    val segments = mutableListOf("$cpuBackendLabel + $gpuBackendLabel")
    workloadSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "workloads: $it"
    }
    interopErrorBudgetSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "policy: $it"
    }
    return segments.joinToString(" | ")
}

private fun runtimeInfoTelemetryMessage(signal: RuntimeSignal.RuntimeInfoAvailable): String {
    val segments = mutableListOf(
        "cpu=${signal.cpuBackendLabel}",
        "gpu=${signal.gpuBackendLabel}",
    )
    signal.workloadSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "workloads=$it"
    }
    signal.interopErrorBudgetSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "policy=$it"
    }
    return segments.joinToString(", ")
}

private fun SnapshotPresentation.toTelemetryKey(): String {
    return listOf(
        scenarioId,
        activeBranchId,
        bodyCount.toString(),
        paused.toString(),
        focusTargetBodyId ?: "-",
        activeCheckpointId ?: "-",
        observerModeLabel,
    ).joinToString("|")
}

private fun SnapshotPresentation.toTelemetrySummary(): String {
    return "scenario=$scenarioId, branch=$activeBranchId, bodies=$bodyCount, paused=$paused, mode=$observerModeLabel"
}

private fun NativeVulkanScenePacket.toTelemetryKey(sceneRevisionLabel: String): String {
    return listOf(
        sceneRevisionLabel,
        bodyCount.toString(),
        tracerCount.toString(),
        trailSpanCount.toString(),
        trailVertexCount.toString(),
        directionalLightCount.toString(),
        diagnostics.droppedFrames.toString(),
    ).joinToString("|")
}

private fun NativeVulkanScenePacket.toTelemetrySummary(sceneRevision: String): String {
    return "revision=$sceneRevision, bodies=$bodyCount, tracers=$tracerCount, trails=$trailSpanCount/$trailVertexCount, lights=$directionalLightCount"
}

private fun String.toSceneRevisionLabel(maxChars: Int = 96): String {
    if (length <= maxChars) {
        return this
    }

    val suffix = "... (${length} chars)"
    val prefixLength = (maxChars - suffix.length).coerceAtLeast(0)
    return take(prefixLength) + suffix
}

private fun NativeVulkanCameraPacket.toFacingSummary(): String {
    return "target=($targetFromOriginX, $targetFromOriginY, $targetFromOriginZ), up=($upX, $upY, $upZ)"
}

private fun Double.asEpochLabel(): String = String.format(Locale.US, "%,.1f s", this)

private fun Double.asRateLabel(): String = String.format(Locale.US, "%,.2fx", this)
