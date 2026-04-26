package com.sednalabs.solarlab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.feature.lab.PlaybackSpeedPreset
import com.graciousgazelles.solarlab.feature.lab.StepQuantumPreset
import com.graciousgazelles.solarlab.feature.lab.render.RenderInteractionListener
import com.graciousgazelles.solarlab.feature.lab.render.RenderProcessingMode
import com.graciousgazelles.solarlab.feature.lab.render.SceneInteractionMode
import com.graciousgazelles.solarlab.feature.lab.render.SolarSystemRenderHostView
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderBody as StageRenderBody
import com.graciousgazelles.solarlab.render.core.RenderBodyKind
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame
import com.graciousgazelles.solarlab.render.core.RenderTrail as StageRenderTrail
import com.sednalabs.solarlab.runtime.RenderFrame
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RenderStatusPresentation
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.RuntimeObserverMode
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellUiState
import com.sednalabs.solarlab.runtime.toShareText
import com.sednalabs.solarlab.ui.theme.SolarLabTheme
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private data class RuntimeMirrorBody(
    val id: String,
    val displayName: String,
    val positionM: Vector3d,
    val radiusM: Double,
    val colorArgb: Int,
    val kind: RenderBodyKind,
    val hostBodyId: String?,
)

private data class RuntimeMirrorScene(
    val scene: RenderSceneFrame,
    val searchableBodies: List<RuntimeMirrorBody>,
)

private data class RuntimeSelectionCard(
    val title: String,
    val detail: String,
)

private val RuntimeMirrorCompactWidthBreakpoint = 720.dp
private val RuntimeMirrorVoid = Color(0xFF02050B)
private val RuntimeMirrorGlass = Color(0xE6070D18)
private val RuntimeMirrorGlassSoft = Color(0xB40B1424)
private val RuntimeMirrorCyan = Color(0xFF76F7FF)
private val RuntimeMirrorCyanDim = Color(0xFF2A9DAC)
private val RuntimeMirrorGold = Color(0xFFFFD36B)
private val RuntimeMirrorBlue = Color(0xFF5E8CFF)
private val RuntimeMirrorInkLine = Color(0xFF19324B)
private val RuntimeMirrorText = Color(0xFFE8F7FF)
private val RuntimeMirrorTextDim = Color(0xFF9FB6C9)
private const val RUNTIME_MIRROR_MISSION_DAY_SECONDS = 86_400.0
private const val RUNTIME_MIRROR_SIGNAL_BODY_NORMALIZATION = 18f
private const val RUNTIME_MIRROR_SIGNAL_TRAIL_NORMALIZATION = 28f
private const val RUNTIME_MIRROR_SIGNAL_FOCUS_LIFT = 0.16f
private const val RUNTIME_MIRROR_SIGNAL_SELECTED_LIFT = 0.12f
private const val RUNTIME_MIRROR_SIGNAL_L1_BASE = 0.24f
private const val RUNTIME_MIRROR_SIGNAL_L1_BODY_COEFF = 0.34f
private const val RUNTIME_MIRROR_SIGNAL_L2_BASE = 0.22f
private const val RUNTIME_MIRROR_SIGNAL_L2_SELECTED_COEFF = 0.44f
private const val RUNTIME_MIRROR_SIGNAL_L3_BASE = 0.30f
private const val RUNTIME_MIRROR_SIGNAL_L3_TRAIL_COEFF = 0.42f
private const val RUNTIME_MIRROR_SIGNAL_L4_BASE = 0.20f
private const val RUNTIME_MIRROR_SIGNAL_L4_TIME_COEFF = 0.50f
private const val RUNTIME_MIRROR_SIGNAL_L5_BASE = 0.32f
private const val RUNTIME_MIRROR_SIGNAL_L5_BODY_COEFF = 0.20f
private const val RUNTIME_MIRROR_SIGNAL_L5_TRAIL_COEFF = 0.22f
private const val RUNTIME_MIRROR_SIGNAL_L6_BASE = 0.28f
private const val RUNTIME_MIRROR_SIGNAL_L6_FOCUS_COEFF = 0.48f
private const val RUNTIME_MIRROR_CAMERA_ZOOM_IN_FACTOR: Float = 1.2f
private const val RUNTIME_MIRROR_CAMERA_ZOOM_OUT_FACTOR: Float = 1f / RUNTIME_MIRROR_CAMERA_ZOOM_IN_FACTOR

@Composable
internal fun StageFirstRuntimeMirrorExperience(
    runtimeFacade: RuntimeFacade?,
    ensureRuntimeStarted: (() -> Unit)?,
    pendingSemanticAction: PendingSemanticAction?,
    onReturnToSandbox: () -> Unit,
) {
    SolarLabTheme {
        val lifecycleOwner = LocalLifecycleOwner.current
        val coroutineScope = rememberCoroutineScope()

        val fallbackRuntimeState = remember {
            mutableStateOf(
                ShellUiState(
                    connectionState = SessionConnectionState.Unavailable,
                    statusLine = "Runtime mirror unavailable",
                    detailLine = "This stage-first build did not provide a Rust runtime facade.",
                    noticeLine = "Switch back to Sandbox to keep using the restored local client.",
                    renderStatus = RenderStatusPresentation(
                        readiness = RenderHostReadiness.Unavailable,
                        isDegraded = true,
                        degradationReason = "Runtime facade missing",
                        issue = "Runtime facade missing",
                    ),
                )
            )
        }
        val uiState by runtimeFacade?.uiState?.collectAsState() ?: fallbackRuntimeState

        var selectedBodyId by rememberSaveable { mutableStateOf<String?>(null) }
        var observerMode by remember { mutableStateOf(ObserverMode.FREE) }
        var renderProcessingMode by remember { mutableStateOf(HostedDebugMode.initialRenderProcessingMode) }
        var stepQuantumPreset by remember { mutableStateOf(StepQuantumPreset.SIX_HOURS) }
        var playbackSpeedPreset by remember { mutableStateOf(PlaybackSpeedPreset.SIX_HOURS_PER_SECOND) }
        var searchVisible by rememberSaveable { mutableStateOf(false) }
        var debugVisible by rememberSaveable { mutableStateOf(false) }
        var renderHostView by remember { mutableStateOf<SolarSystemRenderHostView?>(null) }
        var hostRendererStatus by remember { mutableStateOf("Preparing immersive runtime mirror.") }
        var appliedSemanticActionToken by remember { mutableStateOf<Long?>(null) }
        var hostedDebugModeApplied by remember { mutableStateOf(false) }

        val mirrorScene = remember(uiState.renderFrame) {
            uiState.renderFrame?.toRuntimeMirrorScene()
        }
        val searchableBodies = mirrorScene?.searchableBodies.orEmpty()
        val selectedBody = remember(searchableBodies, selectedBodyId) {
            searchableBodies.firstOrNull { it.id == selectedBodyId }
        }
        val runtimeSessionHandle = uiState.sessionHandle ?: 0L
        val selectionCard = remember(uiState, selectedBody) {
            buildRuntimeSelectionCard(
                uiState = uiState,
                selectedBody = selectedBody,
            )
        }
        val diagnosticsText = remember(uiState, hostRendererStatus, observerMode, renderProcessingMode, stepQuantumPreset, playbackSpeedPreset) {
            buildRuntimeDiagnosticsText(
                uiState = uiState,
                hostRendererStatus = hostRendererStatus,
                observerMode = observerMode,
                renderProcessingMode = renderProcessingMode,
                stepQuantumPreset = stepQuantumPreset,
                playbackSpeedPreset = playbackSpeedPreset,
            )
        }
        val backendStatus = remember(uiState, hostRendererStatus) {
            buildRuntimeBackendStatus(uiState = uiState, hostRendererStatus = hostRendererStatus)
        }
        val interactionHintText = remember(uiState) {
            when {
                uiState.connectionState == SessionConnectionState.Connecting ->
                    "Connecting the restored stage-first camera to the Rust-authoritative world."

                uiState.sessionHandle == null ->
                    "Waiting for the runtime session handle that lets the immersive stage stream directly from Rust."

                uiState.renderFrame == null ->
                    "Streaming the stage straight from the Rust session while the Android shell waits for decoded packet metadata."

                uiState.renderStatus.issue != null ->
                    "Using the native immersive stage while the packet bridge reports: ${uiState.renderStatus.issue}"

                else ->
                    "Tap bodies to focus them, pinch through Close→Deep scales, drag to pan, and use two fingers to orbit/tilt the immersive camera without dropping back to the packet-viewer shell. Full object editing still lives in Sandbox for now."
            }
        }
        val canSendCommands = runtimeFacade != null && uiState.connectionState == SessionConnectionState.Active
        val isRunning = uiState.snapshot?.paused == false
        val cameraControlsEnabled = runtimeSessionHandle != 0L || mirrorScene?.scene != null
        val refreshRuntime = {
            if (runtimeFacade != null) {
                coroutineScope.launch {
                    runtimeFacade.refresh()
                }
            }
        }
        fun sendRuntimeCommand(command: RuntimeCommand) {
            if (runtimeFacade == null) {
                return
            }
            coroutineScope.launch {
                runtimeFacade.applyCommand(command)
            }
        }

        fun syncObserver(bodyId: String?, mode: ObserverMode) {
            if (runtimeFacade == null) {
                return
            }
            coroutineScope.launch {
                runtimeFacade.applyCommand(RuntimeCommand.FocusBody(bodyId))
                runtimeFacade.applyCommand(RuntimeCommand.SetObserverMode(mode.toRuntimeObserverMode()))
            }
        }

        fun focusAndFrameRuntimeBody(bodyId: String) {
            selectedBodyId = bodyId
            observerMode = ObserverMode.FOLLOW_SELECTED
            renderHostView?.focusAndFrameBody(bodyId, ObserverMode.FOLLOW_SELECTED)
            syncObserver(bodyId, ObserverMode.FOLLOW_SELECTED)
        }

        BackHandler(enabled = searchVisible || debugVisible) {
            when {
                searchVisible -> searchVisible = false
                debugVisible -> debugVisible = false
            }
        }

        LaunchedEffect(runtimeFacade) {
            if (runtimeFacade != null) {
                ensureRuntimeStarted?.invoke()
            }
        }

        LaunchedEffect(uiState.snapshot?.simSecondsPerRealSecond) {
            val runtimeSpeed = uiState.snapshot?.simSecondsPerRealSecond ?: return@LaunchedEffect
            playbackSpeedPreset = nearestPlaybackSpeedPreset(runtimeSpeed)
        }

        LaunchedEffect(uiState.focusedBodyId) {
            selectedBodyId = uiState.focusedBodyId
        }

        LaunchedEffect(uiState.observerModeCode) {
            observerMode = observerModeFromRuntimeCode(uiState.observerModeCode)
        }

        LaunchedEffect(canSendCommands, uiState.snapshot?.paused) {
            if (!HostedDebugMode.enabled || hostedDebugModeApplied || !canSendCommands) {
                return@LaunchedEffect
            }

            renderProcessingMode = RenderProcessingMode.LOW

            val snapshot = uiState.snapshot ?: return@LaunchedEffect
            if (!snapshot.paused) {
                sendRuntimeCommand(RuntimeCommand.PausePlayback)
                return@LaunchedEffect
            }

            hostedDebugModeApplied = true
        }

        LaunchedEffect(mirrorScene?.scene?.sourceRevision, searchableBodies) {
            if (selectedBodyId != null && searchableBodies.none { body -> body.id == selectedBodyId }) {
                selectedBodyId = null
                observerMode = ObserverMode.FREE
            }
        }

        LaunchedEffect(pendingSemanticAction?.token, searchableBodies, renderHostView) {
            if (pendingSemanticAction?.token == appliedSemanticActionToken) {
                return@LaunchedEffect
            }
            when (val action = pendingSemanticAction?.action) {
                is SolarLabSemanticAction.FocusBody -> {
                    val resolvedBodyId = resolveRuntimeSemanticBodyId(
                        bodies = searchableBodies,
                        bodyQuery = action.bodyQuery,
                    ) ?: return@LaunchedEffect
                    selectedBodyId = resolvedBodyId
                    observerMode = ObserverMode.FOLLOW_SELECTED
                    searchVisible = false
                    debugVisible = false
                    syncObserver(resolvedBodyId, observerMode)
                    appliedSemanticActionToken = pendingSemanticAction.token
                }

                SolarLabSemanticAction.ResetCamera -> {
                    renderHostView ?: return@LaunchedEffect
                    renderHostView?.resetCamera()
                    appliedSemanticActionToken = pendingSemanticAction.token
                }

                else -> Unit
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                renderHostView?.release()
            }
        }

        DisposableEffect(lifecycleOwner, renderHostView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> renderHostView?.onHostResume()
                    Lifecycle.Event.ON_PAUSE -> renderHostView?.onHostPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            RuntimeMirrorVoid,
                            Color(0xFF06101C),
                            Color(0xFF0B1829),
                        )
                    )
                ),
        ) {
            val compactLayout = maxWidth < RuntimeMirrorCompactWidthBreakpoint
            val actionButtons: @Composable RowScope.() -> Unit = {
                StageActionButton(
                    label = "Sandbox",
                    onClick = onReturnToSandbox,
                    modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_RUNTIME_SANDBOX_BUTTON),
                    secondary = true,
                )
                StageActionButton(
                    label = if (searchVisible) "Searching" else "Search",
                    onClick = { searchVisible = true },
                    modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON),
                    emphasized = searchVisible,
                    enabled = searchableBodies.isNotEmpty(),
                )
                StageActionButton(
                    label = if (debugVisible) "Debugging" else "Debug",
                    onClick = { debugVisible = true },
                    modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON),
                    emphasized = debugVisible,
                )
            }
            val primaryControls: @Composable RowScope.() -> Unit = {
                StageActionButton(
                    label = "Zoom +",
                    onClick = { renderHostView?.zoomBy(RUNTIME_MIRROR_CAMERA_ZOOM_IN_FACTOR) },
                    enabled = cameraControlsEnabled,
                    modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON),
                )
                StageActionButton(
                    label = "Zoom -",
                    onClick = { renderHostView?.zoomBy(RUNTIME_MIRROR_CAMERA_ZOOM_OUT_FACTOR) },
                    enabled = cameraControlsEnabled,
                    modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON),
                )
                StageActionButton(
                    label = "Frame selected",
                    onClick = { selectedBodyId?.let(::focusAndFrameRuntimeBody) },
                    enabled = cameraControlsEnabled && selectedBodyId != null,
                    modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON),
                )
                StageActionButton(
                    label = if (isRunning) "Pause" else "Start",
                    onClick = {
                        if (isRunning) {
                            sendRuntimeCommand(RuntimeCommand.PausePlayback)
                        } else {
                            sendRuntimeCommand(RuntimeCommand.ResumePlayback)
                        }
                    },
                    emphasized = isRunning,
                    enabled = canSendCommands,
                )
                StageActionButton(
                    label = "Step once",
                    onClick = { sendRuntimeCommand(RuntimeCommand.AdvanceEpoch(stepQuantumPreset.seconds)) },
                    enabled = canSendCommands && !isRunning,
                )
                StageActionButton(
                    label = "Forward step",
                    onClick = { sendRuntimeCommand(RuntimeCommand.AdvanceEpoch(stepQuantumPreset.seconds)) },
                    enabled = canSendCommands && !isRunning,
                )
                StageActionButton(
                    label = observerMode.runtimeDisplayLabel(),
                    onClick = {
                        observerMode = when (observerMode) {
                            ObserverMode.FREE -> ObserverMode.FOLLOW_SELECTED
                            ObserverMode.FOLLOW_SELECTED -> ObserverMode.FOLLOW_SELECTED_HOST
                            ObserverMode.FOLLOW_SELECTED_HOST -> ObserverMode.FREE
                        }
                        syncObserver(selectedBodyId, observerMode)
                    },
                    enabled = selectedBodyId != null || observerMode != ObserverMode.FREE,
                )
                StageActionButton(
                    label = "Refresh",
                    onClick = {
                        selectedBodyId = null
                        observerMode = ObserverMode.FREE
                        renderHostView?.resetCamera()
                        refreshRuntime()
                    },
                    enabled = runtimeFacade != null,
                )
            }
            val secondaryControls: @Composable RowScope.() -> Unit = {
                StageActionButton(
                    label = "Step ${stepQuantumPreset.label}",
                    onClick = { stepQuantumPreset = stepQuantumPreset.shifted(1) },
                )
                StageActionButton(
                    label = "Slower",
                    onClick = {
                        val nextPreset = playbackSpeedPreset.shifted(-1)
                        playbackSpeedPreset = nextPreset
                        sendRuntimeCommand(RuntimeCommand.SetPlaybackRate(nextPreset.simSecondsPerRealSecond))
                    },
                    enabled = canSendCommands,
                )
                StageActionButton(
                    label = "Faster · ${playbackSpeedPreset.label}",
                    onClick = {
                        val nextPreset = playbackSpeedPreset.shifted(1)
                        playbackSpeedPreset = nextPreset
                        sendRuntimeCommand(RuntimeCommand.SetPlaybackRate(nextPreset.simSecondsPerRealSecond))
                    },
                    enabled = canSendCommands,
                )
                StageActionButton(
                    label = when (renderProcessingMode) {
                        RenderProcessingMode.DEFAULT -> "Rendering: Standard"
                        RenderProcessingMode.LOW -> "Rendering: Simplified"
                    },
                    onClick = {
                        renderProcessingMode = when (renderProcessingMode) {
                            RenderProcessingMode.DEFAULT -> RenderProcessingMode.LOW
                            RenderProcessingMode.LOW -> RenderProcessingMode.DEFAULT
                        }
                    },
                    secondary = true,
                )
            }

            if (runtimeSessionHandle != 0L || mirrorScene?.scene != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        SolarSystemRenderHostView(viewContext).also { view ->
                            view.setOnBackendStatusChangedListener { status ->
                                hostRendererStatus = status.message
                            }
                            view.setInteractionMode(SceneInteractionMode.NAVIGATE_AND_SELECT)
                            view.setInteractionListener(
                                object : RenderInteractionListener {
                                    override fun onBodySelectionChanged(bodyId: String?) {
                                        selectedBodyId = bodyId
                                        if (bodyId == null && observerMode != ObserverMode.FREE) {
                                            observerMode = ObserverMode.FREE
                                        }
                                        syncObserver(selectedBodyId, observerMode)
                                    }

                                    override fun onPlacementGesture(
                                        startWorldPositionM: Vector3d,
                                        endWorldPositionM: Vector3d,
                                        gestureDistancePx: Float,
                                    ) = Unit
                                }
                            )
                            renderHostView = view
                        }
                    },
                    update = { view ->
                        renderHostView = view
                        view.bindRuntimeSessionHandle(runtimeSessionHandle)
                        view.setProcessingMode(renderProcessingMode)
                        view.setObserverMode(observerMode)
                        view.setSelectedBodyId(selectedBodyId)
                        view.setInteractionMode(SceneInteractionMode.NAVIGATE_AND_SELECT)
                        mirrorScene?.scene?.let(view::submitSceneFrame)
                    },
                )
                RuntimeMirrorBackdropOverlay(compact = compactLayout)
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    StagePanel {
                        Text(
                            text = uiState.statusLine,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        uiState.detailLine?.let { detail ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        StageActionButton(
                            label = "Refresh runtime",
                            onClick = refreshRuntime,
                            modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_RUNTIME_REFRESH_BUTTON),
                            enabled = runtimeFacade != null,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (compactLayout) {
                    RuntimeMirrorMissionPanel(
                        uiState = uiState,
                        selectionCard = selectionCard,
                        selectedBody = selectedBody,
                        observerMode = observerMode,
                        renderProcessingMode = renderProcessingMode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actionButtons,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RuntimeMirrorMissionPanel(
                            uiState = uiState,
                            selectionCard = selectionCard,
                            selectedBody = selectedBody,
                            observerMode = observerMode,
                            renderProcessingMode = renderProcessingMode,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL),
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            content = actionButtons,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RuntimeMirrorTimelineRail(
                    uiState = uiState,
                    fallbackSpeedPreset = playbackSpeedPreset,
                    modifier = Modifier.fillMaxWidth(),
                )
                StageControlRail(compact = compactLayout, content = primaryControls)
                StageControlRail(compact = compactLayout, content = secondaryControls)
                StagePanel(
                    modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_STATUS_PANEL),
                ) {
                    Text(
                        text = backendStatus,
                        color = RuntimeMirrorCyan,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = interactionHintText,
                        color = RuntimeMirrorTextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (searchVisible) {
            RuntimeMirrorSearchDialog(
                bodies = searchableBodies,
                selectedBodyId = selectedBodyId,
                onDismiss = { searchVisible = false },
                onSelectBody = { bodyId ->
                    searchVisible = false
                    focusAndFrameRuntimeBody(bodyId)
                },
            )
        }

        if (debugVisible) {
            RuntimeMirrorDebugDialog(
                uiState = uiState,
                hostRendererStatus = hostRendererStatus,
                stepQuantumPreset = stepQuantumPreset,
                playbackSpeedPreset = playbackSpeedPreset,
                renderProcessingMode = renderProcessingMode,
                observerMode = observerMode,
                selectedBodyId = selectedBodyId,
                onDismiss = { debugVisible = false },
                onRefresh = refreshRuntime,
            )
        }
    }
}

@Composable
private fun RuntimeMirrorBackdropOverlay(compact: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val haloCenter = Offset(size.width * 0.74f, size.height * if (compact) 0.54f else 0.48f)
        val haloRadius = size.minDimension * if (compact) 0.30f else 0.38f
        drawCircle(
            color = RuntimeMirrorCyanDim.copy(alpha = 0.07f),
            radius = haloRadius,
            center = haloCenter,
        )
        drawCircle(
            color = RuntimeMirrorBlue.copy(alpha = 0.05f),
            radius = haloRadius * 0.62f,
            center = Offset(size.width * 0.22f, size.height * 0.24f),
        )
        drawArc(
            color = RuntimeMirrorCyan.copy(alpha = 0.11f),
            startAngle = 196f,
            sweepAngle = 116f,
            useCenter = false,
            topLeft = Offset(haloCenter.x - haloRadius, haloCenter.y - haloRadius),
            size = Size(haloRadius * 2f, haloRadius * 2f),
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color = RuntimeMirrorGold.copy(alpha = 0.16f),
            startAngle = 242f,
            sweepAngle = 34f,
            useCenter = false,
            topLeft = Offset(haloCenter.x - haloRadius * 1.18f, haloCenter.y - haloRadius * 1.18f),
            size = Size(haloRadius * 2.36f, haloRadius * 2.36f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )

        val gridTop = size.height * if (compact) 0.10f else 0.08f
        val gridBottom = size.height * if (compact) 0.48f else 0.58f
        repeat(12) { index ->
            val fraction = index / 11f
            drawLine(
                color = RuntimeMirrorInkLine.copy(alpha = 0.30f),
                start = Offset(size.width * fraction, gridTop),
                end = Offset(size.width * (fraction + 0.08f), gridBottom),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun RuntimeMirrorMissionPanel(
    uiState: ShellUiState,
    selectionCard: RuntimeSelectionCard,
    selectedBody: RuntimeMirrorBody?,
    observerMode: ObserverMode,
    renderProcessingMode: RenderProcessingMode,
    modifier: Modifier = Modifier,
) {
    val revision = uiState.renderStatus.sceneRevision ?: uiState.renderFrame?.sceneRevision ?: "waiting"
    val bodyCount = uiState.renderStatus.renderedBodyCount
    val trailCount = uiState.renderStatus.renderedTrailCount
    val focusLabel = selectedBody?.displayName ?: uiState.focusedBodyId?.let(::displayNameForBodyId) ?: "Free camera"

    Surface(
        modifier = modifier.widthIn(max = 760.dp),
        color = RuntimeMirrorGlass,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, RuntimeMirrorCyan.copy(alpha = 0.30f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            RuntimeMirrorCyanDim.copy(alpha = 0.20f),
                            RuntimeMirrorGlass,
                            RuntimeMirrorGold.copy(alpha = 0.08f),
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TRAJECTORY STAGE",
                    color = RuntimeMirrorCyan,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = when (renderProcessingMode) {
                        RenderProcessingMode.DEFAULT -> "FULL DETAIL"
                        RenderProcessingMode.LOW -> "LITE RENDER"
                    },
                    color = RuntimeMirrorGold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = selectionCard.title,
                modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE),
                color = RuntimeMirrorText,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = selectionCard.detail,
                color = RuntimeMirrorTextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuntimeMirrorMetricPill(label = "Focus", value = focusLabel)
                RuntimeMirrorMetricPill(label = "Rev", value = revision)
                RuntimeMirrorMetricPill(label = "Scene", value = "$bodyCount bodies / $trailCount trails")
                RuntimeMirrorMetricPill(label = "Camera", value = observerMode.runtimeDisplayLabel().removePrefix("Observer: "))
            }
            RuntimeMirrorMiniSignalChart(
                values = runtimeMirrorSignalValues(uiState = uiState, selectedBody = selectedBody),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
            )
        }
    }
}

@Composable
private fun RuntimeMirrorMetricPill(
    label: String,
    value: String,
) {
    Surface(
        color = RuntimeMirrorGlassSoft,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, RuntimeMirrorCyanDim.copy(alpha = 0.24f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label.uppercase(Locale.US),
                color = RuntimeMirrorTextDim,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = value,
                color = RuntimeMirrorText,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RuntimeMirrorTimelineRail(
    uiState: ShellUiState,
    fallbackSpeedPreset: PlaybackSpeedPreset,
    modifier: Modifier = Modifier,
) {
    val snapshot = uiState.snapshot
    val epochSeconds = snapshot?.epochSeconds ?: uiState.renderFrame?.epochSeconds
    val speedLabel = snapshot?.let { formatPlaybackRateLabel(it.simSecondsPerRealSecond, fallbackSpeedPreset) }
        ?: "packet time"
    val progress = runtimeMirrorRailProgress(epochSeconds)

    Surface(
        modifier = modifier,
        color = RuntimeMirrorGlass,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, RuntimeMirrorCyanDim.copy(alpha = 0.24f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = epochSeconds?.let { "MET ${formatRuntimeEpoch(it)}" } ?: "Awaiting mission clock",
                    color = RuntimeMirrorText,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = speedLabel,
                    color = RuntimeMirrorGold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
            ) {
                val railY = size.height * 0.58f
                val startX = 4.dp.toPx()
                val endX = size.width - 4.dp.toPx()
                val activeX = startX + (endX - startX) * progress
                drawLine(
                    color = RuntimeMirrorInkLine.copy(alpha = 0.86f),
                    start = Offset(startX, railY),
                    end = Offset(endX, railY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = RuntimeMirrorCyan.copy(alpha = 0.88f),
                    start = Offset(startX, railY),
                    end = Offset(activeX, railY),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                repeat(9) { index ->
                    val tickX = startX + (endX - startX) * (index / 8f)
                    val tickHeight = if (index % 2 == 0) 8.dp.toPx() else 5.dp.toPx()
                    drawLine(
                        color = RuntimeMirrorTextDim.copy(alpha = 0.42f),
                        start = Offset(tickX, railY - (tickHeight * 0.5f)),
                        end = Offset(tickX, railY + (tickHeight * 0.5f)),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                drawCircle(
                    color = RuntimeMirrorGold.copy(alpha = 0.26f),
                    radius = 8.dp.toPx(),
                    center = Offset(activeX, railY),
                )
                drawCircle(
                    color = RuntimeMirrorGold,
                    radius = 3.dp.toPx(),
                    center = Offset(activeX, railY),
                )
            }
        }
    }
}

@Composable
private fun RuntimeMirrorMiniSignalChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (values.isEmpty()) {
            return@Canvas
        }
        val left = 2.dp.toPx()
        val right = size.width - 2.dp.toPx()
        val top = 4.dp.toPx()
        val bottom = size.height - 4.dp.toPx()
        val baseline = bottom - (bottom - top) * 0.18f
        drawLine(
            color = RuntimeMirrorInkLine.copy(alpha = 0.72f),
            start = Offset(left, baseline),
            end = Offset(right, baseline),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        var previous: Offset? = null
        values.forEachIndexed { index, value ->
            val fraction = if (values.size == 1) 1f else index / values.lastIndex.toFloat()
            val current = Offset(
                x = left + (right - left) * fraction,
                y = bottom - (bottom - top) * value.coerceIn(0f, 1f),
            )
            previous?.let { last ->
                drawLine(
                    color = RuntimeMirrorCyan.copy(alpha = 0.30f),
                    start = last,
                    end = current,
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = RuntimeMirrorCyan.copy(alpha = 0.88f),
                    start = last,
                    end = current,
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            previous = current
        }
        previous?.let { head ->
            drawCircle(
                color = RuntimeMirrorGold.copy(alpha = 0.88f),
                radius = 2.4.dp.toPx(),
                center = head,
            )
        }
    }
}

@Composable
private fun RuntimeMirrorSearchDialog(
    bodies: List<RuntimeMirrorBody>,
    selectedBodyId: String?,
    onDismiss: () -> Unit,
    onSelectBody: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase(Locale.US)
    val filteredBodies = remember(bodies, normalizedQuery) {
        bodies
            .sortedWith(compareBy<RuntimeMirrorBody> { it.displayName.lowercase(Locale.US) }.thenBy { it.id.lowercase(Locale.US) })
            .filter { body ->
                normalizedQuery.isBlank() ||
                    body.displayName.lowercase(Locale.US).contains(normalizedQuery) ||
                    body.id.lowercase(Locale.US).contains(normalizedQuery)
            }
            .take(32)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Find a runtime body") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD),
                    label = { Text("Search by name or id") },
                    singleLine = true,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (filteredBodies.isEmpty()) {
                        Text(
                            text = "No runtime bodies matched that query.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    filteredBodies.forEach { body ->
                        val selected = body.id == selectedBodyId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                                    RoundedCornerShape(14.dp),
                                ),
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = body.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "${body.id} · ${formatDistance(body.positionM.magnitude())} from frame origin",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onSelectBody(body.id) }) {
                                    Text("Focus")
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun RuntimeMirrorDebugDialog(
    uiState: ShellUiState,
    hostRendererStatus: String,
    stepQuantumPreset: StepQuantumPreset,
    playbackSpeedPreset: PlaybackSpeedPreset,
    renderProcessingMode: RenderProcessingMode,
    observerMode: ObserverMode,
    selectedBodyId: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh) {
                    Text("Refresh")
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        title = { Text("Runtime mirror debug") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = buildRuntimeBackendStatus(uiState = uiState, hostRendererStatus = hostRendererStatus),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "Selected: ${selectedBodyId ?: "none"}\n${observerMode.runtimeDisplayLabel()}\nStep ${stepQuantumPreset.label} · Speed ${playbackSpeedPreset.label}\n${when (renderProcessingMode) { RenderProcessingMode.DEFAULT -> "Rendering: Standard"; RenderProcessingMode.LOW -> "Rendering: Simplified" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildRuntimeDiagnosticsText(
                        uiState = uiState,
                        hostRendererStatus = hostRendererStatus,
                        observerMode = observerMode,
                        renderProcessingMode = renderProcessingMode,
                        stepQuantumPreset = stepQuantumPreset,
                        playbackSpeedPreset = playbackSpeedPreset,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.developerTelemetry.enabled) {
                    Text(
                        text = uiState.developerTelemetry.toShareText(maxEntries = 8),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

private fun buildRuntimeTimelineText(
    uiState: ShellUiState,
    stepQuantumPreset: StepQuantumPreset,
    fallbackSpeedPreset: PlaybackSpeedPreset,
): String {
    val snapshot = uiState.snapshot ?: return "Runtime mirror\nWaiting for authoritative snapshot"
    val speedLabel = formatPlaybackRateLabel(snapshot.simSecondsPerRealSecond, fallbackSpeedPreset)
    return buildString {
        append("Runtime mirror | branch=")
        append(snapshot.activeBranchId)
        append('\n')
        append("Epoch ")
        append(formatRuntimeEpoch(snapshot.epochSeconds))
        append(" • Speed ")
        append(speedLabel)
        append(" • Step ")
        append(stepQuantumPreset.label)
    }
}

private fun buildRuntimeSelectionCard(
    uiState: ShellUiState,
    selectedBody: RuntimeMirrorBody?,
): RuntimeSelectionCard {
    if (selectedBody == null) {
        return RuntimeSelectionCard(
            title = if (uiState.connectionState == SessionConnectionState.Unavailable) {
                "Runtime mirror unavailable"
            } else {
                "No body selected"
            },
            detail = uiState.detailLine ?: "Tap a moving body to select it, then use Follow to keep the authoritative scene in view.",
        )
    }
    val hostLine = selectedBody.hostBodyId?.let { "Host ${displayNameForBodyId(it)}" }
    return RuntimeSelectionCard(
        title = "${selectedBody.displayName} · runtime body",
        detail = listOfNotNull(
            selectedBody.id,
            hostLine,
            "Radius ${formatDistance(selectedBody.radiusM)} • ${formatDistance(selectedBody.positionM.magnitude())} from frame origin",
        ).joinToString(separator = "\n"),
    )
}

private fun buildRuntimeBackendStatus(
    uiState: ShellUiState,
    hostRendererStatus: String,
): String {
    val connectionSummary = when (uiState.connectionState) {
        SessionConnectionState.Active -> "Runtime connected"
        SessionConnectionState.Connecting -> "Connecting to runtime"
        SessionConnectionState.Unavailable -> "Runtime unavailable"
    }
    val revision = uiState.renderStatus.sceneRevision ?: "waiting-for-packet"
    return listOfNotNull(
        connectionSummary,
        uiState.statusLine.takeIf(String::isNotBlank),
        "rev=$revision",
        hostRendererStatus.takeIf(String::isNotBlank),
    ).joinToString(separator = " · ")
}

private fun buildRuntimeDiagnosticsText(
    uiState: ShellUiState,
    hostRendererStatus: String,
    observerMode: ObserverMode,
    renderProcessingMode: RenderProcessingMode,
    stepQuantumPreset: StepQuantumPreset,
    playbackSpeedPreset: PlaybackSpeedPreset,
): String {
    val snapshot = uiState.snapshot
    val renderStatus = uiState.renderStatus
    return buildString {
        appendLine("connection=${uiState.connectionState}")
        appendLine("status=${uiState.statusLine}")
        uiState.detailLine?.let { appendLine("detail=$it") }
        uiState.noticeLine?.let { appendLine("notice=$it") }
        appendLine("observer=${observerMode.runtimeDisplayLabel()}")
        appendLine("rendering=${if (renderProcessingMode == RenderProcessingMode.DEFAULT) "standard" else "simplified"}")
        appendLine("stepQuantum=${stepQuantumPreset.label}")
        appendLine("playbackPreset=${playbackSpeedPreset.label}")
        appendLine("hostRenderer=$hostRendererStatus")
        snapshot?.let {
            appendLine("scenario=${it.scenarioId}")
            appendLine("branch=${it.activeBranchId}")
            appendLine("epochSeconds=${"%.3f".format(Locale.US, it.epochSeconds)}")
            appendLine("bodyCount=${it.bodyCount}")
            appendLine("paused=${it.paused}")
            appendLine("simSecondsPerRealSecond=${"%.3f".format(Locale.US, it.simSecondsPerRealSecond)}")
            appendLine("observerModeLabel=${it.observerModeLabel}")
            appendLine("timelineSemantics=${it.timelineSemanticsLabel}")
        }
        appendLine("readiness=${renderStatus.readiness}")
        appendLine("sceneRevision=${renderStatus.sceneRevision ?: "none"}")
        appendLine("renderedBodies=${renderStatus.renderedBodyCount}")
        appendLine("renderedTracers=${renderStatus.renderedTracerCount}")
        appendLine("renderedTrails=${renderStatus.renderedTrailCount}")
        appendLine("directionalLights=${renderStatus.directionalLightCount}")
        renderStatus.summary?.let { appendLine("summary=$it") }
        renderStatus.issue?.let { appendLine("issue=$it") }
        renderStatus.provenanceSource?.let { appendLine("provenanceSource=$it") }
        renderStatus.provenanceVersion?.let { appendLine("provenanceVersion=$it") }
        renderStatus.provenanceManifestId?.let { appendLine("provenanceManifestId=$it") }
        renderStatus.provenancePackageDigest?.let { appendLine("provenancePackageDigest=$it") }
        renderStatus.diagnosticsFrameNumber?.let { appendLine("renderFrame=${it}") }
        renderStatus.diagnosticsCpuExtractMs?.let { appendLine("cpuExtractMs=${"%.3f".format(Locale.US, it)}") }
        renderStatus.diagnosticsGpuUploadMs?.let { appendLine("gpuUploadMs=${"%.3f".format(Locale.US, it)}") }
        append("droppedFrames=${renderStatus.diagnosticsDroppedFrames}")
    }
}

private fun RenderFrame.toRuntimeMirrorScene(): RuntimeMirrorScene {
    val origin = Vector3d(
        x = camera.frameOriginX,
        y = camera.frameOriginY,
        z = camera.frameOriginZ,
    )
    val searchableBodies = bodies.map { body ->
        val kind = inferRenderBodyKind(body.bodyId, body.radiusM.toDouble())
        RuntimeMirrorBody(
            id = body.bodyId,
            displayName = displayNameForBodyId(body.bodyId),
            positionM = origin + Vector3d(body.x.toDouble(), body.y.toDouble(), body.z.toDouble()),
            radiusM = body.radiusM.toDouble(),
            colorArgb = argbFrom(body.colorR, body.colorG, body.colorB, body.colorA),
            kind = kind,
            hostBodyId = inferRuntimeHostBodyId(body.bodyId),
        )
    }
    val authoritativeBodies = searchableBodies.map { body ->
        StageRenderBody(
            id = body.id,
            name = body.displayName,
            positionM = body.positionM,
            radiusM = max(body.radiusM, 1.0),
            colorArgb = body.colorArgb,
            kind = body.kind,
            isMassive = true,
            hostBodyId = body.hostBodyId,
        )
    }
    val tracerBodies = tracers.mapIndexed { index, tracer ->
        StageRenderBody(
            id = "runtime-tracer-$index",
            name = "Tracer ${index + 1}",
            positionM = origin + Vector3d(tracer.x.toDouble(), tracer.y.toDouble(), tracer.z.toDouble()),
            radiusM = max(150_000.0, tracer.sizePx.toDouble() * 180_000.0),
            colorArgb = argbFrom(tracer.colorR, tracer.colorG, tracer.colorB, tracer.colorA),
            kind = RenderBodyKind.TEST_OBJECT,
            isMassive = false,
            hostBodyId = null,
        )
    }
    val trails = trails.map { trail ->
        StageRenderTrail(
            bodyId = trail.sourceBodyId,
            colorArgb = argbFrom(trail.colorR, trail.colorG, trail.colorB, trail.colorA),
            alpha = trail.colorA.coerceIn(0f, 1f),
            pointsM = trail.points.map { point ->
                origin + Vector3d(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
            },
        )
    }
    val revisionSeed = sceneRevision.hashCode().toLong() xor epochSeconds.toBits()
    val stableRevision = if (revisionSeed == Long.MIN_VALUE) 0L else abs(revisionSeed)
    return RuntimeMirrorScene(
        scene = RenderSceneFrame(
            epochSeconds = epochSeconds,
            authoritativeBodies = authoritativeBodies,
            tracerBodies = tracerBodies,
            trails = trails,
            sourceRevision = stableRevision,
        ),
        searchableBodies = searchableBodies,
    )
}

private fun displayNameForBodyId(bodyId: String): String {
    return SolarLabTeachingCatalog.entries
        .firstOrNull { entry -> entry.bodyId.equals(bodyId, ignoreCase = true) }
        ?.displayName
        ?: bodyId.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(Locale.US) else character.toString()
        }
}

private fun inferRenderBodyKind(
    bodyId: String,
    radiusM: Double,
): RenderBodyKind {
    val catalogEntry = SolarLabTeachingCatalog.entries.firstOrNull { entry ->
        entry.bodyId.equals(bodyId, ignoreCase = true)
    }
    return when (catalogEntry?.spawnBodyClass) {
        com.sednalabs.solarlab.runtime.RuntimeBodyClass.Star -> RenderBodyKind.STAR
        com.sednalabs.solarlab.runtime.RuntimeBodyClass.Planet,
        com.sednalabs.solarlab.runtime.RuntimeBodyClass.Moon,
        -> RenderBodyKind.PLANET

        com.sednalabs.solarlab.runtime.RuntimeBodyClass.DwarfPlanet -> RenderBodyKind.DWARF_PLANET
        com.sednalabs.solarlab.runtime.RuntimeBodyClass.SmallBody -> if (bodyId.equals("halley", ignoreCase = true)) {
            RenderBodyKind.COMET
        } else {
            RenderBodyKind.ASTEROID
        }

        com.sednalabs.solarlab.runtime.RuntimeBodyClass.Spacecraft -> RenderBodyKind.PROBE
        com.sednalabs.solarlab.runtime.RuntimeBodyClass.Tracer,
        com.sednalabs.solarlab.runtime.RuntimeBodyClass.Custom,
        null,
        -> when {
            bodyId.equals("sun", ignoreCase = true) -> RenderBodyKind.STAR
            radiusM >= 1.0e7 -> RenderBodyKind.PLANET
            radiusM >= 7.5e5 -> RenderBodyKind.DWARF_PLANET
            else -> RenderBodyKind.TEST_OBJECT
        }
    }
}

private fun inferRuntimeHostBodyId(bodyId: String): String? = when (bodyId.lowercase(Locale.US)) {
    "moon" -> "earth"
    "phobos", "deimos" -> "mars"
    "io", "europa", "ganymede", "callisto" -> "jupiter"
    "titan", "enceladus", "rhea", "dione", "iapetus", "mimas", "tethys" -> "saturn"
    else -> null
}

private fun resolveRuntimeSemanticBodyId(
    bodies: List<RuntimeMirrorBody>,
    bodyQuery: String,
): String? {
    val normalizedQuery = bodyQuery.trim().lowercase(Locale.US)
    if (normalizedQuery.isEmpty()) {
        return null
    }
    return bodies.firstOrNull { body ->
        body.id.lowercase(Locale.US) == normalizedQuery ||
            body.displayName.lowercase(Locale.US) == normalizedQuery
    }?.id
}

private fun argbFrom(
    red: Float,
    green: Float,
    blue: Float,
    alpha: Float,
): Int {
    fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    return (channel(alpha) shl 24) or
        (channel(red) shl 16) or
        (channel(green) shl 8) or
        channel(blue)
}

private fun ObserverMode.toRuntimeObserverMode(): RuntimeObserverMode = when (this) {
    ObserverMode.FREE -> RuntimeObserverMode.Free
    ObserverMode.FOLLOW_SELECTED -> RuntimeObserverMode.FollowSelected
    ObserverMode.FOLLOW_SELECTED_HOST -> RuntimeObserverMode.FollowHost
}

private fun ObserverMode.runtimeDisplayLabel(): String = when (this) {
    ObserverMode.FREE -> "Observer: Free"
    ObserverMode.FOLLOW_SELECTED -> "Observer: Follow selected"
    ObserverMode.FOLLOW_SELECTED_HOST -> "Observer: Follow selected host"
}

private fun observerModeFromRuntimeCode(code: Int?): ObserverMode = when (code) {
    RuntimeObserverMode.FollowSelected.nativeCode -> ObserverMode.FOLLOW_SELECTED
    RuntimeObserverMode.FollowHost.nativeCode -> ObserverMode.FOLLOW_SELECTED_HOST
    else -> ObserverMode.FREE
}

private fun StepQuantumPreset.shifted(direction: Int): StepQuantumPreset {
    val entries = StepQuantumPreset.entries
    val index = entries.indexOf(this).coerceAtLeast(0)
    return entries[(index + direction).coerceIn(0, entries.lastIndex)]
}

private fun PlaybackSpeedPreset.shifted(direction: Int): PlaybackSpeedPreset {
    val entries = PlaybackSpeedPreset.entries
    val index = entries.indexOf(this).coerceAtLeast(0)
    return entries[(index + direction).coerceIn(0, entries.lastIndex)]
}

private fun nearestPlaybackSpeedPreset(simSecondsPerRealSecond: Double): PlaybackSpeedPreset {
    return PlaybackSpeedPreset.entries.minByOrNull { preset ->
        abs(preset.simSecondsPerRealSecond - simSecondsPerRealSecond)
    } ?: PlaybackSpeedPreset.SIX_HOURS_PER_SECOND
}

private fun runtimeMirrorRailProgress(epochSeconds: Double?): Float {
    if (epochSeconds == null) {
        return 0.08f
    }
    val normalized = ((epochSeconds % RUNTIME_MIRROR_MISSION_DAY_SECONDS) + RUNTIME_MIRROR_MISSION_DAY_SECONDS) %
        RUNTIME_MIRROR_MISSION_DAY_SECONDS
    return (normalized / RUNTIME_MIRROR_MISSION_DAY_SECONDS).toFloat().coerceIn(0.04f, 0.96f)
}

private fun runtimeMirrorSignalValues(
    uiState: ShellUiState,
    selectedBody: RuntimeMirrorBody?,
): List<Float> {
    val epochProgress = runtimeMirrorRailProgress(uiState.snapshot?.epochSeconds ?: uiState.renderFrame?.epochSeconds)
    val bodySignal = (uiState.renderStatus.renderedBodyCount / RUNTIME_MIRROR_SIGNAL_BODY_NORMALIZATION)
        .coerceIn(0.08f, 0.92f)
    val trailSignal = (uiState.renderStatus.renderedTrailCount / RUNTIME_MIRROR_SIGNAL_TRAIL_NORMALIZATION)
        .coerceIn(0.10f, 0.90f)
    val focusLift = if (uiState.focusedBodyId != null) RUNTIME_MIRROR_SIGNAL_FOCUS_LIFT else 0f
    val selectedLift = if (selectedBody != null) RUNTIME_MIRROR_SIGNAL_SELECTED_LIFT else 0f

    return listOf(
        RUNTIME_MIRROR_SIGNAL_L1_BASE + (bodySignal * RUNTIME_MIRROR_SIGNAL_L1_BODY_COEFF),
        RUNTIME_MIRROR_SIGNAL_L2_BASE + (selectedLift * RUNTIME_MIRROR_SIGNAL_L2_SELECTED_COEFF),
        RUNTIME_MIRROR_SIGNAL_L3_BASE + (trailSignal * RUNTIME_MIRROR_SIGNAL_L3_TRAIL_COEFF),
        RUNTIME_MIRROR_SIGNAL_L4_BASE + (epochProgress * RUNTIME_MIRROR_SIGNAL_L4_TIME_COEFF),
        RUNTIME_MIRROR_SIGNAL_L5_BASE + (bodySignal * RUNTIME_MIRROR_SIGNAL_L5_BODY_COEFF) + (trailSignal * RUNTIME_MIRROR_SIGNAL_L5_TRAIL_COEFF),
        RUNTIME_MIRROR_SIGNAL_L6_BASE + (focusLift * RUNTIME_MIRROR_SIGNAL_L6_FOCUS_COEFF) + selectedLift,
    ).map { value -> value.coerceIn(0.05f, 0.95f) }
}

private fun formatPlaybackRateLabel(
    simSecondsPerRealSecond: Double,
    fallbackSpeedPreset: PlaybackSpeedPreset,
): String {
    val nearest = nearestPlaybackSpeedPreset(simSecondsPerRealSecond)
    val relativeError = if (simSecondsPerRealSecond == 0.0) {
        0.0
    } else {
        abs(nearest.simSecondsPerRealSecond - simSecondsPerRealSecond) / simSecondsPerRealSecond
    }
    return if (relativeError <= 0.03) {
        nearest.label
    } else {
        fallbackSpeedPreset.label + " (~${"%.2f".format(Locale.US, simSecondsPerRealSecond)} sim s/real s)"
    }
}

private fun formatRuntimeEpoch(epochSeconds: Double): String = when {
    abs(epochSeconds) >= 86_400.0 -> "${"%.2f".format(Locale.US, epochSeconds / 86_400.0)} d"
    abs(epochSeconds) >= 3_600.0 -> "${"%.2f".format(Locale.US, epochSeconds / 3_600.0)} h"
    else -> "${"%.0f".format(Locale.US, epochSeconds)} s"
}
