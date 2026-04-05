package com.sednalabs.solarlab

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.RuntimeObserverMode
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellNoticeTone
import com.sednalabs.solarlab.runtime.ShellUiState
import com.sednalabs.solarlab.runtime.SnapshotPresentation
import com.sednalabs.solarlab.ui.theme.SolarLabTheme
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SolarLabApp(runtimeFacade: RuntimeFacade) {
    val uiState by runtimeFacade.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(runtimeFacade) {
        runtimeFacade.startSession()
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
                        HeroPanel(
                            uiState = uiState,
                            onRefresh = {
                                scope.launch {
                                    runtimeFacade.refresh()
                                }
                            },
                            canRefresh = canRefresh,
                        )

                        if (isWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                RenderStagePanel(
                                    uiState = uiState,
                                    modifier = Modifier.weight(1.35f),
                                    onRefresh = {
                                        scope.launch {
                                            runtimeFacade.refresh()
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
    onRefresh: () -> Unit,
    canRefresh: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BoxWithConstraints {
                val stackedHeader = maxWidth < 560.dp
                if (stackedHeader) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        HeroCopy()
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
                            HeroCopy()
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
private fun HeroCopy() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Canonical Android shell",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = "Solar Gravity Lab",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(SolarLabTestTags.TITLE),
        )
        Text(
            text = "A host-first mobile surface for the Rust runtime boundary, tuned for clarity, confidence, and touch use.",
            style = MaterialTheme.typography.bodyLarge,
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
private fun RenderStagePanel(
    uiState: ShellUiState,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    canRefresh: Boolean,
) {
    LabPanel(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Render stage",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Host-owned packet presentation over the Rust scene export contract.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    .heightIn(min = 320.dp, max = 560.dp)
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
                            VulkanPacketRenderSurfaceView(context = context)
                        },
                        update = { view ->
                            view.submitFrame(uiState.renderFrame)
                        },
                    )

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = uiState.renderStatus.sceneRevision ?: uiState.renderFrame.sceneRevision,
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
                } else {
                    EmptyRenderStage(
                        uiState = uiState,
                        onRefresh = onRefresh,
                        canRefresh = canRefresh,
                    )
                }
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

@Composable
private fun ControlDeck(
    uiState: ShellUiState,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onCommand: (RuntimeCommand) -> Unit,
) {
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
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(label)
        },
    )
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
                "Runtime backend" to (uiState.backendSummary ?: "Pending runtime info"),
            ),
        )

        DetailCard(
            title = "Authoritative snapshot",
            entries = uiState.snapshot.toSnapshotEntries(),
        )

        DetailCard(
            title = "Render host",
            entries = listOf(
                "Readiness" to uiState.renderStatus.readiness.displayLabel(),
                "Scene revision" to (uiState.renderStatus.sceneRevision ?: "Waiting for packet"),
                "Decoded frame" to "${uiState.renderStatus.renderedBodyCount} bodies / ${uiState.renderStatus.renderedTracerCount} tracers / ${uiState.renderStatus.renderedTrailCount} trails",
                "Packet summary" to (uiState.renderStatus.summary ?: "No packet summary yet"),
                "Issue" to (uiState.renderStatus.issue ?: "None"),
            ),
            valueTags = mapOf("Packet summary" to SolarLabTestTags.RENDER_PACKET_SUMMARY),
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
            "Branch" to "Pending",
        )
    }

    return listOf(
        "Scenario" to scenarioId,
        "Branch" to activeBranchId,
        "Epoch" to epochSeconds.asEpochLabel(),
        "Bodies" to bodyCount.toString(),
        "Playback" to if (paused) "Paused" else simSecondsPerRealSecond.asPlaybackLabel(),
        "Observer" to observerModeLabel,
    )
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
