package com.sednalabs.solarlab.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
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
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val operationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val experimentStore: ExperimentStore? = null,
) : RuntimeFacade {
    constructor() : this(JniRuntimeBridge())
    constructor(operationScope: CoroutineScope) : this(JniRuntimeBridge(), operationScope = operationScope)
    constructor(operationScope: CoroutineScope, experimentDirectory: File) : this(
        JniRuntimeBridge(), operationScope = operationScope, experimentStore = FileExperimentStore(experimentDirectory),
    )
    constructor(operationScope: CoroutineScope, experimentDirectory: File, context: Context) : this(
        JniRuntimeBridge(performanceHints = androidRuntimePerformanceHints(context)),
        operationScope = operationScope, experimentStore = FileExperimentStore(experimentDirectory),
    )

    private val flightInputGeneration = AtomicLong(0)

    private val manualLock = Any()
    private var manualJob: Job? = null
    private val manualCancelRequested = AtomicBoolean(false)
    private data class AdvanceIdentity(val intent: UUID, val baseOrdinal: Long, val delta: Double, val recordedAt: Long, val priorGeneration: Long)
    private var unresolvedAdvance: AdvanceIdentity? = null

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

    private val experimentMutex = Mutex()
    private val _savedExperiments = MutableStateFlow<List<SavedExperimentInfo>>(emptyList())
    override val savedExperiments: StateFlow<List<SavedExperimentInfo>> = _savedExperiments.asStateFlow()
    private val _experimentStatus = MutableStateFlow(ExperimentStatus())
    override val experimentStatus: StateFlow<ExperimentStatus> = _experimentStatus.asStateFlow()
    private val _lastProbeCommit = MutableStateFlow<ProbeCommitResult?>(null)
    override val lastProbeCommit: StateFlow<ProbeCommitResult?> = _lastProbeCommit.asStateFlow()

    override val scenarioPacks: List<RuntimeScenarioPack> = RuntimeScenarioPacks.all

    /**
     * Initializes the connection to the Rust-owned runtime boundary.
     * 
     * Session handoff is one-way from the bridge to the UI state. The bridge's 
     * connection flow is the primary driver for the initial shell lifecycle.
     */
    override suspend fun startSession() {
        refreshSavedExperiments()
        updateWithTelemetry(
            recordTelemetry(
                level = DeveloperTelemetryLevel.Info,
                category = "session.start",
                message = "Opening Rust runtime session from Android shell",
            ),
        ) { current ->
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
            )
        }
        try {
            withContext(boundaryDispatcher) {
                bridge.connect().collect(::applySignal)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            surfaceShellFailure(
                statusLine = "Runtime startup failed",
                detailLine = error.message ?: error::class.java.simpleName,
                noticeLine = "Android shell caught an unhandled startup failure instead of crashing",
            )
        }
    }

    private suspend fun refreshSavedExperiments(): Boolean =
        try {
            _savedExperiments.value = withContext(Dispatchers.IO) { experimentStore?.list().orEmpty() }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _uiState.update { it.copy(noticeLine = "Saved experiments are unavailable: ${error.message}", noticeTone = ShellNoticeTone.Caution) }
            _experimentStatus.update { it.copy(notice = "Saved experiments are unavailable: ${error.message}", tone = ShellNoticeTone.Caution) }
            false
        }

    override suspend fun refreshExperimentCatalogue() = experimentMutex.withLock {
        if (refreshSavedExperiments()) {
            _experimentStatus.update {
                if (it.notice?.startsWith("Saved experiments are unavailable:") == true ||
                    it.notice?.contains("the saved list is currently unavailable") == true) {
                    it.copy(notice = null, tone = ShellNoticeTone.Neutral)
                } else it
            }
        }
    }

    override suspend fun inspectBody(bodyId: String): RuntimeBodyState? = try {
        withContext(boundaryDispatcher) { bridge.inspectBody(bodyId) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        _uiState.update { it.copy(noticeLine = "Could not inspect body: ${error.message}", noticeTone = ShellNoticeTone.Caution) }
        null
    }

    override suspend fun startFlight(shipId: String) = withContext(NonCancellable + boundaryDispatcher) {
        check(_uiState.value.manualAdvance == null) { "Finish or cancel time advance before entering flight" }
        bridge.startFlight(shipId).forEach(::applySignal)
    }

    override suspend fun startLunarMission(shipId: String) = experimentMutex.withLock {
        val store = checkNotNull(experimentStore) { "Save storage is unavailable; your current world has not been replaced" }
        _experimentStatus.value = ExperimentStatus(busyLabel = "Saving this experiment and preparing lunar flight")
        _uiState.update { it.copy(pendingActionLabel = "Preparing lunar flight") }
        try {
            withContext(NonCancellable) {
                check(_uiState.value.manualAdvance == null) { "Finish or cancel the current advance before starting a mission" }
                flightInputGeneration.incrementAndGet()
                val previous = withContext(boundaryDispatcher) {
                    bridge.applyCommand(RuntimeCommand.PausePlayback).forEach(::applySignal)
                    bridge.exportSavedSession()
                }
                val saved = withContext(Dispatchers.IO) {
                    store.save("Before lunar flight ${java.text.SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(java.util.Date())}", previous)
                }
                refreshSavedExperiments()
                // Native construction is isolated until seeding, mission authoring and validation succeed.
                withContext(boundaryDispatcher) { bridge.startLunarFlight(shipId).forEach(::applySignal) }
                _lastProbeCommit.value = null
                _experimentStatus.value = ExperimentStatus(notice = "Lunar flight ready. Previous world saved as ${saved.name}", tone = ShellNoticeTone.Positive)
                _uiState.update { it.copy(pendingActionLabel = null, playbackMeasurement = null,
                    manualAdvanceOutcome = null, noticeLine = "Lunar flight ready. Your previous experiment has been saved.",
                    noticeTone = ShellNoticeTone.Positive) }
            }
        } catch (error: Throwable) {
            _experimentStatus.value = ExperimentStatus(notice = "Lunar flight could not start: ${error.message}", tone = ShellNoticeTone.Caution)
            _uiState.update { it.copy(pendingActionLabel = null, noticeLine = "Lunar flight could not start: ${error.message}", noticeTone = ShellNoticeTone.Caution) }
            throw error
        }
    }

    override suspend fun beginLunarDeparture(shipId: String) {
        flightInputGeneration.incrementAndGet()
        withContext(NonCancellable + boundaryDispatcher) {
            check(_uiState.value.manualAdvance == null) { "Finish or cancel the current advance first" }
            bridge.beginLunarDeparture(shipId).forEach(::applySignal)
            bridge.applyCommand(RuntimeCommand.ResumePlayback).forEach(::applySignal)
        }
    }

    override suspend fun setFlightControl(control: FlightControl, shipId: String) {
        val generation = flightInputGeneration.get()
        withContext(boundaryDispatcher) {
            if (generation != flightInputGeneration.get()) return@withContext
            check(_uiState.value.manualAdvance == null) { "Finish or cancel time advance before controlling flight" }
            if (control.requiresRealTime && (_uiState.value.snapshot?.simSecondsPerRealSecond ?: 1.0) > 1.0) {
                bridge.applyCommand(RuntimeCommand.SetPlaybackRate(1.0)).forEach(::applySignal)
            }
            bridge.setFlightControl(shipId, control)
        }
    }

    override suspend fun flightState(shipId: String): FlightState? =
        withContext(boundaryDispatcher) { bridge.flightState(shipId) }

    override suspend fun lunarMissionState(shipId: String): LunarMissionState? =
        withContext(boundaryDispatcher) { bridge.lunarMissionState(shipId) }

    override fun suspendFlightControls(shipId: String) {
        // Invalidate inputs queued by the old Activity before scheduling the retained stop.
        flightInputGeneration.incrementAndGet()
        val session = _uiState.value.sessionHandle
        operationScope.launch(NonCancellable + boundaryDispatcher) {
            if (session == null || session != _uiState.value.sessionHandle) return@launch
            try {
                bridge.setFlightControl(shipId, FlightControl.Cut)
            } catch (error: Throwable) {
                _uiState.update { it.copy(noticeLine = "Could not cut flight input: ${error.message}", noticeTone = ShellNoticeTone.Caution) }
            } finally {
                // A missing or removed ship must never prevent stopping simulation time.
                try { bridge.applyCommand(RuntimeCommand.PausePlayback).forEach(::applySignal) }
                catch (error: Throwable) {
                    _uiState.update { it.copy(noticeLine = "Could not pause flight: ${error.message}", noticeTone = ShellNoticeTone.Caution) }
                }
            }
        }
    }

    override suspend fun previewProbe(candidate: RuntimeBodyState, referenceBodyId: String, horizonSeconds: Double): ProbePreviewResult =
        withContext(boundaryDispatcher) {
            check(_uiState.value.manualAdvance == null) { "Finish or cancel the current time advance first" }
            bridge.previewProbe(candidate, referenceBodyId, horizonSeconds)
        }

    override suspend fun commitProbe(preview: ProbePreviewResult): ProbeCommitResult =
        withContext(NonCancellable + boundaryDispatcher) {
            check(_uiState.value.manualAdvance == null) { "Finish or cancel the current time advance first" }
            val (receipt, signals) = bridge.commitProbe(preview)
            // Returning to a disposed Activity may be cancelled after native success.
            // Retain the receipt before that boundary, even if UI projection later fails.
            _lastProbeCommit.value = receipt
            signals.forEach(::applySignal)
            _uiState.update { it.copy(noticeLine = "Placed ${receipt.bodyId}", noticeTone = ShellNoticeTone.Positive) }
            receipt
        }

    override suspend fun undoProbe(commit: ProbeCommitResult) = withContext(NonCancellable + boundaryDispatcher) {
        check(_uiState.value.manualAdvance == null) { "Finish or cancel the current time advance first" }
        val signals = bridge.undoProbe(commit)
        _lastProbeCommit.value = null
        signals.forEach(::applySignal)
        _uiState.update { it.copy(noticeLine = "Returned to before placing ${commit.bodyId}", noticeTone = ShellNoticeTone.Positive) }
    }

    override suspend fun saveExperiment(name: String) = experimentMutex.withLock {
        _experimentStatus.value = ExperimentStatus(busyLabel = "Saving experiment")
        _uiState.update { it.copy(pendingActionLabel = "Saving experiment") }
        try {
            val store = checkNotNull(experimentStore) { "Experiment storage is unavailable" }
            check(_uiState.value.manualAdvance == null) { "Finish or cancel the current advance before saving" }
            val session = withContext(boundaryDispatcher) { bridge.exportSavedSession() }
            // Once file I/O starts, observe the atomic commit even if the caller's UI is
            // destroyed. Never report cancellation for a save that already committed.
            val saved = withContext(NonCancellable + Dispatchers.IO) { store.save(name, session) }
            withContext(NonCancellable) {
                val refreshed = refreshSavedExperiments()
                _experimentStatus.value = ExperimentStatus(
                    notice = if (refreshed) "Saved ${saved.name}" else "Saved ${saved.name}; the saved list is currently unavailable.",
                    tone = if (refreshed) ShellNoticeTone.Positive else ShellNoticeTone.Caution)
                _uiState.update { it.copy(pendingActionLabel = null,
                    noticeLine = if (refreshed) "Saved ${saved.name}" else "Saved ${saved.name}; the saved list is currently unavailable.",
                    noticeTone = if (refreshed) ShellNoticeTone.Positive else ShellNoticeTone.Caution) }
            }
        } catch (error: CancellationException) {
            _experimentStatus.update { it.copy(busyLabel = null) }
            _uiState.update { it.copy(pendingActionLabel = null) }
            throw error
        } catch (error: Throwable) {
            _experimentStatus.value = ExperimentStatus(notice = "Could not save experiment: ${error.message}", tone = ShellNoticeTone.Caution)
            _uiState.update { it.copy(pendingActionLabel = null, noticeLine = "Could not save experiment: ${error.message}", noticeTone = ShellNoticeTone.Caution) }
        }
    }

    override suspend fun loadExperiment(id: String) = experimentMutex.withLock {
        _experimentStatus.value = ExperimentStatus(busyLabel = "Opening experiment")
        _uiState.update { it.copy(pendingActionLabel = "Opening experiment") }
        try {
            val store = checkNotNull(experimentStore) { "Experiment storage is unavailable" }
            val saved = withContext(Dispatchers.IO) { store.read(id) }
            cancelManualAdvance()
            synchronized(manualLock) { manualJob }?.join()
            check(_uiState.value.manualAdvance == null) { "Cancel the current advance before opening an experiment" }
            // Import builds a new native session before replacing the live one. Ensure
            // its resulting signals are applied even if the originating sheet closes.
            withContext(NonCancellable + boundaryDispatcher) {
                bridge.importSavedSession(saved.bytes).forEach(::applySignal)
                _experimentStatus.value = ExperimentStatus(notice = "Opened ${saved.info.name} · paused",
                    tone = ShellNoticeTone.Positive, openedId = id)
                _uiState.update { it.copy(
                    pendingActionLabel = null, playbackMeasurement = null,
                    manualAdvanceOutcome = null, recentFocusedBodyIds = listOfNotNull(it.focusedBodyId),
                    noticeLine = "Opened ${saved.info.name} · paused", noticeTone = ShellNoticeTone.Positive,
                ) }
            }
        } catch (error: CancellationException) {
            _experimentStatus.update { it.copy(busyLabel = null) }
            _uiState.update { it.copy(pendingActionLabel = null) }
            throw error
        } catch (error: Throwable) {
            _experimentStatus.value = ExperimentStatus(notice = "Could not open experiment: ${error.message}", tone = ShellNoticeTone.Caution)
            _uiState.update { it.copy(pendingActionLabel = null, noticeLine = "Could not open experiment: ${error.message}", noticeTone = ShellNoticeTone.Caution) }
        }
    }

    override suspend fun importExperiment(openInput: () -> InputStream?) = retainedExperimentOperation(
        "Importing experiment", "Could not import experiment",
    ) { store ->
        val document = withContext(Dispatchers.IO) {
            checkNotNull(openInput()) { "The selected document is unavailable" }.use(ExperimentDocumentCodec::read)
        }
        val nativeScenario = withContext(boundaryDispatcher) { bridge.validateSavedSession(document.bytes) }
        check(nativeScenario == document.info.scenarioId) { "The document and its simulation have different identities" }
        val saved = withContext(NonCancellable + Dispatchers.IO) {
            // Import is a new local version. An external UUID never overwrites a saved copy.
            store.save(document.info.name, SavedRuntimeSession(nativeScenario, document.bytes))
        }
        "Imported ${saved.name} · open it when you are ready"
    }

    override suspend fun exportExperiment(id: String, openOutput: () -> OutputStream?) = retainedExperimentOperation(
        "Exporting experiment", "Export did not finish; the destination may be incomplete",
    ) { store ->
        val saved = withContext(Dispatchers.IO) { store.read(id) }
        withContext(NonCancellable + Dispatchers.IO) {
            // Success is reported only after the provider's stream has closed successfully.
            // A document provider does not promise atomic replacement or cloud synchronisation.
            checkNotNull(openOutput()) { "The selected destination is unavailable" }.use { output ->
                ExperimentDocumentCodec.write(output, saved)
            }
        }
        "Exported ${saved.info.name}"
    }

    override suspend fun deleteExperiment(id: String) = retainedExperimentOperation(
        "Removing saved copy", "Could not remove saved copy",
    ) { store ->
        withContext(NonCancellable + Dispatchers.IO) { store.delete(id) }
        "Removed the saved copy · the live experiment is unchanged"
    }

    private suspend fun retainedExperimentOperation(
        label: String,
        failure: String,
        action: suspend (ExperimentStore) -> String,
    ) {
        // Ownership belongs to the retained runtime, not the Activity waiting for a picker
        // result. Rotation can cancel the caller's await without cancelling an export.
        operationScope.async {
            experimentMutex.withLock {
                _experimentStatus.value = ExperimentStatus(busyLabel = label)
                _uiState.update { it.copy(pendingActionLabel = label) }
                try {
                    val message = action(checkNotNull(experimentStore) { "Experiment storage is unavailable" })
                    val refreshed = refreshSavedExperiments()
                    _experimentStatus.value = ExperimentStatus(
                        notice = if (refreshed) message else "${message.substringBefore(" ·")}; the saved list is currently unavailable.",
                        tone = if (refreshed) ShellNoticeTone.Positive else ShellNoticeTone.Caution)
                    _uiState.update { it.copy(
                        noticeLine = if (refreshed) message else "${message.substringBefore(" ·")}; the saved list is currently unavailable.",
                        noticeTone = if (refreshed) ShellNoticeTone.Positive else ShellNoticeTone.Caution) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    _experimentStatus.value = ExperimentStatus(notice = "$failure: ${error.message}", tone = ShellNoticeTone.Caution)
                    _uiState.update { it.copy(noticeLine = "$failure: ${error.message}", noticeTone = ShellNoticeTone.Caution) }
                } finally {
                    _experimentStatus.update { it.copy(busyLabel = null) }
                    _uiState.update { it.copy(pendingActionLabel = null) }
                }
            }
        }.await()
    }

    override suspend fun loadScenario(scenarioId: String) {
        cancelManualAdvance()
        synchronized(manualLock) { manualJob }?.join()
        if (_uiState.value.manualAdvance != null) return
        _uiState.update { it.copy(playbackMeasurement = null, manualAdvanceOutcome = null) }
        val scenarioPack = RuntimeScenarioPacks.byId(scenarioId)
        if (scenarioPack == null) {
            updateWithTelemetry(
                recordTelemetry(
                    level = DeveloperTelemetryLevel.Warning,
                    category = "scenario.load.rejected",
                    message = "Unknown scenario $scenarioId",
                ),
            ) { current ->
                current.copy(
                    noticeLine = "Unknown scenario pack: $scenarioId",
                    noticeTone = ShellNoticeTone.Caution,
                    pendingActionLabel = null,
                )
            }
            return
        }
        try {
            runShellAction(
                label = "Loading ${scenarioPack.title}",
                telemetry = TelemetryEvent(
                    level = DeveloperTelemetryLevel.Info,
                    category = "scenario.load.requested",
                    message = "Requested scenario ${scenarioPack.scenarioId}",
                ),
                onStart = { current ->
                    current.copy(
                        connectionState = SessionConnectionState.Connecting,
                        statusLine = "Loading ${scenarioPack.title}",
                        detailLine = scenarioPack.description,
                        noticeLine = "Replacing the runtime session with scenario ${scenarioPack.scenarioId}",
                        noticeTone = ShellNoticeTone.Neutral,
                        pendingActionLabel = "Loading ${scenarioPack.title}",
                        sessionHandle = null,
                        snapshot = null,
                        snapshotSummary = null,
                        observerModeCode = null,
                        cameraFacingSummary = null,
                        focusedBodyId = null,
                        recentFocusedBodyIds = emptyList(),
                        activeCheckpointId = null,
                        activeCheckpointLabel = null,
                        renderStatus = RenderStatusPresentation(
                            readiness = RenderHostReadiness.WaitingForSession,
                        ),
                        renderPacketSummary = null,
                        renderFrame = null,
                    )
                }
            ) {
                withContext(boundaryDispatcher) {
                    bridge.loadScenario(scenarioPack.scenarioId).forEach(::applySignal)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            surfaceShellFailure(
                statusLine = "Scenario load failed",
                detailLine = error.message ?: error::class.java.simpleName,
                noticeLine = "Android shell caught an unhandled scenario load failure instead of crashing",
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
                    bridge.refresh().forEach(::applySignal)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            surfaceShellFailure(
                statusLine = "Runtime refresh failed",
                detailLine = error.message ?: error::class.java.simpleName,
                noticeLine = "Android shell caught an unhandled refresh failure instead of crashing",
            )
        }
    }

    override suspend fun applyCommand(command: RuntimeCommand) {
        if (command is RuntimeCommand.AdvanceEpoch) {
            startManualAdvance(command.deltaSeconds)
            return
        }
        if (_uiState.value.manualAdvance != null) return
        _uiState.update { it.copy(playbackMeasurement = null, manualAdvanceOutcome = null) }
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            surfaceShellFailure(
                statusLine = "Runtime command failed",
                detailLine = error.message ?: error::class.java.simpleName,
                noticeLine = "Android shell caught an unhandled command failure instead of crashing",
            )
        }
    }

    override fun cancelManualAdvance() {
        manualCancelRequested.set(true)
        _uiState.update { current ->
            current.copy(manualAdvance = current.manualAdvance?.copy(cancelRequested = true, status = "Cancelling"))
        }
        synchronized(manualLock) {
            val identity = unresolvedAdvance
            if (manualJob == null && identity != null) {
                val job = operationScope.launch(start = CoroutineStart.LAZY) {
                    try { reconcileUnknownAdvance(identity) }
                    finally { synchronized(manualLock) { manualJob = null } }
                }
                manualJob = job
                job.start()
            }
        }
    }

    private fun startManualAdvance(deltaSeconds: Double) {
        synchronized(manualLock) {
            val state = _uiState.value
            if (manualJob != null || state.manualAdvance != null || state.sessionHandle == null ||
                state.snapshot?.paused != true
            ) return
            manualCancelRequested.set(false)
            _uiState.update { it.copy(manualAdvance = ManualAdvancePresentation(), manualAdvanceOutcome = null, playbackMeasurement = null) }
            val intent = UUID.randomUUID()
            val recordedAtUnixMs = System.currentTimeMillis()
            val job = operationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    executeManualAdvance(intent, deltaSeconds, recordedAtUnixMs)
                } finally {
                    synchronized(manualLock) { manualJob = null }
                }
            }
            manualJob = job
            job.start()
        }
    }

    private suspend fun executeManualAdvance(intent: UUID, deltaSeconds: Double, recordedAtUnixMs: Long) {
        var baseOrdinal = 0L
        var operation: NativeAdvanceResult? = null
        var expectedGeneration: Long? = null
        var beginAttempted = false
        var terminal = false
        var outcome: ManualAdvanceOutcome? = null
        fun isOurs(result: NativeAdvanceResult): Boolean =
            result.intentHigh == intent.mostSignificantBits && result.intentLow == intent.leastSignificantBits &&
                result.baseOrdinal == baseOrdinal && result.requestedSeconds.toBits() == deltaSeconds.toBits() &&
                result.recordedAtUnixMs == recordedAtUnixMs &&
                expectedGeneration?.let { result.generation == it + 1 } == true
        suspend fun query(): NativeAdvanceResult = withContext(boundaryDispatcher) { bridge.queryAdvance() }
        try {
            val previous = query()
            check(previous.code == 0 && previous.state != 1 && previous.currentNextOrdinal > 0) { "Another advance is active or cannot be queried" }
            baseOrdinal = previous.currentNextOrdinal
            expectedGeneration = previous.generation
            synchronized(manualLock) {
                unresolvedAdvance = AdvanceIdentity(intent, baseOrdinal, deltaSeconds, recordedAtUnixMs, previous.generation)
            }
            if (manualCancelRequested.get()) {
                terminal = true
                return
            }
            beginAttempted = true
            operation = try {
                withContext(boundaryDispatcher) {
                    bridge.beginAdvance(intent.mostSignificantBits, intent.leastSignificantBits, previous.generation, baseOrdinal, deltaSeconds, recordedAtUnixMs)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val recovered = query()
                if (recovered.code != 0 || !isOurs(recovered)) throw error
                recovered
            }
            if (operation!!.code != 0) {
                terminal = true
                if (operation!!.code == 5) {
                    withContext(boundaryDispatcher) { bridge.refresh().forEach(::applySignal) }
                }
                error("Advance could not start (status ${operation!!.code})")
            }
            check(isOurs(operation!!)) { "Advance identity could not be reconciled" }
            while (operation!!.state == 1) {
                val current = operation!!
                _uiState.update { state -> state.copy(manualAdvance = ManualAdvancePresentation(
                    completedSteps = current.completedSteps,
                    totalSteps = current.totalSteps,
                    cancelRequested = manualCancelRequested.get(),
                    status = if (manualCancelRequested.get()) "Cancelling" else "Advancing",
                )) }
                yield()
                operation = if (manualCancelRequested.get()) {
                    try {
                        withContext(boundaryDispatcher) { bridge.cancelAdvance(current.generation) }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        query()
                    }
                } else {
                    try {
                        withContext(boundaryDispatcher) { bridge.continueAdvance(current.generation, current.completedSteps) }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        val recovered = query()
                        if (recovered.code != 0 || !isOurs(recovered) ||
                            (recovered.state == 1 && recovered.completedSteps == current.completedSteps)
                        ) throw error
                        recovered
                    }
                }
                check(operation!!.code == 0 && isOurs(operation!!)) { "Advance status could not be reconciled" }
            }
            terminal = operation!!.state == 2 || operation!!.state == 3
            check(terminal) { "Advance status is unknown" }
            outcome = if (operation!!.state == 2) ManualAdvanceOutcome.Complete else ManualAdvanceOutcome.Cancelled
            withContext(boundaryDispatcher) { bridge.refresh().forEach(::applySignal) }
            val message = if (operation!!.state == 2) "Advance complete" else "Advance cancelled"
            _uiState.update { it.copy(noticeLine = message, noticeTone = ShellNoticeTone.Neutral) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(noticeLine = "Advance unavailable: ${error.message ?: error::class.java.simpleName}", noticeTone = ShellNoticeTone.Caution) }
        } finally {
            if (!beginAttempted) terminal = true
            if (!terminal) {
                withContext(NonCancellable) {
                    val resolved = runCatching {
                        val current = query()
                        if (current.code == 0 && isOurs(current) && current.state == 1) {
                            val result = withContext(boundaryDispatcher) { bridge.cancelAdvance(current.generation) }
                            if (result.code == 0 && isOurs(result)) result else query()
                        } else current
                    }.getOrNull()
                    if (resolved != null && resolved.code == 0 && isOurs(resolved) && resolved.state in 2..3) {
                        terminal = true
                        outcome = if (resolved.state == 2) ManualAdvanceOutcome.Complete else ManualAdvanceOutcome.Cancelled
                        runCatching { withContext(boundaryDispatcher) { bridge.refresh().forEach(::applySignal) } }
                    }
                }
            }
            if (terminal) synchronized(manualLock) { unresolvedAdvance = null }
            _uiState.update { current -> current.copy(
                manualAdvance = if (terminal) null else current.manualAdvance?.copy(status = "Advance status unknown", cancelRequested = false),
                manualAdvanceOutcome = outcome ?: current.manualAdvanceOutcome,
            ) }
        }
    }

    private suspend fun reconcileUnknownAdvance(identity: AdvanceIdentity) {
        fun matches(result: NativeAdvanceResult) = result.code == 0 &&
            result.intentHigh == identity.intent.mostSignificantBits && result.intentLow == identity.intent.leastSignificantBits &&
            result.baseOrdinal == identity.baseOrdinal && result.requestedSeconds.toBits() == identity.delta.toBits() &&
            result.recordedAtUnixMs == identity.recordedAt && result.generation == identity.priorGeneration + 1
        val resolved = runCatching {
            var result = withContext(boundaryDispatcher) { bridge.queryAdvance() }
            if (matches(result) && result.state == 1) {
                result = withContext(boundaryDispatcher) { bridge.cancelAdvance(result.generation) }
            }
            if (matches(result) && result.state in 2..3) {
                withContext(boundaryDispatcher) { bridge.refresh().forEach(::applySignal) }
                if (result.state == 2) ManualAdvanceOutcome.Complete else ManualAdvanceOutcome.Cancelled
            } else null
        }.getOrElse { error -> if (error is CancellationException) throw error else null }
        if (resolved != null) synchronized(manualLock) { unresolvedAdvance = null }
        _uiState.update { current -> current.copy(
            manualAdvance = if (resolved != null) null else current.manualAdvance?.copy(status = "Advance status unknown", cancelRequested = false),
            manualAdvanceOutcome = resolved ?: current.manualAdvanceOutcome,
        ) }
    }

    private fun applySignal(signal: RuntimeSignal) {
        val current = _uiState.value
        val (handle, revision) = when (signal) {
            is RuntimeSignal.Connected -> signal.handle to null
            is RuntimeSignal.PlaybackMeasured -> signal.sessionHandle to signal.revision
            is RuntimeSignal.PlaybackMeasurementCleared -> signal.sessionHandle to signal.revision
            is RuntimeSignal.SnapshotUpdated -> signal.sessionHandle to signal.summary.revision
            is RuntimeSignal.CommandApplied -> signal.sessionHandle to signal.summary.revision
            is RuntimeSignal.RenderPacketReady -> signal.sessionHandle to signal.revision
            is RuntimeSignal.RenderUnavailable -> signal.sessionHandle to signal.revision
            else -> null to null
        }
        if ((handle != null && !bridge.isCurrentSession(handle)) ||
            (handle != null && signal !is RuntimeSignal.Connected && handle != current.sessionHandle) ||
            (revision != null && current.snapshot != null && revision < current.snapshot.revision)
        ) {
            if (signal is RuntimeSignal.RenderPacketReady) signal.lease.close()
            return
        }
        // Scenario loading and save import both publish an accepted new handle.
        // Failed replacement and stale signals cannot erase a still-valid receipt.
        if (signal is RuntimeSignal.Connected && _lastProbeCommit.value?.sessionHandle != signal.handle) {
            _lastProbeCommit.value = null
        }
        if (signal is RuntimeSignal.CommandApplied && when (signal.command) {
                is RuntimeCommand.SpawnBody, is RuntimeCommand.RemoveBody,
                is RuntimeCommand.SetBodyKinematics, RuntimeCommand.SeedCanonicalSolarSystem,
                is RuntimeCommand.CreateCheckpoint, is RuntimeCommand.CreateBranchFromCheckpoint -> true
                else -> false
            }) {
            _lastProbeCommit.value = null
        }
        when (signal) {
            is RuntimeSignal.PlaybackMeasurementCleared -> _uiState.update { it.copy(playbackMeasurement = null) }
            is RuntimeSignal.PlaybackMeasured -> _uiState.update { current ->
                current.copy(playbackMeasurement = PlaybackMeasurement(signal.revision, signal.requestedSeconds, signal.appliedSeconds, signal.limited, signal.achievedRate))
            }
            is RuntimeSignal.Connected -> updateWithTelemetry(
                recordTelemetry(
                    level = DeveloperTelemetryLevel.Info,
                    category = "session.connected",
                    message = "Bound runtime session handle ${signal.handle}",
                ),
            ) { current ->
                current.copy(
                    connectionState = SessionConnectionState.Active,
                    statusLine = "Runtime session connected",
                    detailLine = "Session handle ${signal.handle} is now owned by the Rust boundary",
                    noticeLine = "Session bridge established",
                    noticeTone = ShellNoticeTone.Positive,
                    pendingActionLabel = null,
                    sessionHandle = signal.handle,
                    snapshot = if (current.sessionHandle != signal.handle) null else current.snapshot,
                    renderStatus = current.renderStatus.copy(
                        readiness = RenderHostReadiness.Refreshing,
                        issue = null,
                    ),
                )
            }

            is RuntimeSignal.RuntimeInfoAvailable -> updateWithTelemetry(
                recordTelemetry(
                    level = DeveloperTelemetryLevel.Info,
                    category = "runtime.info",
                    message = runtimeInfoTelemetryMessage(signal),
                ),
            ) { current ->
                val backendSummary = backendSummaryLabel(
                    requestedCpuBackendLabel = signal.requestedCpuBackendLabel,
                    cpuBackendLabel = signal.cpuBackendLabel,
                    cpuSolverPathLabel = signal.cpuSolverPathLabel,
                    cpuFeatureSummary = signal.cpuFeatureSummary,
                    cpuFallbackSummary = signal.cpuFallbackSummary,
                    cpuScheduleSummary = signal.cpuScheduleSummary,
                    cpuKernelCatalogSummary = signal.cpuKernelCatalogSummary,
                    requestedGpuBackendLabel = signal.requestedGpuBackendLabel,
                    gpuBackendLabel = signal.gpuBackendLabel,
                    workloadSummary = signal.workloadSummary,
                    interopErrorBudgetSummary = signal.interopErrorBudgetSummary,
                )
                current.copy(
                    backendSummary = backendSummary,
                    noticeLine = "Runtime backend: $backendSummary",
                    noticeTone = ShellNoticeTone.Positive,
                )
            }

            is RuntimeSignal.Notice -> updateWithTelemetry(
                recordTelemetry(
                    level = signal.level.toDeveloperTelemetryLevel(),
                    category = "runtime.notice",
                    message = signal.message,
                ),
            ) { current ->
                current.copy(
                    noticeLine = signal.message,
                    noticeTone = signal.level.toShellTone(),
                )
            }

            is RuntimeSignal.SnapshotUpdated -> {
                val snapshot = signal.summary.toSnapshotPresentation()
                val snapshotTelemetryKey = snapshot.toTelemetryKey()
                val telemetry = if (snapshotTelemetryKey == lastSnapshotTelemetryKey) null else {
                    lastSnapshotTelemetryKey = snapshotTelemetryKey
                    recordTelemetry(DeveloperTelemetryLevel.Info, "snapshot.updated", snapshot.toTelemetrySummary())
                }
                updateWithTelemetry(telemetry) { current ->
                    val recentFocusedBodyIds = current.recentFocusedBodyIds
                        .updatedRecentFocusedBodyIds(snapshot.focusTargetBodyId)
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
                        playbackMeasurement = current.playbackMeasurement?.takeIf { !snapshot.paused && it.revision == snapshot.revision },
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
                }
            }

            is RuntimeSignal.CommandApplied -> {
                val snapshot = signal.summary.toSnapshotPresentation()
                updateWithTelemetry(
                    recordTelemetry(
                        level = DeveloperTelemetryLevel.Info,
                        category = "command.applied",
                        message = "${signal.commandLabel} -> ${snapshot.toTelemetrySummary()}",
                    ),
                ) { current ->
                    val focusedBodyId = snapshot.focusTargetBodyId
                    val checkpointId = snapshot.activeCheckpointId
                    val checkpointLabel = snapshot.activeCheckpointLabel
                    val recentFocusedBodyIds = current.recentFocusedBodyIds
                        .updatedRecentFocusedBodyIds(focusedBodyId)
                    current.copy(
                        connectionState = SessionConnectionState.Active,
                        statusLine = "Runtime command applied",
                        detailLine = "${signal.commandLabel} at epoch ${signal.summary.epochSeconds.asEpochLabel()}",
                        noticeLine = "Runtime accepted ${signal.commandLabel}",
                        noticeTone = ShellNoticeTone.Positive,
                        pendingActionLabel = null,
                        snapshot = snapshot,
                        playbackMeasurement = current.playbackMeasurement?.takeIf { !snapshot.paused && it.revision == snapshot.revision },
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
                    )
                }
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
                    val renderTelemetryKey = packet.toTelemetryKey(sceneRevisionLabel = sceneRevisionLabel)
                    lastRenderUnavailableReason = null
                    val telemetry = if (renderTelemetryKey == lastRenderTelemetryKey) null else {
                        lastRenderTelemetryKey = renderTelemetryKey
                        recordTelemetry(DeveloperTelemetryLevel.Info, "render.ready", packet.toTelemetrySummary(sceneRevision = sceneRevisionLabel))
                    }
                    updateWithTelemetry(telemetry) { current ->
                        current.copy(
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
                    }
                } catch (error: Throwable) {
                    updateWithTelemetry(
                        recordTelemetry(
                            level = DeveloperTelemetryLevel.Error,
                            category = "render.decode_failed",
                            message = error.message ?: error::class.java.simpleName,
                        ),
                    ) { current ->
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

            is RuntimeSignal.RenderUnavailable -> {
                val telemetry = if (signal.reason == lastRenderUnavailableReason) null else {
                    lastRenderUnavailableReason = signal.reason
                    recordTelemetry(DeveloperTelemetryLevel.Warning, "render.unavailable", signal.reason)
                }
                updateWithTelemetry(telemetry) { current ->
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
            }

            is RuntimeSignal.Unavailable -> updateWithTelemetry(
                recordTelemetry(
                    level = DeveloperTelemetryLevel.Error,
                    category = "session.unavailable",
                    message = signal.detail ?: signal.message,
                ),
            ) { current ->
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
        val presentation = telemetry?.let { recordTelemetry(it.level, it.category, it.message) }
        updateWithTelemetry(presentation) { current -> onStart(current) }
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
        updateWithTelemetry(
            recordTelemetry(
                level = DeveloperTelemetryLevel.Error,
                category = "shell.failure",
                message = "$statusLine: $detailLine",
            ),
        ) { current ->
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
            )
        }
    }

    private fun updateWithTelemetry(
        presentation: DeveloperTelemetryPresentation?,
        transform: (ShellUiState) -> ShellUiState,
    ) {
        _uiState.update { current ->
            val next = transform(current)
            if (presentation != null &&
                presentation.entries.size + presentation.droppedEntryCount >=
                current.developerTelemetry.entries.size + current.developerTelemetry.droppedEntryCount
            ) next.copy(developerTelemetry = presentation) else next
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
    return SnapshotPresentation(
        revision = revision,
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
    is RuntimeCommand.SetBodyProperties -> "Update mass and motion for ${bodyId}"
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
    requestedCpuBackendLabel: String?,
    cpuBackendLabel: String,
    cpuSolverPathLabel: String?,
    cpuFeatureSummary: String?,
    cpuFallbackSummary: String?,
    cpuScheduleSummary: String?,
    cpuKernelCatalogSummary: String?,
    requestedGpuBackendLabel: String?,
    gpuBackendLabel: String,
    workloadSummary: String?,
    interopErrorBudgetSummary: String?,
): String {
    val requestedCpuSegment = requestedCpuBackendLabel
        ?.takeIf { it.isNotBlank() }
        ?.let { requested ->
            if (requested == cpuBackendLabel) {
                "cpu=$cpuBackendLabel"
            } else {
                "cpu=requested $requested -> effective $cpuBackendLabel"
            }
        }
        ?: "cpu=$cpuBackendLabel"
    val requestedGpuSegment = requestedGpuBackendLabel
        ?.takeIf { it.isNotBlank() }
        ?.let { requested ->
            if (requested == gpuBackendLabel) {
                "gpu=$gpuBackendLabel"
            } else {
                "gpu=requested $requested -> effective $gpuBackendLabel"
            }
        }
        ?: "gpu=$gpuBackendLabel"
    val segments = mutableListOf(requestedCpuSegment, requestedGpuSegment)
    cpuSolverPathLabel?.takeIf { it.isNotBlank() }?.let {
        segments += "solver: $it"
    }
    cpuFeatureSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "cpu features: $it"
    }
    cpuFallbackSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "cpu fallback: $it"
    }
    cpuScheduleSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "cpu scheduler: $it"
    }
    cpuKernelCatalogSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "cpu kernels: $it"
    }
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
        "requested-cpu=${signal.requestedCpuBackendLabel ?: signal.cpuBackendLabel}",
        "cpu=${signal.cpuBackendLabel}",
        "solver=${signal.cpuSolverPathLabel ?: "unknown"}",
        "requested-gpu=${signal.requestedGpuBackendLabel ?: signal.gpuBackendLabel}",
        "gpu=${signal.gpuBackendLabel}",
    )
    signal.cpuFeatureSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "cpu-features=$it"
    }
    signal.cpuFallbackSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "cpu-fallback=$it"
    }
    signal.cpuScheduleSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "cpu-scheduler=$it"
    }
    signal.cpuKernelCatalogSummary?.takeIf { it.isNotBlank() }?.let {
        segments += "cpu-kernels=$it"
    }
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
