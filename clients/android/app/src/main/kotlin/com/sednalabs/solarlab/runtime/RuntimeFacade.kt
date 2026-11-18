package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream

/**
 * Public seam for shell/runtime orchestration.
 */
interface RuntimeFacade {
    // State stream is immutable from the UI perspective and sourced from runtime signals.
    val uiState: StateFlow<ShellUiState>

    val scenarioPacks: List<RuntimeScenarioPack>

    val savedExperiments: StateFlow<List<SavedExperimentInfo>> get() = emptySavedExperiments
    val experimentStatus: StateFlow<ExperimentStatus> get() = emptyExperimentStatus
    /** Successful placement receipt owned by the retained runtime session, not an Activity. */
    val lastProbeCommit: StateFlow<ProbeCommitResult?> get() = emptyProbeCommit
    suspend fun saveExperiment(name: String) {}
    suspend fun loadExperiment(id: String) {}
    suspend fun importExperiment(openInput: () -> InputStream?) {}
    suspend fun exportExperiment(id: String, openOutput: () -> OutputStream?) {}
    suspend fun deleteExperiment(id: String) {}
    suspend fun refreshExperimentCatalogue() {}
    suspend fun inspectBody(bodyId: String): RuntimeBodyState? = null
    suspend fun startFlight(shipId: String = REFERENCE_SPACECRAFT_ID) { error("Spaceflight is unavailable") }
    /** Saves the current world before atomically opening a new, paused lunar starting world. */
    suspend fun startLunarMission(shipId: String = REFERENCE_SPACECRAFT_ID) { error("Lunar flight is unavailable") }
    suspend fun beginLunarDeparture(shipId: String = REFERENCE_SPACECRAFT_ID,
        expectedSessionHandle: Long? = null) { error("Guided departure is unavailable") }
    /** Durably saves the current world before opening the station encounter. */
    suspend fun startRendezvousMission(shipId: String = REFERENCE_SPACECRAFT_ID) { error("Station rendezvous is unavailable") }
    suspend fun setRendezvousGuidance(guidance: RendezvousGuidance, shipId: String = REFERENCE_SPACECRAFT_ID,
        expectedSessionHandle: Long? = null) { error("Rendezvous guidance is unavailable") }
    /** Saves the current experiment before opening the native close-range docking mission. */
    suspend fun startDockingMission(shipId: String = REFERENCE_SPACECRAFT_ID) { error("Station docking is unavailable") }
    suspend fun setDockingGuidance(mode: DockingGuidance, shipId: String = REFERENCE_SPACECRAFT_ID,
        expectedSessionHandle: Long? = null) { error("Docking guidance is unavailable") }
    suspend fun undock(shipId: String = REFERENCE_SPACECRAFT_ID,
        expectedSessionHandle: Long? = null) { error("Docking release is unavailable") }
    suspend fun dockingState(shipId: String = REFERENCE_SPACECRAFT_ID): DockingState? = null
    suspend fun setFlightControl(control: FlightControl, shipId: String = REFERENCE_SPACECRAFT_ID,
        expectedSessionHandle: Long? = null) { error("Spaceflight is unavailable") }
    suspend fun flightState(shipId: String = REFERENCE_SPACECRAFT_ID): FlightState? = null
    suspend fun lunarMissionState(shipId: String = REFERENCE_SPACECRAFT_ID): LunarMissionState? = null
    suspend fun rendezvousState(shipId: String = REFERENCE_SPACECRAFT_ID): RendezvousState? = null
    /** Retained operation: cuts input and pauses even when the composing Activity is destroyed. */
    fun suspendFlightControls(shipId: String = REFERENCE_SPACECRAFT_ID, expectedSessionHandle: Long? = null) {}


    /** Draft work is bounded and read-only; failures are thrown to the placement UI. */
    suspend fun previewProbe(candidate: RuntimeBodyState, referenceBodyId: String, horizonSeconds: Double = 43_200.0): ProbePreviewResult =
        error("Probe preview is unavailable")
    suspend fun commitProbe(preview: ProbePreviewResult): ProbeCommitResult = error("Probe placement is unavailable")
    suspend fun undoProbe(commit: ProbeCommitResult) { error("Probe undo is unavailable") }

    // One-time bind/handshake into native runtime.
    suspend fun startSession()

    // Replace the active runtime session with a deterministic built-in scenario pack.
    suspend fun loadScenario(scenarioId: String)

    // Pull latest runtime snapshot/render packet on demand.
    suspend fun refresh()

    // Apply shell-level command through the runtime boundary.
    suspend fun applyCommand(command: RuntimeCommand)

    fun cancelManualAdvance() {}
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
    val focusTargetBodyId: String? = null,
    val activeCheckpointId: String? = null,
    val activeCheckpointLabel: String? = null,
    val timelineSemantics: Int = 0,
    val timelineSemanticsLabel: String = "Unknown timeline semantics",
    val observerModeLabel: String,
    val revision: Long = 0,
)

data class RenderStatusPresentation(
    val readiness: RenderHostReadiness = RenderHostReadiness.WaitingForSession,
    val sceneRevision: String? = null,
    val summary: String? = null,
    val renderedBodyCount: Int = 0,
    val renderedTracerCount: Int = 0,
    val renderedTrailCount: Int = 0,
    val directionalLightCount: Int = 0,
    val diagnosticsFrameNumber: Long? = null,
    val diagnosticsCpuExtractMs: Float? = null,
    val diagnosticsGpuUploadMs: Float? = null,
    val diagnosticsDroppedFrames: Int = 0,
    val provenanceSource: String? = null,
    val provenanceVersion: String? = null,
    val provenanceManifestId: String? = null,
    val provenanceManifestDigest: String? = null,
    val provenancePackageDigest: String? = null,
    val isDegraded: Boolean = false,
    val degradationReason: String? = null,
    val issue: String? = null,
)

data class ManualAdvancePresentation(
    val completedSteps: Long = 0,
    val totalSteps: Long = 0,
    val cancelRequested: Boolean = false,
    val status: String = "Preparing",
)

enum class ManualAdvanceOutcome {
    Complete,
    Cancelled,
}

data class PlaybackMeasurement(
    val revision: Long,
    val requestedSeconds: Double,
    val appliedSeconds: Double,
    val limited: Boolean,
    val achievedRate: Double?,
)

data class ShellUiState(
    val connectionState: SessionConnectionState = SessionConnectionState.Connecting,
    val statusLine: String,
    val detailLine: String? = null,
    val noticeLine: String? = null,
    val noticeTone: ShellNoticeTone = ShellNoticeTone.Neutral,
    val pendingActionLabel: String? = null,
    val manualAdvance: ManualAdvancePresentation? = null,
    val manualAdvanceOutcome: ManualAdvanceOutcome? = null,
    val playbackMeasurement: PlaybackMeasurement? = null,
    val sessionHandle: Long? = null,
    val backendSummary: String? = null,
    val snapshot: SnapshotPresentation? = null,
    val renderStatus: RenderStatusPresentation = RenderStatusPresentation(),
    val renderPacketSummary: String? = null,
    val snapshotSummary: String? = null,
    val observerModeCode: Int? = null,
    val cameraFacingSummary: String? = null,
    val focusedBodyId: String? = null,
    val recentFocusedBodyIds: List<String> = emptyList(),
    val activeCheckpointId: String? = null,
    val activeCheckpointLabel: String? = null,
    val renderFrame: RenderFrame? = null,
    val developerTelemetry: DeveloperTelemetryPresentation = DeveloperTelemetryPresentation(),
)

private val emptySavedExperiments: StateFlow<List<SavedExperimentInfo>> = MutableStateFlow<List<SavedExperimentInfo>>(emptyList()).asStateFlow()
private val emptyExperimentStatus: StateFlow<ExperimentStatus> = MutableStateFlow(ExperimentStatus()).asStateFlow()
private val emptyProbeCommit: StateFlow<ProbeCommitResult?> = MutableStateFlow<ProbeCommitResult?>(null).asStateFlow()
