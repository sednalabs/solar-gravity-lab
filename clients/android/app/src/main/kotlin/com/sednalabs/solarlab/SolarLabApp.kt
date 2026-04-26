package com.sednalabs.solarlab

import android.content.Intent
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sednalabs.solarlab.runtime.DEVELOPER_TELEMETRY_LOG_TAG
import com.sednalabs.solarlab.runtime.DeveloperTelemetryEvent
import com.sednalabs.solarlab.runtime.DeveloperTelemetryPresentation
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeBodyClass
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.RuntimeObserverMode
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellNoticeTone
import com.sednalabs.solarlab.runtime.ShellUiState
import com.sednalabs.solarlab.runtime.SnapshotPresentation
import com.sednalabs.solarlab.runtime.developerTelemetryStreamingTargetLabel
import com.sednalabs.solarlab.runtime.toDisplayLine
import com.sednalabs.solarlab.runtime.toShareText
import com.sednalabs.solarlab.ui.theme.SolarLabTheme
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sqrt

private val MissionVoid = Color(0xFF02050B)
private val MissionNight = Color(0xFF07101C)
private val MissionGlass = Color(0xE6070D18)
private val MissionGlassSoft = Color(0xB40B1424)
private val MissionCyan = Color(0xFF76F7FF)
private val MissionCyanDim = Color(0xFF2A9DAC)
private val MissionGold = Color(0xFFFFD36B)
private val MissionBlue = Color(0xFF5E8CFF)
private val MissionInkLine = Color(0xFF19324B)
private val MissionText = Color(0xFFE8F7FF)
private val MissionTextDim = Color(0xFF9FB6C9)

@Composable
fun SolarLabApp(runtimeFacade: RuntimeFacade) {
    val uiState by runtimeFacade.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val onRefresh: () -> Unit = {
        scope.launch {
            runtimeFacade.refresh()
        }
    }
    val onFocusBodyRequested: (String?) -> Unit = { bodyId ->
        scope.launch {
            runtimeFacade.applyCommand(RuntimeCommand.FocusBody(bodyId))
        }
    }
    val onCommand: (RuntimeCommand) -> Unit = { command ->
        scope.launch {
            runtimeFacade.applyCommand(command)
        }
    }

    SolarLabTheme {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MissionVoid,
                                Color(0xFF050B15),
                                MissionNight,
                                Color(0xFF0B1829),
                            )
                        )
                    )
            ) {
                OrbitalBackdrop()
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val isWide = maxWidth >= 920.dp
                    val canSendCommands = uiState.connectionState == SessionConnectionState.Active &&
                        uiState.pendingActionLabel == null
                    val canRefresh = canSendCommands

                    if (isWide) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SolarLabTestTags.SHELL_COLUMN)
                                .widthIn(max = 1320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            HeroPanel(
                                uiState = uiState,
                                compact = false,
                                onRefresh = onRefresh,
                                canRefresh = canRefresh,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                RenderStagePanel(
                                    uiState = uiState,
                                    modifier = Modifier.weight(1.35f),
                                    compactStage = false,
                                    compactStageHeight = 520.dp,
                                    onRefresh = onRefresh,
                                    onFocusBodyRequested = onFocusBodyRequested,
                                    canRefresh = canRefresh,
                                )
                                Column(
                                    modifier = Modifier.weight(0.92f),
                                    verticalArrangement = Arrangement.spacedBy(18.dp),
                                ) {
                                    ControlDeck(
                                        uiState = uiState,
                                        enabled = canSendCommands,
                                        onRefresh = onRefresh,
                                        onCommand = onCommand,
                                    )
                                    RuntimeDetailPanel(uiState = uiState)
                                }
                            }
                        }
                    } else {
                        ImmersiveStageShell(
                            uiState = uiState,
                            canSendCommands = canSendCommands,
                            canRefresh = canRefresh,
                            onRefresh = onRefresh,
                            onCommand = onCommand,
                            onFocusBodyRequested = onFocusBodyRequested,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun ImmersiveStageShell(
    uiState: ShellUiState,
    canSendCommands: Boolean,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
    onCommand: (RuntimeCommand) -> Unit,
    onFocusBodyRequested: (String?) -> Unit,
) {
    var showOverlay by rememberSaveable { mutableStateOf(false) }
    var stageCameraModeName by rememberSaveable {
        mutableStateOf(VulkanPacketRenderSurfaceView.CameraPresentationMode.Cinematic.name)
    }
    var showTrackedOrbits by rememberSaveable { mutableStateOf(false) }
    var trackedOrbitLimit by rememberSaveable { mutableStateOf(5) }
    var showForecastPaths by rememberSaveable { mutableStateOf(false) }
    var renderSurfaceView by remember { mutableStateOf<VulkanPacketRenderSurfaceView?>(null) }
    val stageCameraMode = VulkanPacketRenderSurfaceView.CameraPresentationMode.entries
        .firstOrNull { mode -> mode.name == stageCameraModeName }
        ?: VulkanPacketRenderSurfaceView.CameraPresentationMode.Cinematic
    val quickFocusEntries = SolarLabTeachingCatalog.entries.filter { entry ->
        entry.bodyId in setOf("sun", "earth", "moon", "mars", "jupiter") &&
            uiState.renderFrame?.bodies?.any { body -> body.bodyId == entry.bodyId } == true
    }
    val forecastSourceBodyIds = uiState.forecastSourceBodyIds
    val focusedBodyHero = deriveFocusedBodyHeroModel(
        uiState = uiState,
        stageCameraMode = stageCameraMode,
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SolarLabTestTags.IMMERSIVE_STAGE_ROOT),
    ) {
        val overlayMaxHeight = (maxHeight * 0.74f).coerceIn(440.dp, 820.dp)
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(SolarLabTestTags.RENDER_PANEL)
                    .clip(RoundedCornerShape(34.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MissionVoid,
                                Color(0xFF06101E),
                                Color(0xFF0A1A2C),
                            )
                        )
                    ),
            ) {
                if (uiState.renderFrame != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            VulkanPacketRenderSurfaceView(context = context).also { view ->
                                renderSurfaceView = view
                            }
                        },
                        update = { view ->
                            renderSurfaceView = view
                            view.setCameraPresentationMode(stageCameraMode)
                            view.setOnBodyTapped { bodyId ->
                                onFocusBodyRequested(bodyId)
                            }
                            view.submitFrame(
                                frame = uiState.renderFrame,
                                historicalTrailSourceBodyIds = if (showTrackedOrbits) {
                                    uiState.recentFocusedBodyIds.take(trackedOrbitLimit)
                                } else {
                                    emptyList()
                                },
                                showHistoricalTrails = showTrackedOrbits,
                                showForecastOverlay = showForecastPaths,
                                forecastTrailSourceBodyIds = forecastSourceBodyIds,
                            )
                        },
                    )
                } else {
                    EmptyRenderStage(
                        uiState = uiState,
                        onRefresh = onRefresh,
                        canRefresh = canRefresh,
                    )
                }
            }

            if (!showOverlay &&
                (
                    uiState.renderFrame == null ||
                        uiState.renderStatus.readiness != RenderHostReadiness.Ready ||
                        uiState.renderStatus.issue != null
                    )
            ) {
                ImmersiveStageHud(
                    uiState = uiState,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(18.dp),
                )
            }

            if (!showOverlay && uiState.renderFrame != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 18.dp, top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MissionStageTelemetryCard(
                        uiState = uiState,
                        modifier = Modifier.widthIn(max = 300.dp),
                    )
                    focusedBodyHero?.let { model ->
                        FocusedBodyHeroCard(
                            model = model,
                        )
                    }
                }
                MissionTimelineRail(
                    uiState = uiState,
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 18.dp, vertical = 84.dp),
                )
                StageCameraModeDock(
                    selectedCameraMode = stageCameraMode,
                    onCameraModeSelected = { mode ->
                        stageCameraModeName = mode.name
                        renderSurfaceView?.setCameraPresentationMode(mode)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                )
            }

            if (showOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x9E040711))
                        .pointerInteropFilter { motionEvent ->
                            if (motionEvent.actionMasked == MotionEvent.ACTION_UP) {
                                showOverlay = false
                            }
                            true
                        },
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(max = overlayMaxHeight)
                        .testTag(SolarLabTestTags.OVERLAY_PANEL),
                    shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                    ),
                    shadowElevation = 20.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 18.dp)
                            .testTag(SolarLabTestTags.SHELL_COLUMN),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        HeroPanel(
                            uiState = uiState,
                            compact = true,
                            onRefresh = onRefresh,
                            canRefresh = canRefresh,
                        )
                        if (uiState.renderFrame != null) {
                            StageInteractionDock(
                                focusedBodyId = uiState.focusedBodyId,
                                quickFocusEntries = quickFocusEntries,
                                onFocusBodyRequested = onFocusBodyRequested,
                                onZoomIn = { renderSurfaceView?.zoomBy(1.35f) },
                                onZoomOut = { renderSurfaceView?.zoomBy(0.74f) },
                                onResetView = { renderSurfaceView?.resetViewTransform() },
                                selectedCameraMode = stageCameraMode,
                                onCameraModeSelected = { mode ->
                                    stageCameraModeName = mode.name
                                    renderSurfaceView?.setCameraPresentationMode(mode)
                                },
                                showForecastPaths = showForecastPaths,
                                onShowForecastPathsChange = { showForecastPaths = it },
                            )
                            TrackedOrbitHistoryPanel(
                                trackedBodyIds = uiState.recentFocusedBodyIds.take(trackedOrbitLimit),
                                showTrackedOrbits = showTrackedOrbits,
                                trackedOrbitLimit = trackedOrbitLimit,
                                onShowTrackedOrbitsChange = { showTrackedOrbits = it },
                                onTrackedOrbitLimitChange = { trackedOrbitLimit = it },
                                showForecastPaths = showForecastPaths,
                                forecastSourceBodyIds = forecastSourceBodyIds,
                            )
                            RenderStageSummaryCard(uiState = uiState)
                        }
                        ControlDeck(
                            uiState = uiState,
                            enabled = canSendCommands,
                            onRefresh = onRefresh,
                            onCommand = onCommand,
                        )
                        RuntimeDetailPanel(uiState = uiState)
                    }
                }
            }

            FilledTonalButton(
                onClick = { showOverlay = !showOverlay },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
                    .testTag(SolarLabTestTags.OVERLAY_TOGGLE_BUTTON),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(
                        alpha = if (showOverlay) 0.92f else 0.68f
                    ),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(if (showOverlay) "Hide controls" else "Controls")
            }
        }
    }
}

@Composable
private fun ImmersiveStageHud(
    uiState: ShellUiState,
    modifier: Modifier = Modifier,
) {
    val readinessLabel = when (uiState.renderStatus.readiness) {
        RenderHostReadiness.WaitingForSession -> "Waiting for first frame"
        RenderHostReadiness.Refreshing -> "Refreshing scene"
        RenderHostReadiness.Ready -> "Solar system live"
        RenderHostReadiness.Unavailable -> "Render export degraded"
        RenderHostReadiness.Failed -> "Render host failed"
    }
    val supportingLine = uiState.focusedBodyId?.let { "Focused on $it" }
        ?: uiState.renderStatus.summary
        ?: uiState.statusLine

    Surface(
        modifier = modifier.widthIn(max = 260.dp),
        shape = RoundedCornerShape(22.dp),
        color = MissionGlass,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.28f)),
        shadowElevation = 14.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = readinessLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MissionCyan,
            )
            Text(
                text = "Stage-first solar system",
                style = MaterialTheme.typography.titleMedium,
                color = MissionText,
            )
            supportingLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MissionTextDim,
                )
            }
        }
    }
}

@Composable
private fun OrbitalBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = MissionCyanDim.copy(alpha = 0.16f),
            radius = size.minDimension * 0.42f,
            center = Offset(size.width * 0.10f, size.height * 0.08f),
        )
        drawCircle(
            color = MissionBlue.copy(alpha = 0.10f),
            radius = size.minDimension * 0.32f,
            center = Offset(size.width * 0.86f, size.height * 0.18f),
        )
        drawCircle(
            color = MissionGold.copy(alpha = 0.07f),
            radius = size.minDimension * 0.48f,
            center = Offset(size.width * 0.78f, size.height * 0.88f),
        )

        val orbitCenter = Offset(size.width * 0.74f, size.height * 0.70f)
        val orbitStroke = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(
            color = MissionCyan.copy(alpha = 0.10f),
            radius = size.minDimension * 0.30f,
            center = orbitCenter,
            style = orbitStroke,
        )
        drawCircle(
            color = MissionCyanDim.copy(alpha = 0.12f),
            radius = size.minDimension * 0.18f,
            center = orbitCenter,
            style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color = MissionGold.copy(alpha = 0.16f),
            startAngle = 214f,
            sweepAngle = 64f,
            useCenter = false,
            topLeft = Offset(
                orbitCenter.x - size.minDimension * 0.30f,
                orbitCenter.y - size.minDimension * 0.30f,
            ),
            size = Size(size.minDimension * 0.60f, size.minDimension * 0.60f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )

        repeat(16) { index ->
            val fraction = index / 15f
            drawLine(
                color = MissionInkLine.copy(alpha = 0.32f),
                start = Offset(size.width * fraction, 0f),
                end = Offset(size.width * (fraction + 0.10f), size.height * 0.46f),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        repeat(30) { index ->
            val x = size.width * ((index * 37 % 97) / 97f)
            val y = size.height * ((index * 53 % 89) / 89f)
            val alpha = if (index % 5 == 0) 0.34f else 0.16f
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = if (index % 7 == 0) 1.5.dp.toPx() else 0.8.dp.toPx(),
                center = Offset(x, y),
            )
        }
    }
}

@Composable
private fun HeroPanel(
    uiState: ShellUiState,
    compact: Boolean,
    onRefresh: () -> Unit,
    canRefresh: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 26.dp else 32.dp),
        color = MissionGlass,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.24f)),
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MissionCyanDim.copy(alpha = 0.18f),
                            MissionGlass,
                            MissionGold.copy(alpha = 0.07f),
                        ),
                    )
                )
                .padding(if (compact) 18.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BoxWithConstraints {
                val stackedHeader = maxWidth < 560.dp
                if (stackedHeader) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        HeroCopy(compact = compact)
                        FilledTonalButton(
                            onClick = onRefresh,
                            enabled = canRefresh,
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MissionCyan.copy(alpha = 0.18f),
                                contentColor = MissionText,
                            ),
                        ) {
                            Text("Refresh snapshot")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            HeroCopy(compact = compact)
                        }
                        FilledTonalButton(
                            onClick = onRefresh,
                            enabled = canRefresh,
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MissionCyan.copy(alpha = 0.18f),
                                contentColor = MissionText,
                            ),
                        ) {
                            Text("Refresh snapshot")
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = uiState.statusLine,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MissionText,
                    modifier = Modifier.testTag(SolarLabTestTags.STATUS_LINE),
                )
                uiState.detailLine?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MissionTextDim,
                        modifier = Modifier.testTag(SolarLabTestTags.DETAIL_LINE),
                    )
                }
            }

            StatusStrip(uiState = uiState)
        }
    }
}

@Composable
private fun HeroCopy(compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (compact) "Trajectory workbench" else "Live trajectory workbench",
            style = MaterialTheme.typography.labelLarge,
            color = MissionCyan,
        )
        Text(
            text = "Solar Gravity Lab",
            style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
            color = MissionText,
            modifier = Modifier.testTag(SolarLabTestTags.TITLE),
        )
        Text(
            text = if (compact) {
                "A touch-first stage for reading live gravity, focus changes, and orbit traces at a glance."
            } else {
                "Packet-backed telemetry, focus tools, and orbital controls arranged like a compact flight dynamics console."
            },
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = MissionTextDim,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusStrip(uiState: ShellUiState) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusPill(
            label = when (uiState.connectionState) {
                SessionConnectionState.Connecting -> "Runtime connecting"
                SessionConnectionState.Active -> "Runtime connected"
                SessionConnectionState.Unavailable -> "Runtime unavailable"
            },
            tone = when (uiState.connectionState) {
                SessionConnectionState.Connecting -> ShellNoticeTone.Neutral
                SessionConnectionState.Active -> ShellNoticeTone.Positive
                SessionConnectionState.Unavailable -> ShellNoticeTone.Critical
            },
        )
        StatusPill(
            label = when (uiState.renderStatus.readiness) {
                RenderHostReadiness.WaitingForSession -> "Render host waiting"
                RenderHostReadiness.Refreshing -> "Render host refreshing"
                RenderHostReadiness.Ready -> "Render host ready"
                RenderHostReadiness.Unavailable -> "Render host degraded"
                RenderHostReadiness.Failed -> "Render host failed"
            },
            tone = when (uiState.renderStatus.readiness) {
                RenderHostReadiness.Ready -> ShellNoticeTone.Positive
                RenderHostReadiness.Failed -> ShellNoticeTone.Critical
                RenderHostReadiness.Unavailable -> ShellNoticeTone.Caution
                RenderHostReadiness.WaitingForSession,
                RenderHostReadiness.Refreshing,
                -> ShellNoticeTone.Neutral
            },
        )
        uiState.pendingActionLabel?.let { label ->
            StatusPill(
                label = label,
                tone = ShellNoticeTone.Neutral,
            )
        }
        uiState.snapshot?.let { snapshot ->
            StatusPill(
                label = if (snapshot.paused) "Playback paused" else "Playback live",
                tone = if (snapshot.paused) ShellNoticeTone.Caution else ShellNoticeTone.Positive,
            )
        }
        uiState.sessionHandle?.let { sessionHandle ->
            StatusPill(
                label = "Session $sessionHandle",
                tone = ShellNoticeTone.Neutral,
                modifier = Modifier.testTag(SolarLabTestTags.SESSION_HANDLE),
            )
        }
        uiState.backendSummary?.let { backend ->
            StatusPill(
                label = backend,
                tone = ShellNoticeTone.Neutral,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun RenderStagePanel(
    uiState: ShellUiState,
    modifier: Modifier = Modifier,
    compactStage: Boolean = false,
    compactStageHeight: Dp = 620.dp,
    onRefresh: () -> Unit,
    onFocusBodyRequested: (String?) -> Unit,
    canRefresh: Boolean,
) {
    var stageCameraModeName by rememberSaveable {
        mutableStateOf(VulkanPacketRenderSurfaceView.CameraPresentationMode.Cinematic.name)
    }
    var showTrackedOrbits by rememberSaveable { mutableStateOf(false) }
    var trackedOrbitLimit by rememberSaveable { mutableStateOf(5) }
    var showForecastPaths by rememberSaveable { mutableStateOf(false) }
    var renderSurfaceView by remember { mutableStateOf<VulkanPacketRenderSurfaceView?>(null) }
    val stageCameraMode = VulkanPacketRenderSurfaceView.CameraPresentationMode.entries
        .firstOrNull { mode -> mode.name == stageCameraModeName }
        ?: VulkanPacketRenderSurfaceView.CameraPresentationMode.Cinematic
    val quickFocusEntries = SolarLabTeachingCatalog.entries.filter { entry ->
        entry.bodyId in setOf("sun", "earth", "moon", "mars", "jupiter") &&
            uiState.renderFrame?.bodies?.any { body -> body.bodyId == entry.bodyId } == true
    }
    val forecastSourceBodyIds = uiState.forecastSourceBodyIds

    LabPanel(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Trajectory stage",
                        style = MaterialTheme.typography.titleLarge,
                        color = MissionText,
                    )
                    Text(
                        text = if (compactStage) {
                            "Live packet-backed orbit view with focus telemetry and touch controls."
                        } else {
                            "Live packet-backed orbit view with mission-clock telemetry and path overlays."
                        },
                        style = if (compactStage) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MissionTextDim,
                    )
                    Text(
                        text = if (compactStage) {
                            "Tap to focus, pinch to zoom, drag to pan, double-tap to reset."
                        } else {
                            "Pinch to zoom, drag to pan, tap a body to focus, double-tap to reset the view."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MissionCyan,
                    )
                }
                StatusPill(
                    label = when (uiState.renderStatus.readiness) {
                        RenderHostReadiness.WaitingForSession -> "Waiting"
                        RenderHostReadiness.Refreshing -> "Refreshing"
                        RenderHostReadiness.Ready -> "Ready"
                        RenderHostReadiness.Unavailable -> "Degraded"
                        RenderHostReadiness.Failed -> "Failed"
                    },
                    tone = when (uiState.renderStatus.readiness) {
                        RenderHostReadiness.Ready -> ShellNoticeTone.Positive
                        RenderHostReadiness.Unavailable -> ShellNoticeTone.Caution
                        RenderHostReadiness.Failed -> ShellNoticeTone.Critical
                        RenderHostReadiness.WaitingForSession,
                        RenderHostReadiness.Refreshing,
                        -> ShellNoticeTone.Neutral
                    },
                )
            }

            Box(
                modifier = Modifier
                    .testTag(SolarLabTestTags.RENDER_PANEL)
                    .fillMaxWidth()
                    .height(if (compactStage) compactStageHeight else 520.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MissionVoid,
                                Color(0xFF06101E),
                                Color(0xFF0D1E31),
                            )
                        )
                    ),
            ) {
                if (uiState.renderFrame != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            VulkanPacketRenderSurfaceView(context = context).also { view ->
                                renderSurfaceView = view
                            }
                        },
                        update = { view ->
                            renderSurfaceView = view
                            view.setCameraPresentationMode(stageCameraMode)
                            view.setOnBodyTapped { bodyId ->
                                onFocusBodyRequested(bodyId)
                            }
                            view.submitFrame(
                                frame = uiState.renderFrame,
                                historicalTrailSourceBodyIds = uiState.recentFocusedBodyIds,
                                showHistoricalTrails = showTrackedOrbits,
                                showForecastOverlay = showForecastPaths,
                                forecastTrailSourceBodyIds = forecastSourceBodyIds,
                            )
                        },
                    )

                    MissionStageTelemetryCard(
                        uiState = uiState,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                    )
                    MissionTimelineRail(
                        uiState = uiState,
                        compact = compactStage,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    )

                    if (!compactStage) {
                        TrackedOrbitHistoryPanel(
                            trackedBodyIds = uiState.recentFocusedBodyIds.take(trackedOrbitLimit),
                            showTrackedOrbits = showTrackedOrbits,
                            trackedOrbitLimit = trackedOrbitLimit,
                            onShowTrackedOrbitsChange = { showTrackedOrbits = it },
                            onTrackedOrbitLimitChange = { trackedOrbitLimit = it },
                            showForecastPaths = showForecastPaths,
                            forecastSourceBodyIds = forecastSourceBodyIds,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp),
                        )
                    }
                } else {
                    EmptyRenderStage(
                        uiState = uiState,
                        onRefresh = onRefresh,
                        canRefresh = canRefresh,
                    )
                }
            }

            if (uiState.renderFrame != null && !compactStage) {
                RenderStageSummaryCard(uiState = uiState)
            }

            if (uiState.renderFrame != null && compactStage) {
                StageInteractionDock(
                    focusedBodyId = uiState.focusedBodyId,
                    quickFocusEntries = quickFocusEntries,
                    onFocusBodyRequested = onFocusBodyRequested,
                    onZoomIn = { renderSurfaceView?.zoomBy(1.35f) },
                    onZoomOut = { renderSurfaceView?.zoomBy(0.74f) },
                    onResetView = { renderSurfaceView?.resetViewTransform() },
                    selectedCameraMode = stageCameraMode,
                    onCameraModeSelected = { mode ->
                        stageCameraModeName = mode.name
                        renderSurfaceView?.setCameraPresentationMode(mode)
                    },
                    showForecastPaths = showForecastPaths,
                    onShowForecastPathsChange = { showForecastPaths = it },
                )
                TrackedOrbitHistoryPanel(
                    trackedBodyIds = uiState.recentFocusedBodyIds.take(trackedOrbitLimit),
                    showTrackedOrbits = showTrackedOrbits,
                    trackedOrbitLimit = trackedOrbitLimit,
                    onShowTrackedOrbitsChange = { showTrackedOrbits = it },
                    onTrackedOrbitLimitChange = { trackedOrbitLimit = it },
                    showForecastPaths = showForecastPaths,
                    forecastSourceBodyIds = forecastSourceBodyIds,
                )
                RenderStageSummaryCard(uiState = uiState)
            }
        }
    }
}

@Composable
private fun RenderStageSummaryCard(
    uiState: ShellUiState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MissionGlassSoft,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = uiState.renderStatus.sceneRevision ?: uiState.renderFrame?.sceneRevision.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MissionCyan,
            )
            Text(
                text = "${uiState.renderStatus.renderedBodyCount} bodies · ${uiState.renderStatus.renderedTracerCount} tracers · ${uiState.renderStatus.renderedTrailCount} trails",
                style = MaterialTheme.typography.bodyMedium,
                color = MissionText,
            )
            uiState.renderStatus.summary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MissionTextDim,
                )
            }
            if (uiState.renderStatus.readiness == RenderHostReadiness.Unavailable) {
                Text(
                    text = "Showing the last decoded frame while packet export catches up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MissionGold,
                )
            }
        }
    }
}

@Composable
private fun MissionStageTelemetryCard(
    uiState: ShellUiState,
    modifier: Modifier = Modifier,
) {
    val epochSeconds = uiState.snapshot?.epochSeconds ?: uiState.renderFrame?.epochSeconds
    val revision = uiState.renderStatus.sceneRevision ?: uiState.renderFrame?.sceneRevision ?: "waiting"
    val focusLabel = uiState.focusedBodyId?.ifBlank { null } ?: "free camera"
    val bodyCount = uiState.renderStatus.renderedBodyCount
    val tracerCount = uiState.renderStatus.renderedTracerCount
    val trailCount = uiState.renderStatus.renderedTrailCount
    val signalValues = missionSignalValues(uiState)

    Surface(
        modifier = modifier.widthIn(max = 320.dp),
        shape = RoundedCornerShape(22.dp),
        color = MissionGlass,
        border = BorderStroke(1.dp, MissionCyan.copy(alpha = 0.28f)),
        shadowElevation = 14.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MissionCyanDim.copy(alpha = 0.18f),
                            MissionGlass,
                            MissionVoid.copy(alpha = 0.84f),
                        )
                    )
                )
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = "MISSION CLOCK",
                style = MaterialTheme.typography.labelLarge,
                color = MissionCyan,
            )
            Text(
                text = epochSeconds?.let { "MET ${it.asEpochLabel()}" } ?: "Awaiting epoch",
                style = MaterialTheme.typography.titleLarge,
                color = MissionText,
            )
            MissionMetricRow(label = "Focus", value = focusLabel)
            MissionMetricRow(label = "Revision", value = revision)
            MissionMetricRow(label = "Scene", value = "$bodyCount bodies · $tracerCount tracers · $trailCount trails")
            MissionMiniSignalChart(
                values = signalValues,
                accent = MissionCyan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
            )
        }
    }
}

@Composable
private fun MissionMetricRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelMedium,
            color = MissionTextDim,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MissionText,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 178.dp),
        )
    }
}

@Composable
private fun MissionTimelineRail(
    uiState: ShellUiState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val epochSeconds = uiState.snapshot?.epochSeconds ?: uiState.renderFrame?.epochSeconds
    val progress = missionRailProgress(uiState)
    val playback = uiState.snapshot?.let { snapshot ->
        if (snapshot.paused) "Paused" else snapshot.simSecondsPerRealSecond.asRateLabel()
    } ?: "Packet time"

    Surface(
        modifier = modifier
            .widthIn(max = if (compact) 480.dp else 640.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MissionGlass,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.24f)),
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = epochSeconds?.let { "MET ${it.asEpochLabel()}" } ?: "Awaiting mission time",
                    style = MaterialTheme.typography.labelLarge,
                    color = MissionText,
                )
                Text(
                    text = playback,
                    style = MaterialTheme.typography.labelLarge,
                    color = MissionGold,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 24.dp else 28.dp),
            ) {
                val railY = size.height * 0.58f
                val startX = 4.dp.toPx()
                val endX = size.width - 4.dp.toPx()
                val activeX = startX + (endX - startX) * progress
                drawLine(
                    color = MissionInkLine.copy(alpha = 0.86f),
                    start = Offset(startX, railY),
                    end = Offset(endX, railY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = MissionCyan.copy(alpha = 0.88f),
                    start = Offset(startX, railY),
                    end = Offset(activeX, railY),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                repeat(9) { index ->
                    val tickX = startX + ((endX - startX) * (index / 8f))
                    val tickHeight = if (index % 2 == 0) 8.dp.toPx() else 5.dp.toPx()
                    drawLine(
                        color = MissionTextDim.copy(alpha = 0.42f),
                        start = Offset(tickX, railY - (tickHeight * 0.5f)),
                        end = Offset(tickX, railY + (tickHeight * 0.5f)),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                drawCircle(
                    color = MissionGold.copy(alpha = 0.26f),
                    radius = 8.dp.toPx(),
                    center = Offset(activeX, railY),
                )
                drawCircle(
                    color = MissionGold,
                    radius = 3.dp.toPx(),
                    center = Offset(activeX, railY),
                )
            }
        }
    }
}

@Composable
private fun MissionMiniSignalChart(
    values: List<Float>,
    accent: Color,
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
            color = MissionInkLine.copy(alpha = 0.72f),
            start = Offset(left, baseline),
            end = Offset(right, baseline),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        var previous: Offset? = null
        values.forEachIndexed { index, value ->
            val fraction = if (values.size == 1) 1f else index / (values.lastIndex.toFloat())
            val x = left + (right - left) * fraction
            val y = bottom - (bottom - top) * value.coerceIn(0f, 1f)
            val current = Offset(x, y)
            previous?.let { last ->
                drawLine(
                    color = accent.copy(alpha = 0.32f),
                    start = last,
                    end = current,
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = accent.copy(alpha = 0.88f),
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
                color = MissionGold.copy(alpha = 0.88f),
                radius = 2.4.dp.toPx(),
                center = head,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageInteractionDock(
    focusedBodyId: String?,
    quickFocusEntries: List<SolarLabTeachingCatalogEntry>,
    onFocusBodyRequested: (String?) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetView: () -> Unit,
    selectedCameraMode: VulkanPacketRenderSurfaceView.CameraPresentationMode,
    onCameraModeSelected: (VulkanPacketRenderSurfaceView.CameraPresentationMode) -> Unit,
    showForecastPaths: Boolean,
    onShowForecastPathsChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MissionGlassSoft,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = focusedBodyId?.let { "Stage tools · focused on $it" } ?: "Stage tools",
                style = MaterialTheme.typography.labelLarge,
                color = MissionCyan,
            )
            StageCameraModeDock(
                selectedCameraMode = selectedCameraMode,
                onCameraModeSelected = onCameraModeSelected,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RuntimeCommandChip(
                    label = "Zoom +",
                    selected = false,
                    enabled = true,
                    onClick = onZoomIn,
                )
                RuntimeCommandChip(
                    label = "Zoom -",
                    selected = false,
                    enabled = true,
                    onClick = onZoomOut,
                )
                RuntimeCommandChip(
                    label = "Reset view",
                    selected = false,
                    enabled = true,
                    onClick = onResetView,
                )
                RuntimeCommandChip(
                    label = if (showForecastPaths) "Forecast on" else "Forecast off",
                    selected = showForecastPaths,
                    enabled = true,
                    onClick = { onShowForecastPathsChange(!showForecastPaths) },
                    modifier = Modifier.testTag(SolarLabTestTags.PREDICTED_PATH_VISIBILITY_BUTTON),
                )
                if (focusedBodyId != null) {
                    RuntimeCommandChip(
                        label = "Clear focus",
                        selected = false,
                        enabled = true,
                        onClick = { onFocusBodyRequested(null) },
                    )
                }
            }
            if (quickFocusEntries.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    quickFocusEntries.forEach { entry ->
                        RuntimeCommandChip(
                            label = entry.displayName,
                            selected = focusedBodyId == entry.bodyId,
                            enabled = true,
                            onClick = { onFocusBodyRequested(entry.bodyId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun StageCameraModeDock(
    selectedCameraMode: VulkanPacketRenderSurfaceView.CameraPresentationMode,
    onCameraModeSelected: (VulkanPacketRenderSurfaceView.CameraPresentationMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        StageCameraModeOption(
            label = "Cinematic",
            mode = VulkanPacketRenderSurfaceView.CameraPresentationMode.Cinematic,
            testTag = SolarLabTestTags.STAGE_CAMERA_MODE_CINEMATIC,
        ),
        StageCameraModeOption(
            label = "Overhead",
            mode = VulkanPacketRenderSurfaceView.CameraPresentationMode.Overhead,
            testTag = SolarLabTestTags.STAGE_CAMERA_MODE_OVERHEAD,
        ),
        StageCameraModeOption(
            label = "Follow",
            mode = VulkanPacketRenderSurfaceView.CameraPresentationMode.Follow,
            testTag = SolarLabTestTags.STAGE_CAMERA_MODE_FOLLOW,
        ),
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MissionGlassSoft,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.18f)),
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                RuntimeCommandChip(
                    label = option.label,
                    selected = selectedCameraMode == option.mode,
                    enabled = true,
                    onClick = { onCameraModeSelected(option.mode) },
                    modifier = Modifier.testTag(option.testTag),
                )
            }
        }
    }
}

@Composable
private fun FocusedBodyHeroCard(
    model: FocusedBodyHeroModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .widthIn(max = 290.dp)
            .testTag(SolarLabTestTags.FOCUSED_BODY_CARD),
        shape = RoundedCornerShape(22.dp),
        color = MissionGlass,
        border = BorderStroke(1.dp, MissionCyan.copy(alpha = 0.24f)),
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MissionBlue.copy(alpha = 0.14f),
                            MissionGlass,
                        )
                    )
                )
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = model.cameraModeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MissionCyan,
            )
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleLarge,
                color = MissionText,
            )
            Text(
                text = model.detailLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MissionTextDim,
            )
            Text(
                text = model.contextLine,
                style = MaterialTheme.typography.bodySmall,
                color = MissionGold,
            )
        }
    }
}

@Composable
private fun TrackedOrbitHistoryPanel(
    trackedBodyIds: List<String>,
    showTrackedOrbits: Boolean,
    trackedOrbitLimit: Int,
    onShowTrackedOrbitsChange: (Boolean) -> Unit,
    onTrackedOrbitLimitChange: (Int) -> Unit,
    showForecastPaths: Boolean,
    forecastSourceBodyIds: List<String>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(SolarLabTestTags.ORBIT_OVERLAY_LEGEND_PANEL),
        shape = RoundedCornerShape(20.dp),
        color = MissionGlass,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Tracked orbits",
                style = MaterialTheme.typography.labelLarge,
                color = MissionCyan,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = showTrackedOrbits,
                    onClick = { onShowTrackedOrbitsChange(!showTrackedOrbits) },
                    label = {
                        Text(if (showTrackedOrbits) "Visible" else "Hidden")
                    },
                    modifier = Modifier.testTag(SolarLabTestTags.TRACKED_ORBIT_VISIBILITY_BUTTON),
                )
                listOf(3, 5, 8, 12).forEach { limit ->
                    RuntimeCommandChip(
                        label = limit.toString(),
                        selected = trackedOrbitLimit == limit,
                        enabled = true,
                        onClick = { onTrackedOrbitLimitChange(limit) },
                        modifier = Modifier.testTag(SolarLabTestTags.trackedOrbitLimitTag(limit)),
                    )
                }
            }
            if (showTrackedOrbits) {
                if (trackedBodyIds.isEmpty()) {
                    Text(
                        text = "No tracked bodies yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MissionText,
                    )
                } else {
                    trackedBodyIds.forEachIndexed { index, bodyId ->
                        Text(
                            text = "${index + 1}. $bodyId",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MissionText,
                        )
                    }
                }
            } else {
                Text(
                    text = "Tracked orbits are hidden.",
                    modifier = Modifier.testTag(SolarLabTestTags.ORBIT_OVERLAY_HISTORY_SUMMARY),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MissionText,
                )
            }
            if (showTrackedOrbits) {
                Text(
                    text = "History trails · last $trackedOrbitLimit focused bodies stay visible behind the stage.",
                    modifier = Modifier.testTag(SolarLabTestTags.ORBIT_OVERLAY_HISTORY_SUMMARY),
                    style = MaterialTheme.typography.bodySmall,
                    color = MissionTextDim,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Forecast paths",
                style = MaterialTheme.typography.labelLarge,
                color = MissionCyan,
            )
            Text(
                text = if (showForecastPaths) {
                    "Forecast paths · short-horizon projection from the focused body."
                } else {
                    "Forecast paths are hidden."
                },
                modifier = Modifier.testTag(SolarLabTestTags.ORBIT_OVERLAY_FORECAST_SUMMARY),
                style = MaterialTheme.typography.bodyMedium,
                color = MissionText,
            )
            Text(
                text = overlayBodySummary(
                    bodyIds = forecastSourceBodyIds,
                    emptyText = "Forecast follows the current focus when one is selected.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MissionTextDim,
            )
        }
    }
}

private fun overlayBodySummary(
    bodyIds: List<String>,
    emptyText: String,
): String = if (bodyIds.isEmpty()) {
    emptyText
} else {
    "Bodies: ${bodyIds.joinToString(separator = ", ")}"
}

private val ShellUiState.forecastSourceBodyIds: List<String>
    get() = listOfNotNull(focusedBodyId).ifEmpty {
        recentFocusedBodyIds.take(2)
    }

@Composable
private fun EmptyRenderStage(
    uiState: ShellUiState,
    onRefresh: () -> Unit,
    canRefresh: Boolean,
) {
    val title = when (uiState.connectionState) {
        SessionConnectionState.Connecting -> "Preparing the render host"
        SessionConnectionState.Unavailable -> "Render host cannot start"
        SessionConnectionState.Active -> when (uiState.renderStatus.readiness) {
            RenderHostReadiness.Refreshing -> "Fetching the next render packet"
            RenderHostReadiness.Unavailable -> "Render export is temporarily unavailable"
            RenderHostReadiness.Failed -> "Render packet decode failed"
            RenderHostReadiness.WaitingForSession -> "Waiting for the first authoritative frame"
            RenderHostReadiness.Ready -> "Render host is ready"
        }
    }
    val detail = uiState.renderStatus.issue
        ?: uiState.pendingActionLabel
        ?: uiState.noticeLine
        ?: uiState.detailLine
        ?: "The Android shell will render as soon as the Rust runtime exports a scene packet."

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MissionCyan.copy(alpha = 0.46f),
                            MissionCyanDim.copy(alpha = 0.12f),
                            Color.Transparent,
                        )
                    )
                ),
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MissionText,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MissionTextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(modifier = Modifier.height(18.dp))
        FilledTonalButton(
            onClick = onRefresh,
            enabled = canRefresh,
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("Pull the latest state")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlDeck(
    uiState: ShellUiState,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onCommand: (RuntimeCommand) -> Unit,
) {
    var focusCatalogQuery by rememberSaveable { mutableStateOf("") }
    var focusBodyInput by rememberSaveable { mutableStateOf("") }
    var checkpointIdInput by rememberSaveable { mutableStateOf("") }
    var branchFromCheckpointIdInput by rememberSaveable { mutableStateOf("") }
    var newBranchIdInput by rememberSaveable { mutableStateOf("") }
    var spawnBodyIdInput by rememberSaveable { mutableStateOf("") }
    var spawnBodyClassName by rememberSaveable { mutableStateOf(RuntimeBodyClass.Planet.name) }
    var spawnBodyMassInput by rememberSaveable { mutableStateOf("1.0") }
    var spawnBodyRadiusInput by rememberSaveable { mutableStateOf("1.0") }
    var setBodyKinematicsBodyIdInput by rememberSaveable { mutableStateOf("") }
    var setBodyKinematicsPositionXInput by rememberSaveable { mutableStateOf("0.0") }
    var setBodyKinematicsPositionYInput by rememberSaveable { mutableStateOf("0.0") }
    var setBodyKinematicsPositionZInput by rememberSaveable { mutableStateOf("0.0") }
    var setBodyKinematicsVelocityXInput by rememberSaveable { mutableStateOf("0.0") }
    var setBodyKinematicsVelocityYInput by rememberSaveable { mutableStateOf("0.0") }
    var setBodyKinematicsVelocityZInput by rememberSaveable { mutableStateOf("0.0") }
    var removeBodyIdInput by rememberSaveable { mutableStateOf("") }

    val spawnBodyClass = runCatching { RuntimeBodyClass.valueOf(spawnBodyClassName) }
        .getOrDefault(RuntimeBodyClass.Planet)

    LabPanel {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                text = "Control deck",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "These controls only send host intents. Rust remains the authority for state, timing, and packet output.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ControlSection(title = "Teaching catalog") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = focusCatalogQuery,
                        onValueChange = { focusCatalogQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.FOCUS_CATALOG_SEARCH_FIELD),
                        enabled = enabled,
                        label = { Text("Search canonical bodies or aliases") },
                        singleLine = true,
                    )
                    val matchingEntries = SolarLabTeachingCatalog.entries.filter { entry ->
                        entry.matches(focusCatalogQuery)
                    }
                    if (matchingEntries.isEmpty()) {
                        Text(
                            text = "No teaching bodies match that search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            matchingEntries.forEach { entry ->
                                TeachingCatalogCard(
                                    entry = entry,
                                    enabled = enabled,
                                    onLoadFocusBody = {
                                        focusBodyInput = entry.bodyId
                                    },
                                    onLoadSpawnPreset = {
                                        spawnBodyIdInput = entry.bodyId
                                        spawnBodyClassName = entry.spawnBodyClass.name
                                        spawnBodyMassInput = entry.spawnMassKg.toString()
                                        spawnBodyRadiusInput = entry.spawnRadiusM.toString()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = onRefresh,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("Refresh")
                }
                Button(
                    onClick = {
                        onCommand(
                            if (uiState.snapshot?.paused == true) {
                                RuntimeCommand.ResumePlayback
                            } else {
                                RuntimeCommand.PausePlayback
                            }
                        )
                    },
                    enabled = enabled && uiState.snapshot != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(if (uiState.snapshot?.paused == true) "Resume" else "Pause")
                }
            }

            ControlSection(title = "Focus and follow context") {
                OutlinedTextField(
                    value = focusBodyInput,
                    onValueChange = { focusBodyInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SolarLabTestTags.FOCUS_BODY_FIELD),
                    enabled = enabled,
                    label = { Text("Body id (leave empty to clear)") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            onCommand(
                                RuntimeCommand.FocusBody(
                                    focusBodyInput.trim().ifBlank { null },
                                ),
                            )
                        },
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(SolarLabTestTags.FOCUS_BODY_SET_BUTTON),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text("Set focus body")
                    }
                    Button(
                        onClick = {
                            onCommand(RuntimeCommand.SetObserverMode(RuntimeObserverMode.FollowSelected))
                        },
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(SolarLabTestTags.FOCUS_SELECTION_BUTTON),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text("Follow selection")
                    }
                }
            }

            ControlSection(title = "Timeline nudge") {
                RuntimeCommandChip(
                    label = "+60s",
                    selected = false,
                    enabled = enabled,
                    onClick = { onCommand(RuntimeCommand.AdvanceEpoch(deltaSeconds = 60.0)) },
                )
                RuntimeCommandChip(
                    label = "+1h",
                    selected = false,
                    enabled = enabled,
                    onClick = { onCommand(RuntimeCommand.AdvanceEpoch(deltaSeconds = 3_600.0)) },
                )
                RuntimeCommandChip(
                    label = "+6h",
                    selected = false,
                    enabled = enabled,
                    onClick = { onCommand(RuntimeCommand.AdvanceEpoch(deltaSeconds = 21_600.0)) },
                )
            }

            ControlSection(title = "Body management") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = spawnBodyIdInput,
                        onValueChange = { spawnBodyIdInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.SPAWN_BODY_ID_FIELD),
                        enabled = enabled,
                        label = { Text("Spawn body id") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = spawnBodyMassInput,
                            onValueChange = { spawnBodyMassInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(SolarLabTestTags.SPAWN_BODY_MASS_FIELD),
                            enabled = enabled,
                            label = { Text("Mass (kg)") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = spawnBodyRadiusInput,
                            onValueChange = { spawnBodyRadiusInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(SolarLabTestTags.SPAWN_BODY_RADIUS_FIELD),
                            enabled = enabled,
                            label = { Text("Radius (m)") },
                            singleLine = true,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeBodyClass.entries.forEach { bodyClass ->
                            RuntimeCommandChip(
                                label = bodyClass.displayLabel(),
                                selected = spawnBodyClass == bodyClass,
                                enabled = enabled,
                                onClick = { spawnBodyClassName = bodyClass.name },
                            )
                        }
                    }
                    Button(
                        onClick = {
                            onCommand(
                                RuntimeCommand.SpawnBody(
                                    bodyId = spawnBodyIdInput.trim(),
                                    bodyClass = spawnBodyClass,
                                    massKg = spawnBodyMassInput.toDoubleOrNull() ?: 1.0,
                                    radiusM = spawnBodyRadiusInput.toDoubleOrNull() ?: 1.0,
                                ),
                            )
                            spawnBodyIdInput = ""
                        },
                        enabled = enabled && spawnBodyIdInput.trim().isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.SPAWN_BODY_BUTTON),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text("Spawn body")
                    }

                    OutlinedTextField(
                        value = setBodyKinematicsBodyIdInput,
                        onValueChange = { setBodyKinematicsBodyIdInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.SET_BODY_KINEMATICS_BODY_ID_FIELD),
                        enabled = enabled,
                        label = { Text("Kinematics body id") },
                        singleLine = true,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = setBodyKinematicsPositionXInput,
                            onValueChange = { setBodyKinematicsPositionXInput = it },
                            modifier = Modifier
                                .widthIn(min = 120.dp)
                                .testTag(SolarLabTestTags.SET_BODY_KINEMATICS_POSITION_X_FIELD),
                            enabled = enabled,
                            label = { Text("Pos X") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = setBodyKinematicsPositionYInput,
                            onValueChange = { setBodyKinematicsPositionYInput = it },
                            modifier = Modifier
                                .widthIn(min = 120.dp)
                                .testTag(SolarLabTestTags.SET_BODY_KINEMATICS_POSITION_Y_FIELD),
                            enabled = enabled,
                            label = { Text("Pos Y") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = setBodyKinematicsPositionZInput,
                            onValueChange = { setBodyKinematicsPositionZInput = it },
                            modifier = Modifier
                                .widthIn(min = 120.dp)
                                .testTag(SolarLabTestTags.SET_BODY_KINEMATICS_POSITION_Z_FIELD),
                            enabled = enabled,
                            label = { Text("Pos Z") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = setBodyKinematicsVelocityXInput,
                            onValueChange = { setBodyKinematicsVelocityXInput = it },
                            modifier = Modifier
                                .widthIn(min = 120.dp)
                                .testTag(SolarLabTestTags.SET_BODY_KINEMATICS_VELOCITY_X_FIELD),
                            enabled = enabled,
                            label = { Text("Vel X") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = setBodyKinematicsVelocityYInput,
                            onValueChange = { setBodyKinematicsVelocityYInput = it },
                            modifier = Modifier
                                .widthIn(min = 120.dp)
                                .testTag(SolarLabTestTags.SET_BODY_KINEMATICS_VELOCITY_Y_FIELD),
                            enabled = enabled,
                            label = { Text("Vel Y") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = setBodyKinematicsVelocityZInput,
                            onValueChange = { setBodyKinematicsVelocityZInput = it },
                            modifier = Modifier
                                .widthIn(min = 120.dp)
                                .testTag(SolarLabTestTags.SET_BODY_KINEMATICS_VELOCITY_Z_FIELD),
                            enabled = enabled,
                            label = { Text("Vel Z") },
                            singleLine = true,
                        )
                    }
                    Button(
                        onClick = {
                            onCommand(
                                RuntimeCommand.SetBodyKinematics(
                                    bodyId = setBodyKinematicsBodyIdInput.trim(),
                                    positionX = setBodyKinematicsPositionXInput.toDoubleOrNull() ?: 0.0,
                                    positionY = setBodyKinematicsPositionYInput.toDoubleOrNull() ?: 0.0,
                                    positionZ = setBodyKinematicsPositionZInput.toDoubleOrNull() ?: 0.0,
                                    velocityX = setBodyKinematicsVelocityXInput.toDoubleOrNull() ?: 0.0,
                                    velocityY = setBodyKinematicsVelocityYInput.toDoubleOrNull() ?: 0.0,
                                    velocityZ = setBodyKinematicsVelocityZInput.toDoubleOrNull() ?: 0.0,
                                ),
                            )
                        },
                        enabled = enabled && setBodyKinematicsBodyIdInput.trim().isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.SET_BODY_KINEMATICS_BUTTON),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text("Set body kinematics")
                    }

                    OutlinedTextField(
                        value = removeBodyIdInput,
                        onValueChange = { removeBodyIdInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.REMOVE_BODY_ID_FIELD),
                        enabled = enabled,
                        label = { Text("Remove body id") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            onCommand(
                                RuntimeCommand.RemoveBody(
                                    bodyId = removeBodyIdInput.trim(),
                                ),
                            )
                            removeBodyIdInput = ""
                        },
                        enabled = enabled && removeBodyIdInput.trim().isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.REMOVE_BODY_BUTTON),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text("Remove body")
                    }
                }
            }

            ControlSection(title = "Playback rate") {
                val selectedRate = uiState.snapshot?.simSecondsPerRealSecond
                listOf(0.25, 1.0, 10.0, 60.0).forEach { rate ->
                    RuntimeCommandChip(
                        label = rate.asRateLabel(),
                        selected = selectedRate?.let { closeEnough(it, rate) } == true,
                        enabled = enabled,
                        onClick = { onCommand(RuntimeCommand.SetPlaybackRate(simSecondsPerRealSecond = rate)) },
                    )
                }
            }

            ControlSection(title = "Checkpoint and branch controls") {
                OutlinedTextField(
                    value = checkpointIdInput,
                    onValueChange = { checkpointIdInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SolarLabTestTags.CHECKPOINT_ID_FIELD),
                    enabled = enabled,
                    label = { Text("Checkpoint id (optional)") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        onCommand(
                            RuntimeCommand.CreateCheckpoint(
                                checkpointId = checkpointIdInput.trim().ifBlank { null },
                                checkpointLabel = null,
                            ),
                        )
                        checkpointIdInput = ""
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SolarLabTestTags.CREATE_CHECKPOINT_BUTTON),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("Create checkpoint")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = branchFromCheckpointIdInput,
                        onValueChange = { branchFromCheckpointIdInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(SolarLabTestTags.BRANCH_FROM_CHECKPOINT_FIELD),
                        enabled = enabled,
                        label = { Text("Checkpoint id") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newBranchIdInput,
                        onValueChange = { newBranchIdInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(SolarLabTestTags.BRANCH_NAME_FIELD),
                        enabled = enabled,
                        label = { Text("New branch id (optional)") },
                        singleLine = true,
                    )
                }
                Button(
                    onClick = {
                        onCommand(
                            RuntimeCommand.CreateBranchFromCheckpoint(
                                checkpointId = branchFromCheckpointIdInput.trim(),
                                newBranchId = newBranchIdInput.trim().ifBlank { null },
                            ),
                        )
                        newBranchIdInput = ""
                    },
                    enabled = enabled && branchFromCheckpointIdInput.trim().isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SolarLabTestTags.CREATE_BRANCH_FROM_CHECKPOINT_BUTTON),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("Create branch from checkpoint")
                }
            }

            ControlSection(title = "Observer mode") {
                RuntimeObserverMode.values().forEach { mode ->
                    RuntimeCommandChip(
                        label = mode.displayLabel(),
                        selected = uiState.snapshot?.observerModeLabel == mode.displayLabel(),
                        enabled = enabled,
                        onClick = { onCommand(RuntimeCommand.SetObserverMode(mode)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TeachingCatalogCard(
    entry: SolarLabTeachingCatalogEntry,
    enabled: Boolean,
    onLoadFocusBody: () -> Unit,
    onLoadSpawnPreset: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MissionGlassSoft,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 214.dp, max = 280.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MissionText,
                )
                Text(
                    text = entry.bodyId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MissionCyan,
                )
                if (entry.aliases.isNotEmpty()) {
                    Text(
                        text = entry.aliases.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MissionTextDim,
                    )
                }
            }
            Button(
                onClick = onLoadFocusBody,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SolarLabTestTags.focusCatalogFocusPresetTag(entry.bodyId)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Load focus field")
            }
            FilledTonalButton(
                onClick = onLoadSpawnPreset,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SolarLabTestTags.focusCatalogSpawnPresetTag(entry.bodyId)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Load spawn preset")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun RuntimeCommandChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = {
            Text(label)
        },
    )
}

private fun RuntimeBodyClass.displayLabel(): String = when (this) {
    RuntimeBodyClass.Star -> "Star"
    RuntimeBodyClass.Planet -> "Planet"
    RuntimeBodyClass.DwarfPlanet -> "Dwarf"
    RuntimeBodyClass.Moon -> "Moon"
    RuntimeBodyClass.SmallBody -> "Small body"
    RuntimeBodyClass.Tracer -> "Tracer"
    RuntimeBodyClass.Spacecraft -> "Craft"
    RuntimeBodyClass.Custom -> "Custom"
}

@Composable
private fun RuntimeDetailPanel(uiState: ShellUiState) {
    LabPanel {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                text = "Runtime and boundary details",
                style = MaterialTheme.typography.titleLarge,
                color = MissionText,
            )

            NoticeCard(uiState = uiState)

            DetailGrid(uiState = uiState)

            DeveloperTelemetryCard(presentation = uiState.developerTelemetry)
        }
    }
}

@Composable
private fun NoticeCard(uiState: ShellUiState) {
    val message = uiState.pendingActionLabel ?: uiState.noticeLine ?: "No additional runtime notices."
    val tone = if (uiState.pendingActionLabel != null) {
        ShellNoticeTone.Neutral
    } else {
        uiState.noticeTone
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = tone.containerColor(),
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, tone.borderColor()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = when (tone) {
                    ShellNoticeTone.Positive -> "Healthy signal"
                    ShellNoticeTone.Caution -> "Attention"
                    ShellNoticeTone.Critical -> "Failure"
                    ShellNoticeTone.Neutral -> "Current notice"
                },
                style = MaterialTheme.typography.labelLarge,
                color = tone.textColor(),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MissionText,
            )
        }
    }
}

@Composable
private fun DetailGrid(uiState: ShellUiState) {
    val renderSummaryFallback = uiState.renderPacketSummary
        ?: uiState.renderStatus.summary
        ?: "No packet summary yet"
    val renderedIssue = uiState.renderStatus.issue ?: "None"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailCard(
            title = "Session boundary",
            entries = listOf(
                "Connection" to when (uiState.connectionState) {
                    SessionConnectionState.Connecting -> "Connecting"
                    SessionConnectionState.Active -> "Connected"
                    SessionConnectionState.Unavailable -> "Unavailable"
                },
                "Session handle" to (uiState.sessionHandle?.toString() ?: "Not yet assigned"),
                "Provenance" to (uiState.backendSummary ?: "Not exposed by this state slice"),
            ),
            valueTags = mapOf("Provenance" to SolarLabTestTags.METADATA_PROVENANCE),
        )

        DetailCard(
            title = "Authoritative snapshot",
            entries = uiState.snapshot.toSnapshotEntries().plus(
                listOf(
                    "Active checkpoint" to uiState.deriveActiveCheckpoint(),
                    "Focus target" to uiState.resolveFocusTarget(),
                    "Observer mode code" to uiState.deriveObserverModeCode(),
                ),
            ),
            valueTags = mapOf(
                "Active branch" to SolarLabTestTags.METADATA_ACTIVE_BRANCH,
                "Active checkpoint" to SolarLabTestTags.METADATA_ACTIVE_CHECKPOINT,
                "Focus target" to SolarLabTestTags.METADATA_FOCUS_TARGET,
                "Observer mode" to SolarLabTestTags.METADATA_OBSERVER_MODE,
            ),
        )

        DetailCard(
            title = "Render host",
            entries = listOf(
                "Readiness" to uiState.renderStatus.readiness.displayLabel(),
                "Scene revision" to (uiState.renderStatus.sceneRevision ?: "Waiting for packet"),
                "Body count" to uiState.renderStatus.renderedBodyCount.toString(),
                "Tracer count" to uiState.renderStatus.renderedTracerCount.toString(),
                "Trail count" to uiState.renderStatus.renderedTrailCount.toString(),
                "Light count" to deriveLightCountFromSummary(renderSummaryFallback),
                "Packet summary" to renderSummaryFallback,
                "Issue" to renderedIssue,
            ),
            valueTags = mapOf(
                "Packet summary" to SolarLabTestTags.RENDER_PACKET_SUMMARY,
                "Light count" to SolarLabTestTags.METADATA_LIGHTS,
            ),
        )
    }
}

@Composable
private fun DetailCard(
    title: String,
    entries: List<Pair<String, String>>,
    valueTags: Map<String, String> = emptyMap(),
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MissionGlassSoft,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.16f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MissionText,
            )
            entries.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(color = MissionInkLine.copy(alpha = 0.68f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MissionCyan,
                        modifier = Modifier.weight(0.38f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MissionTextDim,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .weight(0.62f)
                            .then(
                                valueTags[label]?.let { tag ->
                                    Modifier.testTag(tag)
                                } ?: Modifier
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeveloperTelemetryCard(presentation: DeveloperTelemetryPresentation) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val shareText = presentation.toShareText(maxEntries = 24, locale = Locale.getDefault())
    val streamingTarget = developerTelemetryStreamingTargetLabel()

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MissionGlassSoft,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.16f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Developer telemetry",
                style = MaterialTheme.typography.titleMedium,
                color = MissionText,
            )
            Text(
                text = if (presentation.enabled) {
                    "Local shell diagnostics are mirrored to logcat with tag $DEVELOPER_TELEMETRY_LOG_TAG and can be copied or shared from here."
                } else {
                    "Developer telemetry is disabled for this build."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MissionTextDim,
            )
            streamingTarget?.let { target ->
                Text(
                    text = "Remote stream target: $target",
                    style = MaterialTheme.typography.bodySmall,
                    color = MissionCyan,
                )
            }

            if (presentation.enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(shareText))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Copy telemetry")
                    }
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "Solar Gravity Lab developer telemetry")
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    },
                                    "Share developer telemetry",
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Share")
                    }
                }
            }

            if (presentation.droppedEntryCount > 0) {
                Text(
                    text = "Dropped oldest entries: ${presentation.droppedEntryCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MissionGold,
                )
            }

            if (!presentation.enabled || presentation.entries.isEmpty()) {
                Text(
                    text = if (presentation.enabled) {
                        "No local telemetry captured yet."
                    } else {
                        "Use a debug or internal build to enable live device telemetry."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MissionTextDim,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presentation.entries
                        .takeLast(8)
                        .asReversed()
                        .forEach { event ->
                            DeveloperTelemetryEntry(event = event)
                        }
                }
            }
        }
    }
}

@Composable
private fun DeveloperTelemetryEntry(event: DeveloperTelemetryEvent) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MissionGlass,
        border = BorderStroke(1.dp, MissionInkLine.copy(alpha = 0.72f)),
    ) {
        Text(
            text = event.toDisplayLine(locale = Locale.getDefault()),
            style = MaterialTheme.typography.bodySmall,
            color = MissionTextDim,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LabPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = MissionGlass,
        border = BorderStroke(1.dp, MissionCyanDim.copy(alpha = 0.18f)),
        shadowElevation = 12.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MissionGlass,
                            MissionGlassSoft,
                        )
                    )
                )
                .padding(20.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    tone: ShellNoticeTone,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = tone.containerColor(),
        border = BorderStroke(1.dp, tone.borderColor()),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tone.textColor(),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private data class StageCameraModeOption(
    val label: String,
    val mode: VulkanPacketRenderSurfaceView.CameraPresentationMode,
    val testTag: String,
)

private data class FocusedBodyHeroModel(
    val displayName: String,
    val detailLine: String,
    val contextLine: String,
    val cameraModeLabel: String,
)

private fun deriveFocusedBodyHeroModel(
    uiState: ShellUiState,
    stageCameraMode: VulkanPacketRenderSurfaceView.CameraPresentationMode,
): FocusedBodyHeroModel? {
    val frame = uiState.renderFrame ?: return null
    val focusBody = frame.bodies.firstOrNull { body ->
        uiState.focusedBodyId?.let { focusId ->
            body.bodyId.equals(focusId, ignoreCase = true)
        } == true
    } ?: frame.bodies.firstOrNull { body ->
        body.selected
    } ?: return null

    val catalogName = SolarLabTeachingCatalog.entries
        .firstOrNull { entry -> entry.bodyId.equals(focusBody.bodyId, ignoreCase = true) }
        ?.displayName
    val displayName = catalogName ?: focusBody.bodyId.replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(Locale.US) else character.toString()
    }
    val radiusKm = focusBody.radiusM.toDouble() / 1_000.0
    val detailParts = mutableListOf("radius ${String.format(Locale.US, "%,.0f", radiusKm)} km")
    val sun = frame.bodies.firstOrNull { body ->
        body.bodyId.equals("sun", ignoreCase = true)
    }
    if (sun != null && !focusBody.bodyId.equals("sun", ignoreCase = true)) {
        val dx = (focusBody.x - sun.x).toDouble()
        val dy = (focusBody.y - sun.y).toDouble()
        val dz = (focusBody.z - sun.z).toDouble()
        val distanceAu = sqrt((dx * dx) + (dy * dy) + (dz * dz)) / ASTRONOMICAL_UNIT_M_DOUBLE
        if (distanceAu.isFinite()) {
            detailParts += "sun distance ${String.format(Locale.US, "%.3f", distanceAu)} AU"
        }
    }
    val cameraLabel = when (stageCameraMode) {
        VulkanPacketRenderSurfaceView.CameraPresentationMode.Cinematic -> "Cinematic camera"
        VulkanPacketRenderSurfaceView.CameraPresentationMode.Overhead -> "Overhead camera"
        VulkanPacketRenderSurfaceView.CameraPresentationMode.Follow -> "Follow camera"
    }
    val contextLine = "${uiState.renderStatus.renderedBodyCount} bodies · " +
        "${uiState.renderStatus.renderedTracerCount} tracers · " +
        "${uiState.renderStatus.renderedTrailCount} trails"
    return FocusedBodyHeroModel(
        displayName = displayName,
        detailLine = detailParts.joinToString(" · "),
        contextLine = contextLine,
        cameraModeLabel = cameraLabel,
    )
}

private const val ASTRONOMICAL_UNIT_M_DOUBLE = 149_597_870_700.0

private object MissionSignalTuning {
    const val BodyNormalization = 18f
    const val TracerNormalization = 120f
    const val TrailNormalization = 28f
    const val FocusLift = 0.18f

    const val L1_Base = 0.24f
    const val L1_BodyCoeff = 0.34f

    const val L2_Base = 0.18f
    const val L2_TracerCoeff = 0.52f
    const val L2_FocusCoeff = 0.24f

    const val L3_Base = 0.30f
    const val L3_TrailCoeff = 0.42f

    const val L4_Base = 0.22f
    const val L4_TimeCoeff = 0.48f

    const val L5_Base = 0.32f
    const val L5_BodyCoeff = 0.22f
    const val L5_TrailCoeff = 0.24f

    const val L6_Base = 0.28f
    const val L6_TracerCoeff = 0.34f
}

private fun SnapshotPresentation?.toSnapshotEntries(): List<Pair<String, String>> {
    if (this == null) {
        return listOf(
            "State" to "Waiting for the first authoritative snapshot",
            "Scenario" to "Pending",
            "Active branch" to "Pending",
        )
    }

    return listOf(
        "Scenario" to scenarioId,
        "Active branch" to activeBranchId,
        "Epoch" to epochSeconds.asEpochLabel(),
        "Bodies" to bodyCount.toString(),
        "Playback" to if (paused) "Paused" else simSecondsPerRealSecond.asPlaybackLabel(),
        "Observer mode" to observerModeLabel,
    )
}

private fun ShellUiState.deriveObserverModeCode(): String = observerModeCode?.let { code ->
    RuntimeObserverMode.values().firstOrNull { it.nativeCode == code }?.displayLabel()
        ?: "Mode code $code"
} ?: "Not exposed by this state slice"

private fun ShellUiState.resolveFocusTarget(): String = cameraFacingSummary ?: "Not exposed by this state slice"

private fun ShellUiState.deriveActiveCheckpoint(): String = snapshotSummary?.parseActiveCheckpoint()
    ?: "Not exposed by this state slice"

private fun missionRailProgress(uiState: ShellUiState): Float {
    val epochSeconds = uiState.snapshot?.epochSeconds ?: uiState.renderFrame?.epochSeconds ?: return 0.08f
    val missionDaySeconds = 86_400.0
    val normalized = ((epochSeconds % missionDaySeconds) + missionDaySeconds) % missionDaySeconds
    return (normalized / missionDaySeconds).toFloat().coerceIn(0.04f, 0.96f)
}

private fun missionSignalValues(uiState: ShellUiState): List<Float> {
    val epochProgress = missionRailProgress(uiState)
    val bodySignal = (uiState.renderStatus.renderedBodyCount / MissionSignalTuning.BodyNormalization)
        .coerceIn(0.08f, 0.92f)
    val tracerSignal = (uiState.renderStatus.renderedTracerCount / MissionSignalTuning.TracerNormalization)
        .coerceIn(0.06f, 0.86f)
    val trailSignal = (uiState.renderStatus.renderedTrailCount / MissionSignalTuning.TrailNormalization)
        .coerceIn(0.10f, 0.90f)
    val focusLift = if (uiState.focusedBodyId != null) MissionSignalTuning.FocusLift else 0f

    return listOf(
        MissionSignalTuning.L1_Base + (bodySignal * MissionSignalTuning.L1_BodyCoeff),
        MissionSignalTuning.L2_Base + (tracerSignal * MissionSignalTuning.L2_TracerCoeff) + (focusLift * MissionSignalTuning.L2_FocusCoeff),
        MissionSignalTuning.L3_Base + (trailSignal * MissionSignalTuning.L3_TrailCoeff),
        MissionSignalTuning.L4_Base + (epochProgress * MissionSignalTuning.L4_TimeCoeff),
        MissionSignalTuning.L5_Base + (bodySignal * MissionSignalTuning.L5_BodyCoeff) + (trailSignal * MissionSignalTuning.L5_TrailCoeff),
        MissionSignalTuning.L6_Base + (tracerSignal * MissionSignalTuning.L6_TracerCoeff) + focusLift,
    ).map { value -> value.coerceIn(0.05f, 0.95f) }
}

private fun String.parseActiveCheckpoint(): String {
    val capture = Regex("checkpoint=([^,;]+)").find(this)
    return capture?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "Not exposed by this state slice"
}

private fun deriveLightCountFromSummary(summary: String): String {
    val captured = Regex("light(?:s)?=([0-9]+)").find(summary)
    return captured?.groupValues?.get(1)?.let { "$it (estimated)" }
        ?: "Not exposed by this state slice"
}

private fun RenderHostReadiness.displayLabel(): String = when (this) {
    RenderHostReadiness.WaitingForSession -> "Waiting for session"
    RenderHostReadiness.Refreshing -> "Refreshing packet"
    RenderHostReadiness.Ready -> "Ready"
    RenderHostReadiness.Unavailable -> "Unavailable"
    RenderHostReadiness.Failed -> "Decode failed"
}

private fun RuntimeObserverMode.displayLabel(): String = when (this) {
    RuntimeObserverMode.Free -> "Free camera"
    RuntimeObserverMode.FollowSelected -> "Follow selected"
    RuntimeObserverMode.FollowHost -> "Follow host"
    RuntimeObserverMode.SystemFrame -> "System frame"
}

@Composable
private fun ShellNoticeTone.containerColor(): Color = when (this) {
    ShellNoticeTone.Neutral -> MissionGlassSoft
    ShellNoticeTone.Positive -> Color(0xFF0E3A31).copy(alpha = 0.92f)
    ShellNoticeTone.Caution -> Color(0xFF473410).copy(alpha = 0.92f)
    ShellNoticeTone.Critical -> Color(0xFF4A1820).copy(alpha = 0.94f)
}

@Composable
private fun ShellNoticeTone.borderColor(): Color = when (this) {
    ShellNoticeTone.Neutral -> MissionCyanDim.copy(alpha = 0.22f)
    ShellNoticeTone.Positive -> Color(0xFF4FD39A).copy(alpha = 0.42f)
    ShellNoticeTone.Caution -> MissionGold.copy(alpha = 0.42f)
    ShellNoticeTone.Critical -> Color(0xFFFF8B8B).copy(alpha = 0.42f)
}

@Composable
private fun ShellNoticeTone.textColor(): Color = when (this) {
    ShellNoticeTone.Neutral -> MissionText
    ShellNoticeTone.Positive -> Color(0xFFD6FFE9)
    ShellNoticeTone.Caution -> Color(0xFFFFECB8)
    ShellNoticeTone.Critical -> Color(0xFFFFD7DC)
}

private fun closeEnough(left: Double, right: Double): Boolean = kotlin.math.abs(left - right) < 0.0001

private fun Double.asEpochLabel(): String = String.format(Locale.US, "%,.1f s", this)

private fun Double.asRateLabel(): String = String.format(Locale.US, "%.2fx", this)

private fun Double.asPlaybackLabel(): String = String.format(Locale.US, "%.2fx real-time", this)
