package com.sednalabs.solarlab

import android.content.Intent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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

@Composable
fun SolarLabApp(runtimeFacade: RuntimeFacade) {
    val uiState by runtimeFacade.uiState.collectAsState()
    val scope = rememberCoroutineScope()

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
                                Color(0xFF040711),
                                Color(0xFF07111E),
                                Color(0xFF0A1626),
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
                    val compactStageViewportHeight = (maxHeight * 0.56f).coerceIn(460.dp, 760.dp)
                    val canSendCommands = uiState.connectionState == SessionConnectionState.Active &&
                        uiState.pendingActionLabel == null
                    val canRefresh = canSendCommands

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.SHELL_COLUMN)
                            .widthIn(max = 1320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (isWide) {
                            HeroPanel(
                                uiState = uiState,
                                compact = false,
                                onRefresh = {
                                    scope.launch {
                                        runtimeFacade.refresh()
                                    }
                                },
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
                                    onRefresh = {
                                        scope.launch {
                                            runtimeFacade.refresh()
                                        }
                                    },
                                    onFocusBodyRequested = { bodyId ->
                                        scope.launch {
                                            runtimeFacade.applyCommand(RuntimeCommand.FocusBody(bodyId))
                                        }
                                    },
                                    canRefresh = canRefresh,
                                )
                                Column(
                                    modifier = Modifier.weight(0.92f),
                                    verticalArrangement = Arrangement.spacedBy(18.dp),
                                ) {
                                    ControlDeck(
                                        uiState = uiState,
                                        enabled = canSendCommands,
                                        onRefresh = {
                                            scope.launch {
                                                runtimeFacade.refresh()
                                            }
                                        },
                                        onCommand = { command ->
                                            scope.launch {
                                                runtimeFacade.applyCommand(command)
                                            }
                                        },
                                    )
                                    RuntimeDetailPanel(uiState = uiState)
                                }
                            }
                        } else {
                            RenderStagePanel(
                                uiState = uiState,
                                modifier = Modifier.fillMaxWidth(),
                                compactStage = true,
                                compactStageHeight = compactStageViewportHeight,
                                onRefresh = {
                                    scope.launch {
                                        runtimeFacade.refresh()
                                    }
                                },
                                onFocusBodyRequested = { bodyId ->
                                    scope.launch {
                                        runtimeFacade.applyCommand(RuntimeCommand.FocusBody(bodyId))
                                    }
                                },
                                canRefresh = canRefresh,
                            )
                            HeroPanel(
                                uiState = uiState,
                                compact = true,
                                onRefresh = {
                                    scope.launch {
                                        runtimeFacade.refresh()
                                    }
                                },
                                canRefresh = canRefresh,
                            )
                            ControlDeck(
                                uiState = uiState,
                                enabled = canSendCommands,
                                onRefresh = {
                                    scope.launch {
                                        runtimeFacade.refresh()
                                    }
                                },
                                onCommand = { command ->
                                    scope.launch {
                                        runtimeFacade.applyCommand(command)
                                    }
                                },
                            )
                            RuntimeDetailPanel(uiState = uiState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrbitalBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = Color(0xFF173C68).copy(alpha = 0.26f),
            radius = size.minDimension * 0.35f,
            center = Offset(size.width * 0.18f, size.height * 0.16f),
        )
        drawCircle(
            color = Color(0xFF7B5BFF).copy(alpha = 0.12f),
            radius = size.minDimension * 0.25f,
            center = Offset(size.width * 0.82f, size.height * 0.2f),
        )
        drawCircle(
            color = Color(0xFFF8C15C).copy(alpha = 0.08f),
            radius = size.minDimension * 0.4f,
            center = Offset(size.width * 0.75f, size.height * 0.85f),
        )

        val orbitColor = Color(0xFF6EA8FF).copy(alpha = 0.14f)
        drawCircle(
            color = orbitColor,
            radius = size.minDimension * 0.28f,
            center = Offset(size.width * 0.72f, size.height * 0.72f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4.dp.toPx()),
        )
        drawCircle(
            color = orbitColor,
            radius = size.minDimension * 0.18f,
            center = Offset(size.width * 0.72f, size.height * 0.72f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()),
        )

        repeat(18) { index ->
            val fraction = index / 18f
            drawLine(
                color = Color(0xFFE8EEF9).copy(alpha = 0.05f),
                start = Offset(size.width * fraction, 0f),
                end = Offset(size.width * fraction, size.height * 0.4f),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
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
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(SolarLabTestTags.STATUS_LINE),
                )
                uiState.detailLine?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            text = if (compact) "Rust-authoritative stage shell" else "Canonical Android shell",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = "Solar Gravity Lab",
            style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(SolarLabTestTags.TITLE),
        )
        Text(
            text = if (compact) {
                "A stage-first mobile shell for exploring the live solar system before dropping into the deeper controls."
            } else {
                "A host-first mobile surface for the Rust runtime boundary, tuned for clarity, confidence, and touch use."
            },
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    var showTrackedOrbits by rememberSaveable { mutableStateOf(true) }
    var trackedOrbitLimit by rememberSaveable { mutableStateOf(5) }
    var renderSurfaceView by remember { mutableStateOf<VulkanPacketRenderSurfaceView?>(null) }
    val quickFocusEntries = SolarLabTeachingCatalog.entries.filter { entry ->
        entry.bodyId in setOf("sun", "earth", "moon", "mars", "jupiter") &&
            uiState.renderFrame?.bodies?.any { body -> body.bodyId == entry.bodyId } == true
    }

    LabPanel(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Solar system stage",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (compactStage) {
                            "Live overhead teaching view from Rust-authoritative scene packets."
                        } else {
                            "Live overhead orbital view from Rust-authoritative scene packets."
                        },
                        style = if (compactStage) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (compactStage) {
                            "Tap to focus, pinch to zoom, drag to pan, double-tap to reset."
                        } else {
                            "Pinch to zoom, drag to pan, tap a body to focus, double-tap to reset the view."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
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
                                Color(0xFF08111C),
                                Color(0xFF0B1827),
                                Color(0xFF13243A),
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
                            view.setOnBodyTapped { bodyId ->
                                onFocusBodyRequested(bodyId)
                            }
                            view.submitFrame(
                                frame = uiState.renderFrame,
                                highlightedTrailSourceBodyIds = uiState.recentFocusedBodyIds,
                            )
                        },
                    )

                    if (compactStage) {
                        StatusPill(
                            label = uiState.focusedBodyId?.let { "Focused: $it" } ?: "Tap a body to focus",
                            tone = if (uiState.focusedBodyId != null) {
                                ShellNoticeTone.Positive
                            } else {
                                ShellNoticeTone.Neutral
                            },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp),
                        )
                    } else {
                        TrackedOrbitHistoryPanel(
                            trackedBodyIds = uiState.recentFocusedBodyIds.take(trackedOrbitLimit),
                            showTrackedOrbits = showTrackedOrbits,
                            trackedOrbitLimit = trackedOrbitLimit,
                            onShowTrackedOrbitsChange = { showTrackedOrbits = it },
                            onTrackedOrbitLimitChange = { trackedOrbitLimit = it },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp),
                        )

                        RenderStageSummaryCard(
                            uiState = uiState,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
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

            if (uiState.renderFrame != null && compactStage) {
                StageInteractionDock(
                    focusedBodyId = uiState.focusedBodyId,
                    quickFocusEntries = quickFocusEntries,
                    onFocusBodyRequested = onFocusBodyRequested,
                    onZoomIn = { renderSurfaceView?.zoomBy(1.35f) },
                    onZoomOut = { renderSurfaceView?.zoomBy(0.74f) },
                    onResetView = { renderSurfaceView?.resetViewTransform() },
                )
                TrackedOrbitHistoryPanel(
                    trackedBodyIds = uiState.recentFocusedBodyIds.take(trackedOrbitLimit),
                    showTrackedOrbits = showTrackedOrbits,
                    trackedOrbitLimit = trackedOrbitLimit,
                    onShowTrackedOrbitsChange = { showTrackedOrbits = it },
                    onTrackedOrbitLimitChange = { trackedOrbitLimit = it },
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = uiState.renderStatus.sceneRevision ?: uiState.renderFrame?.sceneRevision.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = "${uiState.renderStatus.renderedBodyCount} bodies · ${uiState.renderStatus.renderedTracerCount} tracers · ${uiState.renderStatus.renderedTrailCount} trails",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            uiState.renderStatus.summary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.renderStatus.readiness == RenderHostReadiness.Unavailable) {
                Text(
                    text = "Showing the last decoded frame while packet export catches up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = focusedBodyId?.let { "Stage tools · focused on $it" } ?: "Stage tools",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
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
private fun TrackedOrbitHistoryPanel(
    trackedBodyIds: List<String>,
    showTrackedOrbits: Boolean,
    trackedOrbitLimit: Int,
    onShowTrackedOrbitsChange: (Boolean) -> Unit,
    onTrackedOrbitLimitChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Tracked orbits",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
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
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    trackedBodyIds.forEachIndexed { index, bodyId ->
                        Text(
                            text = "${index + 1}. $bodyId",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                Text(
                    text = "Tracked orbits are hidden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
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
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.46f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            Color.Transparent,
                        )
                    )
                ),
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
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
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entry.bodyId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (entry.aliases.isNotEmpty()) {
                    Text(
                        text = entry.aliases.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurface,
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
                color = MaterialTheme.colorScheme.onSurface,
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            entries.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(0.38f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (presentation.enabled) {
                    "Local shell diagnostics are mirrored to logcat with tag $DEVELOPER_TELEMETRY_LOG_TAG and can be copied or shared from here."
                } else {
                    "Developer telemetry is disabled for this build."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            streamingTarget?.let { target ->
                Text(
                    text = "Remote stream target: $target",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
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
                    color = MaterialTheme.colorScheme.secondary,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) {
        Text(
            text = event.toDisplayLine(locale = Locale.getDefault()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        shadowElevation = 12.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
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
    ShellNoticeTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    ShellNoticeTone.Positive -> Color(0xFF153728).copy(alpha = 0.92f)
    ShellNoticeTone.Caution -> Color(0xFF43310F).copy(alpha = 0.92f)
    ShellNoticeTone.Critical -> Color(0xFF4A1820).copy(alpha = 0.94f)
}

@Composable
private fun ShellNoticeTone.borderColor(): Color = when (this) {
    ShellNoticeTone.Neutral -> MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    ShellNoticeTone.Positive -> Color(0xFF4FD39A).copy(alpha = 0.42f)
    ShellNoticeTone.Caution -> Color(0xFFF8C15C).copy(alpha = 0.42f)
    ShellNoticeTone.Critical -> Color(0xFFFF8B8B).copy(alpha = 0.42f)
}

@Composable
private fun ShellNoticeTone.textColor(): Color = when (this) {
    ShellNoticeTone.Neutral -> MaterialTheme.colorScheme.onSurface
    ShellNoticeTone.Positive -> Color(0xFFD6FFE9)
    ShellNoticeTone.Caution -> Color(0xFFFFECB8)
    ShellNoticeTone.Critical -> Color(0xFFFFD7DC)
}

private fun closeEnough(left: Double, right: Double): Boolean = kotlin.math.abs(left - right) < 0.0001

private fun Double.asEpochLabel(): String = String.format(Locale.US, "%,.1f s", this)

private fun Double.asRateLabel(): String = String.format(Locale.US, "%.2fx", this)

private fun Double.asPlaybackLabel(): String = String.format(Locale.US, "%.2fx real-time", this)
