package com.sednalabs.solarlab

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.TimelineMode
import com.graciousgazelles.solarlab.feature.lab.LabFrame
import com.graciousgazelles.solarlab.feature.lab.LabFrameListener
import com.graciousgazelles.solarlab.feature.lab.LabSession
import com.graciousgazelles.solarlab.feature.lab.TimelineStatus
import com.graciousgazelles.solarlab.feature.lab.render.RenderInteractionListener
import com.graciousgazelles.solarlab.feature.lab.render.RenderProcessingMode
import com.graciousgazelles.solarlab.feature.lab.render.SceneInteractionMode
import com.graciousgazelles.solarlab.feature.lab.render.SolarSystemRenderHostView
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderLayerOptions
import com.graciousgazelles.solarlab.render.core.TraceLayerMode
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.ui.theme.SolarLabTheme
import kotlinx.coroutines.flow.Flow
import kotlin.math.sqrt

private val StageBackdrop = Color(0xFF02050B)
private val OverlayPanel = Color(0xE6070D18)
private val ControlRail = Color(0xEA07101A)
private val OverlayStroke = Color(0x5C76F7FF)
private val PrimaryAction = Color(0xFF17344A)
private val SecondaryAction = Color(0xFF0E1B29)
private val TimelineText = Color(0xFF76F7FF)
private val MissionText = Color(0xFFEAFBFF)
private val SelectionText = Color(0xFFFFD36B)
private val HintText = Color(0xC29FB6C9)
private val BodyText = Color(0xE6E8F7FF)
private val SurfaceText = Color(0xFFF4FBFF)

private val StageCompactWidthBreakpoint = 720.dp
private val StageRendererTelemetryTailRegex =
    Regex("""\s*(?:[·|]\s*)?(?:rev=|A=|TN=|TM=|TF=|TL=|bytes=|paths[=\[].*|compute[=\[].*|gp[=\[].*|cp[=\[].*|cam[=\[].*).*$""")
private val StageRendererWhitespaceRegex = Regex("""\s+""")

private const val PLACEMENT_DRAG_THRESHOLD_PX: Float = 24f
private const val PLACEMENT_DRAG_LOOKAHEAD_SECONDS: Double = 30.0 * PhysicalConstants.DAY_SECONDS
private const val STAGE_BACKEND_HUD_STATUS_CHAR_LIMIT = 120

/**
 * Restored stage-first client that brings the interactive feature-lab surface back into the
 * current Android shell.
 *
 * Recovery slice two restores sandbox authoring parity on top of the stage-first client:
 * add object, place-on-scene, edit selected, and delete selected are all available again.
 */
internal enum class StageFirstExperienceMode {
    LOCAL_SANDBOX,
    RUNTIME_MIRROR,
}

internal data class LoadScenarioSemanticRouting(
    val shouldEnterRuntimeMirror: Boolean,
    val shouldDeliverAction: Boolean,
)

internal fun resolveLoadScenarioSemanticRouting(
    runtimeMirrorAvailable: Boolean,
    currentlyInRuntimeMirror: Boolean,
    scenarioKnown: Boolean,
): LoadScenarioSemanticRouting = LoadScenarioSemanticRouting(
    shouldEnterRuntimeMirror = runtimeMirrorAvailable && scenarioKnown,
    shouldDeliverAction = scenarioKnown || currentlyInRuntimeMirror,
)

internal data class PendingSemanticAction(
    val token: Long,
    val action: SolarLabSemanticAction,
)

@Composable
internal fun StageFirstSandboxApp(
    runtimeFacade: RuntimeFacade? = null,
    ensureRuntimeStarted: (() -> Unit)? = null,
    semanticActions: Flow<SolarLabSemanticAction> = SolarLabSemanticActionBridge.commands,
    experienceModeState: MutableState<StageFirstExperienceMode>? = null,
    runtimeMirrorMountedState: MutableState<Boolean>? = null,
) {
    val localExperienceModeState = rememberSaveable { mutableStateOf(StageFirstExperienceMode.LOCAL_SANDBOX) }
    val resolvedExperienceModeState = experienceModeState ?: localExperienceModeState
    var experienceMode by resolvedExperienceModeState
    var nextSemanticToken by remember { mutableStateOf(0L) }
    var pendingSemanticAction by remember { mutableStateOf<PendingSemanticAction?>(null) }
    val runtimeMirrorAvailable = runtimeFacade != null && ensureRuntimeStarted != null

    LaunchedEffect(semanticActions, runtimeMirrorAvailable) {
        semanticActions.collect { action ->
            when (action) {
                SolarLabSemanticAction.OpenImmersive -> {
                    if (runtimeMirrorAvailable) {
                        experienceMode = StageFirstExperienceMode.RUNTIME_MIRROR
                    }
                    SolarLabSemanticActionBridge.clearPendingReplay()
                }

                SolarLabSemanticAction.ReturnToSandbox -> {
                    experienceMode = StageFirstExperienceMode.LOCAL_SANDBOX
                    SolarLabSemanticActionBridge.clearPendingReplay()
                }

                is SolarLabSemanticAction.LoadScenario -> {
                    val scenarioKnown = runtimeFacade
                        ?.scenarioPacks
                        ?.any { it.scenarioId == action.scenarioId }
                        ?: false
                    val routing = resolveLoadScenarioSemanticRouting(
                        runtimeMirrorAvailable = runtimeMirrorAvailable,
                        currentlyInRuntimeMirror = experienceMode == StageFirstExperienceMode.RUNTIME_MIRROR,
                        scenarioKnown = scenarioKnown,
                    )
                    if (routing.shouldEnterRuntimeMirror) {
                        experienceMode = StageFirstExperienceMode.RUNTIME_MIRROR
                    }
                    if (routing.shouldDeliverAction) {
                        nextSemanticToken += 1L
                        pendingSemanticAction = PendingSemanticAction(
                            token = nextSemanticToken,
                            action = action,
                        )
                    }
                    SolarLabSemanticActionBridge.clearPendingReplay()
                }

                else -> {
                    nextSemanticToken += 1L
                    pendingSemanticAction = PendingSemanticAction(
                        token = nextSemanticToken,
                        action = action,
                    )
                    SolarLabSemanticActionBridge.clearPendingReplay()
                }
            }
        }
    }

    when {
        !runtimeMirrorAvailable || experienceMode == StageFirstExperienceMode.LOCAL_SANDBOX -> {
            SideEffect {
                runtimeMirrorMountedState?.value = false
            }
            StageFirstSandboxLocalExperience(
                pendingSemanticAction = pendingSemanticAction,
                onEnterRuntimeMirror = if (runtimeMirrorAvailable) {
                    { experienceMode = StageFirstExperienceMode.RUNTIME_MIRROR }
                } else {
                    null
                },
            )
        }

        else -> StageFirstRuntimeMirrorExperience(
            runtimeFacade = runtimeFacade,
            ensureRuntimeStarted = ensureRuntimeStarted,
            pendingSemanticAction = pendingSemanticAction,
            onReturnToSandbox = { experienceMode = StageFirstExperienceMode.LOCAL_SANDBOX },
            runtimeMirrorMountedState = runtimeMirrorMountedState,
        )
    }
}

/**
 * Restored stage-first client that brings the interactive feature-lab surface back into the
 * current Android shell.
 *
 * Recovery slice two restores sandbox authoring parity on top of the stage-first client:
 * add object, place-on-scene, edit selected, and delete selected are all available again.
 */
@Composable
private fun StageFirstSandboxLocalExperience(
    pendingSemanticAction: PendingSemanticAction?,
    onEnterRuntimeMirror: (() -> Unit)?,
) {
    SolarLabTheme {
        val context = LocalContext.current.applicationContext
        val lifecycleOwner = LocalLifecycleOwner.current

        var latestFrame by remember { mutableStateOf<LabFrame?>(null) }
        var backendStatus by remember { mutableStateOf("Preparing immersive Vulkan stage.") }
        var selectedBodyId by remember { mutableStateOf<String?>(null) }
        var observerMode by remember { mutableStateOf(ObserverMode.FREE) }
        var collisionMode by remember { mutableStateOf(CollisionMode.MERGE) }
        var renderProcessingMode by remember { mutableStateOf(HostedDebugMode.initialRenderProcessingMode) }
        var chromeModeName by rememberSaveable { mutableStateOf(StageChromeMode.COLLAPSED.name) }
        var traceLayerModeName by rememberSaveable { mutableStateOf(TraceLayerMode.FOCUS.name) }
        var isRunning by remember { mutableStateOf(!HostedDebugMode.enabled) }
        var resumeSimulationOnForeground by remember { mutableStateOf(false) }
        var resumeSimulationAfterModalInteraction by remember { mutableStateOf(false) }
        var searchVisible by rememberSaveable { mutableStateOf(false) }
        var debugVisible by rememberSaveable { mutableStateOf(false) }
        var immersivePromptVisible by rememberSaveable { mutableStateOf(false) }
        var pendingAddDraft by remember { mutableStateOf<EditableBodyDraft?>(null) }
        var bodyEditorState by remember { mutableStateOf<BodyEditorDialogState?>(null) }
        var renderHostView by remember { mutableStateOf<SolarSystemRenderHostView?>(null) }
        var appliedSemanticActionToken by remember { mutableStateOf<Long?>(null) }

        val frameListener = remember {
            object : LabFrameListener {
                override fun onLabFrame(frame: LabFrame) {
                    latestFrame = frame
                }
            }
        }
        val session = remember(context) {
            LabSession.createDefault(context = context, listener = frameListener)
        }
        val chromeMode = stageChromeModeFromName(chromeModeName)
        val traceLayerMode = traceLayerModeFromName(traceLayerModeName)
        val renderLayerOptions = remember(traceLayerMode, selectedBodyId) {
            RenderLayerOptions(
                traceLayerMode = traceLayerMode,
                focusedBodyIds = setOfNotNull(selectedBodyId),
            )
        }

        val prepareForModalInteraction = {
            resumeSimulationAfterModalInteraction = session.isRunning()
            if (resumeSimulationAfterModalInteraction) {
                session.pause()
                isRunning = false
            }
        }
        val maybeResumeAfterModalInteraction = {
            if (pendingAddDraft != null || bodyEditorState != null) {
                Unit
            } else {
                if (resumeSimulationAfterModalInteraction && !session.isRunning()) {
                    session.start()
                    isRunning = true
                }
                resumeSimulationAfterModalInteraction = false
            }
        }
        val cancelPendingPlacement: (Boolean) -> Unit = { shouldResume ->
            pendingAddDraft = null
            renderHostView?.setInteractionMode(SceneInteractionMode.NAVIGATE_AND_SELECT)
            if (shouldResume) {
                maybeResumeAfterModalInteraction()
            }
        }
        val openAddBodyEditor = {
            searchVisible = false
            debugVisible = false
            prepareForModalInteraction()
            bodyEditorState = BodyEditorDialogState(
                draft = EditableBodyDraft.newDefault(),
                isNewBody = true,
            )
        }

        BackHandler(enabled = searchVisible || debugVisible || immersivePromptVisible || pendingAddDraft != null || bodyEditorState != null) {
            when {
                bodyEditorState != null -> {
                    bodyEditorState = null
                    if (pendingAddDraft == null) {
                        maybeResumeAfterModalInteraction()
                    }
                }

                searchVisible -> searchVisible = false
                pendingAddDraft != null -> cancelPendingPlacement(true)
                immersivePromptVisible -> immersivePromptVisible = false
                debugVisible -> debugVisible = false
            }
        }

        fun focusAndFrameBody(bodyId: String) {
            selectedBodyId = bodyId
            observerMode = ObserverMode.FOLLOW_SELECTED
            renderHostView?.focusAndFrameBody(bodyId, ObserverMode.FOLLOW_SELECTED)
        }

        DisposableEffect(session) {
            collisionMode = session.collisionMode()
            session.dispatchCurrentFrame()
            if (HostedDebugMode.enabled) {
                isRunning = false
            } else {
                session.start()
                isRunning = true
            }
            onDispose {
                renderHostView?.release()
                session.release()
            }
        }

        DisposableEffect(lifecycleOwner, session, renderHostView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        renderHostView?.onHostResume()
                        if (resumeSimulationOnForeground && !session.isRunning()) {
                            session.start()
                            isRunning = true
                        }
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        resumeSimulationOnForeground = session.isRunning()
                        renderHostView?.onHostPause()
                        if (resumeSimulationOnForeground) {
                            session.pause()
                            isRunning = false
                        }
                    }

                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        LaunchedEffect(renderHostView) {
            renderHostView?.onHostResume()
        }

        LaunchedEffect(latestFrame?.snapshot, renderHostView, renderLayerOptions) {
            latestFrame?.snapshot?.let { snapshot ->
                renderHostView?.setRenderLayerOptions(renderLayerOptions)
                renderHostView?.submitSnapshot(snapshot)
            }
        }

        LaunchedEffect(selectedBodyId, observerMode, renderProcessingMode, pendingAddDraft, renderHostView) {
            renderHostView?.setSelectedBodyId(selectedBodyId)
            renderHostView?.setObserverMode(observerMode)
            renderHostView?.setProcessingMode(renderProcessingMode)
            renderHostView?.setPlacementPlaneZ(pendingAddDraft?.positionM?.z ?: 0.0)
            renderHostView?.setInteractionMode(
                if (pendingAddDraft == null) {
                    SceneInteractionMode.NAVIGATE_AND_SELECT
                } else {
                    SceneInteractionMode.PLACE_BODY
                },
            )
        }

        LaunchedEffect(latestFrame, selectedBodyId) {
            if (selectedBodyId != null && latestFrame?.snapshot?.bodies?.none { it.id == selectedBodyId } == true) {
                selectedBodyId = null
                observerMode = ObserverMode.FREE
            }
        }

        LaunchedEffect(pendingSemanticAction?.token, latestFrame, renderHostView) {
            if (pendingSemanticAction?.token == appliedSemanticActionToken) {
                return@LaunchedEffect
            }
            when (val action = pendingSemanticAction?.action) {
                is SolarLabSemanticAction.FocusBody -> {
                    val resolvedBodyId = resolveSandboxSemanticBodyId(
                        frame = latestFrame,
                        bodyQuery = action.bodyQuery,
                    ) ?: return@LaunchedEffect
                    selectedBodyId = resolvedBodyId
                    observerMode = ObserverMode.FOLLOW_SELECTED
                    searchVisible = false
                    debugVisible = false
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

        val frame = latestFrame
        val selectionCard = remember(frame, selectedBodyId, pendingAddDraft) {
            buildSelectionCard(
                frame = frame,
                selectedBodyId = selectedBodyId,
                pendingAddDraft = pendingAddDraft,
            )
        }
        val timelineText = remember(frame) { buildTimelineText(frame?.timeline) }
        val diagnosticsText = remember(frame) { buildDiagnosticsText(frame) }
        val interactionHintText = remember(pendingAddDraft) {
            if (pendingAddDraft == null) {
                "Pinch through Close→Deep scale bands, drag to pan, and use two fingers to orbit/tilt the stage. Tap a body to select it."
            } else {
                "Placement armed at the draft Z plane. Tap to place the new body, or drag to seed initial velocity."
            }
        }
        val authoringStatusText = remember(pendingAddDraft) {
            pendingAddDraft?.let { draft ->
                "Placement armed for ${draft.name} · ${draft.prettyRoleLabel()} · ${draft.prettyCategoryLabel()}"
            } ?: "No pending authoring action."
        }
        val editorState = bodyEditorState
        val selectedBody = remember(frame, selectedBodyId) {
            frame?.snapshot?.bodies?.firstOrNull { it.id == selectedBodyId }
        }
        val backendHudStatus = remember(backendStatus) {
            compactStageBackendHudStatusText(backendStatus)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StageBackdrop),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    SolarSystemRenderHostView(viewContext).also { view ->
                        view.setOnBackendStatusChangedListener { status ->
                            backendStatus = status.message
                        }
                        view.setInteractionListener(
                            object : RenderInteractionListener {
                                override fun onBodySelectionChanged(bodyId: String?) {
                                    if (pendingAddDraft != null) {
                                        return
                                    }
                                    selectedBodyId = bodyId
                                    if (bodyId == null && observerMode != ObserverMode.FREE) {
                                        observerMode = ObserverMode.FREE
                                    }
                                }

                                override fun onPlacementGesture(
                                    startWorldPositionM: Vector3d,
                                    endWorldPositionM: Vector3d,
                                    gestureDistancePx: Float,
                                ) {
                                    val draft = pendingAddDraft ?: return
                                    val placedPosition = Vector3d(
                                        x = startWorldPositionM.x,
                                        y = startWorldPositionM.y,
                                        z = draft.positionM.z,
                                    )
                                    val velocityFromDrag = if (gestureDistancePx >= PLACEMENT_DRAG_THRESHOLD_PX) {
                                        val delta = endWorldPositionM - startWorldPositionM
                                        Vector3d(
                                            x = draft.velocityMps.x + (delta.x / PLACEMENT_DRAG_LOOKAHEAD_SECONDS),
                                            y = draft.velocityMps.y + (delta.y / PLACEMENT_DRAG_LOOKAHEAD_SECONDS),
                                            z = draft.velocityMps.z,
                                        )
                                    } else {
                                        draft.velocityMps
                                    }
                                    val placedBody = draft.toBodyState(
                                        positionOverrideM = placedPosition,
                                        velocityOverrideMps = velocityFromDrag,
                                    )
                                    pendingAddDraft = null
                                    view.setInteractionMode(SceneInteractionMode.NAVIGATE_AND_SELECT)
                                    session.addBody(placedBody)
                                    selectedBodyId = placedBody.id
                                    maybeResumeAfterModalInteraction()
                                }
                            },
                        )
                        renderHostView = view
                    }
                },
                update = { view ->
                    renderHostView = view
                },
            )

            StageOverlay(
                timelineText = timelineText,
                selectionCard = selectionCard,
                backendStatus = backendHudStatus,
                interactionHintText = interactionHintText,
                collisionMode = collisionMode,
                renderProcessingMode = renderProcessingMode,
                chromeMode = chromeMode,
                traceLayerMode = traceLayerMode,
                isRunning = isRunning,
                canStepBackward = pendingAddDraft == null && !isRunning && (frame?.timeline?.canStepBackward == true),
                canStepForward = pendingAddDraft == null && !isRunning,
                canStepOnce = pendingAddDraft == null && !isRunning,
                observerMode = observerMode,
                observerButtonEnabled = pendingAddDraft == null && (selectedBodyId != null || observerMode != ObserverMode.FREE),
                searchVisible = searchVisible,
                debugVisible = debugVisible,
                searchEnabled = pendingAddDraft == null,
                addButtonLabel = if (pendingAddDraft == null) "Add object" else "Cancel add",
                editButtonEnabled = selectedBodyId != null && pendingAddDraft == null,
                authoringActive = pendingAddDraft != null,
                modeButtonLabel = "Immersive",
                onToggleMode = if (onEnterRuntimeMirror != null) {
                    {
                        searchVisible = false
                        debugVisible = false
                        immersivePromptVisible = true
                    }
                } else {
                    null
                },
                onToggleChrome = {
                    chromeModeName = chromeMode.toggle().name
                },
                onCycleTraceLayer = {
                    traceLayerModeName = traceLayerMode.next().name
                },
                onSearch = { searchVisible = true },
                onDebug = { debugVisible = true },
                onAddObject = {
                    if (pendingAddDraft != null) {
                        cancelPendingPlacement(true)
                    } else {
                        openAddBodyEditor()
                    }
                },
                onEditSelected = {
                    selectedBody?.let { body ->
                        searchVisible = false
                        debugVisible = false
                        prepareForModalInteraction()
                        bodyEditorState = BodyEditorDialogState(
                            draft = EditableBodyDraft.fromBodyState(body),
                            isNewBody = false,
                        )
                    }
                },
                onStartPause = {
                    if (session.isRunning()) {
                        session.pause()
                        isRunning = false
                    } else {
                        session.start()
                        isRunning = true
                    }
                },
                onStepOnce = { session.stepOnce() },
                onBackStep = { session.jumpTimelineByStep(-1) },
                onForwardStep = { session.jumpTimelineByStep(1) },
                onCycleObserver = {
                    observerMode = when (observerMode) {
                        ObserverMode.FREE -> ObserverMode.FOLLOW_SELECTED
                        ObserverMode.FOLLOW_SELECTED -> ObserverMode.FOLLOW_SELECTED_HOST
                        ObserverMode.FOLLOW_SELECTED_HOST -> ObserverMode.FREE
                    }
                },
                onCycleStepQuantum = { session.cycleStepQuantum(+1) },
                onSlower = { session.cyclePlaybackSpeed(-1) },
                onFaster = { session.cyclePlaybackSpeed(+1) },
                onReset = {
                    searchVisible = false
                    debugVisible = false
                    bodyEditorState = null
                    pendingAddDraft = null
                    resumeSimulationAfterModalInteraction = false
                    selectedBodyId = null
                    observerMode = ObserverMode.FREE
                    session.resetDefault()
                    renderHostView?.resetScene()
                    renderHostView?.resetCamera()
                    renderHostView?.setInteractionMode(SceneInteractionMode.NAVIGATE_AND_SELECT)
                    isRunning = false
                },
                onToggleProcessingMode = {
                    renderProcessingMode = when (renderProcessingMode) {
                        RenderProcessingMode.DEFAULT -> RenderProcessingMode.LOW
                        RenderProcessingMode.LOW -> RenderProcessingMode.DEFAULT
                    }
                },
                onToggleCollisionMode = {
                    collisionMode = when (collisionMode) {
                        CollisionMode.NONE -> CollisionMode.MERGE
                        CollisionMode.MERGE -> CollisionMode.ELASTIC
                        CollisionMode.ELASTIC -> CollisionMode.FRAGMENTATION
                        CollisionMode.FRAGMENTATION -> CollisionMode.MERGE
                    }
                    session.setCollisionMode(collisionMode)
                },
                stepQuantumLabel = frame?.timeline?.stepQuantum?.label ?: session.stepQuantumPreset().label,
                speedLabel = frame?.timeline?.playbackSpeed?.label ?: session.playbackSpeedPreset().label,
            )
        }

        if (searchVisible) {
            SearchDialog(
                frame = frame,
                selectedBodyId = selectedBodyId,
                onDismiss = { searchVisible = false },
                onSelectBody = { bodyId ->
                    focusAndFrameBody(bodyId)
                    searchVisible = false
                },
            )
        }

        if (immersivePromptVisible) {
            AlertDialog(
                onDismissRequest = { immersivePromptVisible = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            immersivePromptVisible = false
                            onEnterRuntimeMirror?.invoke()
                        },
                        modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_IMMERSIVE_CONFIRM_BUTTON),
                    ) {
                        Text("Enter mission renderer")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { immersivePromptVisible = false },
                        modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_IMMERSIVE_CANCEL_BUTTON),
                    ) {
                        Text("Stay in sandbox")
                    }
                },
                title = { Text("Enter mission renderer?") },
                text = {
                    Text(
                        "Switch from the editable sandbox into the Rust-authoritative trajectory renderer. " +
                            "The live view keeps Vulkan rendering and acceleration telemetry visible while the sandbox remains ready for object editing.",
                    )
                },
            )
        }

        if (debugVisible) {
            DebugDialog(
                backendStatus = backendStatus,
                diagnosticsText = diagnosticsText,
                bodyCount = frame?.snapshot?.bodies?.size ?: 0,
                selectedBodyId = selectedBodyId,
                observerMode = observerMode,
                collisionMode = collisionMode,
                renderProcessingMode = renderProcessingMode,
                authoringStatusText = authoringStatusText,
                onDismiss = { debugVisible = false },
                onToggleProcessingMode = {
                    renderProcessingMode = when (renderProcessingMode) {
                        RenderProcessingMode.DEFAULT -> RenderProcessingMode.LOW
                        RenderProcessingMode.LOW -> RenderProcessingMode.DEFAULT
                    }
                },
                onToggleCollisionMode = {
                    collisionMode = when (collisionMode) {
                        CollisionMode.NONE -> CollisionMode.MERGE
                        CollisionMode.MERGE -> CollisionMode.ELASTIC
                        CollisionMode.ELASTIC -> CollisionMode.FRAGMENTATION
                        CollisionMode.FRAGMENTATION -> CollisionMode.MERGE
                    }
                    session.setCollisionMode(collisionMode)
                },
            )
        }

        if (editorState != null) {
            BodyEditorDialog(
                editorState = editorState,
                onDismiss = {
                    bodyEditorState = null
                    if (pendingAddDraft == null) {
                        maybeResumeAfterModalInteraction()
                    }
                },
                onSave = { draft ->
                    if (editorState.isNewBody) {
                        if (draft.placeOnSceneAfterSave) {
                            pendingAddDraft = draft
                            bodyEditorState = null
                            renderHostView?.setInteractionMode(SceneInteractionMode.PLACE_BODY)
                            maybeResumeAfterModalInteraction()
                        } else {
                            val body = draft.toBodyState()
                            session.addBody(body)
                            selectedBodyId = body.id
                            bodyEditorState = null
                            maybeResumeAfterModalInteraction()
                        }
                    } else {
                        val updatedBody = draft.toBodyState()
                        session.updateBody(updatedBody)
                        selectedBodyId = updatedBody.id
                        bodyEditorState = null
                        maybeResumeAfterModalInteraction()
                    }
                },
                onDelete = if (editorState.isNewBody) {
                    null
                } else {
                    {
                        editorState.draft.existingBodyId?.let { bodyId ->
                            session.removeBody(bodyId)
                            selectedBodyId = null
                            bodyEditorState = null
                            maybeResumeAfterModalInteraction()
                        }
                    }
                },
            )
        }
    }
}

internal fun compactStageBackendHudStatusText(value: String): String {
    val normalized = value
        .replace('\n', ' ')
        .replace(StageRendererWhitespaceRegex, " ")
        .trim()
    if (normalized.isBlank()) {
        return "Preparing immersive Vulkan stage."
    }

    val withoutTelemetry = normalized
        .replace(StageRendererTelemetryTailRegex, "")
        .trimEnd('.', ' ')

    val rendererSummary = when {
        withoutTelemetry.contains(
            "Vulkan SPIR-V graphics pipelines + compute compaction active",
            ignoreCase = true,
        ) -> withoutTelemetry.replace(
            oldValue = "Vulkan SPIR-V graphics pipelines + compute compaction active",
            newValue = "Vulkan SPIR-V + compute compaction active",
            ignoreCase = true,
        )

        withoutTelemetry.contains(
            "Vulkan SPIR-V graphics pipelines",
            ignoreCase = true,
        ) -> withoutTelemetry.replace(
            oldValue = "Vulkan SPIR-V graphics pipelines",
            newValue = "Vulkan SPIR-V graphics",
            ignoreCase = true,
        )

        withoutTelemetry.isNotBlank() -> withoutTelemetry
        else -> normalized
    }

    return if (rendererSummary.length <= STAGE_BACKEND_HUD_STATUS_CHAR_LIMIT) {
        rendererSummary
    } else {
        rendererSummary
            .take(STAGE_BACKEND_HUD_STATUS_CHAR_LIMIT)
            .trimEnd()
            .plus("... [truncated]")
    }
}

@Composable
private fun BoxScope.StageOverlay(
    timelineText: String,
    selectionCard: SelectionCardText,
    backendStatus: String,
    interactionHintText: String,
    collisionMode: CollisionMode,
    renderProcessingMode: RenderProcessingMode,
    chromeMode: StageChromeMode,
    traceLayerMode: TraceLayerMode,
    isRunning: Boolean,
    canStepBackward: Boolean,
    canStepForward: Boolean,
    canStepOnce: Boolean,
    observerMode: ObserverMode,
    observerButtonEnabled: Boolean,
    searchVisible: Boolean,
    debugVisible: Boolean,
    searchEnabled: Boolean,
    addButtonLabel: String,
    editButtonEnabled: Boolean,
    authoringActive: Boolean,
    modeButtonLabel: String? = null,
    onToggleMode: (() -> Unit)? = null,
    onToggleChrome: () -> Unit,
    onCycleTraceLayer: () -> Unit,
    onSearch: () -> Unit,
    onDebug: () -> Unit,
    onAddObject: () -> Unit,
    onEditSelected: () -> Unit,
    onStartPause: () -> Unit,
    onStepOnce: () -> Unit,
    onBackStep: () -> Unit,
    onForwardStep: () -> Unit,
    onCycleObserver: () -> Unit,
    onCycleStepQuantum: () -> Unit,
    onSlower: () -> Unit,
    onFaster: () -> Unit,
    onReset: () -> Unit,
    onToggleProcessingMode: () -> Unit,
    onToggleCollisionMode: () -> Unit,
    stepQuantumLabel: String,
    speedLabel: String,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactLayout = maxWidth < StageCompactWidthBreakpoint
        val actionButtons: @Composable () -> Unit = {
            StageControlsButton(
                label = "Hide controls",
                onClick = onToggleChrome,
            )
            modeButtonLabel?.takeIf { onToggleMode != null }?.let { label ->
                StageActionButton(
                    label = label,
                    onClick = { onToggleMode?.invoke() },
                    modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON),
                    secondary = true,
                )
            }
            StageActionButton(
                label = if (searchVisible) "Searching" else "Search",
                onClick = onSearch,
                modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON),
                emphasized = searchVisible,
                enabled = searchEnabled,
            )
            StageActionButton(
                label = if (debugVisible) "Debugging" else "Debug",
                onClick = onDebug,
                modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON),
                emphasized = debugVisible,
            )
        }
        val primaryControls: @Composable () -> Unit = {
            StageActionButton(
                label = if (isRunning) "Pause" else "Start",
                onClick = onStartPause,
                emphasized = isRunning,
                enabled = !authoringActive,
            )
            StageActionButton(label = "Step once", onClick = onStepOnce, enabled = canStepOnce)
            StageActionButton(label = "Back step", onClick = onBackStep, enabled = canStepBackward)
            StageActionButton(label = "Forward step", onClick = onForwardStep, enabled = canStepForward)
            StageActionButton(
                label = observerMode.displayLabel(),
                onClick = onCycleObserver,
                enabled = observerButtonEnabled,
            )
            StageActionButton(label = "Reset", onClick = onReset)
        }
        val secondaryControls: @Composable () -> Unit = {
            StageActionButton(
                label = addButtonLabel,
                onClick = onAddObject,
                modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_ADD_OBJECT_BUTTON),
                emphasized = authoringActive,
            )
            StageActionButton(
                label = "Edit selected",
                onClick = onEditSelected,
                enabled = editButtonEnabled,
            )
            StageActionButton(label = "Step $stepQuantumLabel", onClick = onCycleStepQuantum, enabled = !authoringActive)
            StageActionButton(label = "Slower", onClick = onSlower, enabled = !authoringActive)
            StageActionButton(label = "Faster · $speedLabel", onClick = onFaster, enabled = !authoringActive)
            StageTraceLayerButton(
                mode = traceLayerMode,
                compact = compactLayout,
                onClick = onCycleTraceLayer,
                enabled = !authoringActive,
            )
            StageActionButton(
                label = renderProcessingMode.displayLabel(),
                onClick = onToggleProcessingMode,
                secondary = true,
            )
            StageActionButton(
                label = collisionMode.displayLabel(),
                onClick = onToggleCollisionMode,
                secondary = true,
                enabled = !authoringActive,
            )
        }

        if (chromeMode == StageChromeMode.COLLAPSED) {
            StagePanel(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .widthIn(max = if (compactLayout) 360.dp else 420.dp)
                    .testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StageTrajectoryGlyph(
                        orbitColor = TimelineText,
                        probeColor = SelectionText,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = timelineText,
                            color = TimelineText,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                        Text(
                            text = selectionCard.title,
                            modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE),
                            color = SelectionText,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                        Text(
                            text = selectionCard.eyebrow,
                            color = MissionText,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (chromeMode == StageChromeMode.EXPANDED) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (compactLayout) {
                    StagePanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL),
                    ) {
                        Text(
                            text = selectionCard.eyebrow,
                            color = MissionText,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = timelineText,
                            color = TimelineText,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = selectionCard.title,
                            modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE),
                            color = SelectionText,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = selectionCard.detail,
                            color = HintText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    StageFloatingActionRow(
                        modifier = Modifier.fillMaxWidth(),
                        content = actionButtons,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        StagePanel(
                            modifier = Modifier
                                .weight(1f)
                                .testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL),
                        ) {
                            Text(
                                text = selectionCard.eyebrow,
                                color = MissionText,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = timelineText,
                                color = TimelineText,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = selectionCard.title,
                                modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE),
                                color = SelectionText,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = selectionCard.detail,
                                color = HintText,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            actionButtons()
                        }
                    }
                }
            }
        }

        if (chromeMode == StageChromeMode.COLLAPSED) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StageControlRail(compact = true) {
                    StageActionButton(
                        label = if (isRunning) "Pause" else "Start",
                        onClick = onStartPause,
                        emphasized = isRunning,
                        enabled = !authoringActive,
                        dense = true,
                    )
                    StageActionButton(label = "Slow", onClick = onSlower, enabled = !authoringActive, dense = true)
                    StageTraceLayerButton(
                        mode = traceLayerMode,
                        compact = true,
                        onClick = onCycleTraceLayer,
                        enabled = !authoringActive,
                        dense = true,
                    )
                    StageActionButton(label = "Fast", onClick = onFaster, enabled = !authoringActive, dense = true)
                    StageControlsButton(
                        label = "More",
                        onClick = onToggleChrome,
                        dense = true,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (compactLayout) {
                    StageControlRail(compact = true) {
                        primaryControls()
                        secondaryControls()
                    }
                } else {
                    StageControlRail(content = primaryControls)
                    StageControlRail(content = secondaryControls)
                }
                StagePanel(
                    modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_STATUS_PANEL),
                ) {
                    Text(
                        text = backendStatus,
                        color = TimelineText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = interactionHintText,
                        color = HintText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun StageFloatingActionRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun StageControlRail(
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        color = ControlRail,
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
        border = BorderStroke(1.dp, OverlayStroke),
    ) {
        val railModifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) 4.dp else 10.dp,
                vertical = if (compact) 7.dp else 10.dp,
            )
        if (compact) {
            FlowRow(
                modifier = railModifier,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        } else {
            Row(
                modifier = railModifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun StagePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = OverlayPanel,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, OverlayStroke),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
internal fun StageActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    secondary: Boolean = false,
    dense: Boolean = false,
) {
    val container = when {
        emphasized -> MaterialTheme.colorScheme.primaryContainer
        secondary -> SecondaryAction
        else -> PrimaryAction
    }
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        SurfaceText
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = if (dense) 36.dp else 46.dp)
            .sizeIn(minWidth = if (dense) 54.dp else 88.dp),
        enabled = enabled,
        shape = RoundedCornerShape(if (dense) 13.dp else 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = contentColor,
            disabledContainerColor = container.copy(alpha = 0.45f),
            disabledContentColor = contentColor.copy(alpha = 0.55f),
        ),
        contentPadding = PaddingValues(
            horizontal = if (dense) 6.dp else 16.dp,
            vertical = 0.dp,
        ),
    ) {
        Text(
            text = label,
            style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchDialog(
    frame: LabFrame?,
    selectedBodyId: String?,
    onDismiss: () -> Unit,
    onSelectBody: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val bodies = remember(frame, query) {
        val normalizedQuery = query.trim().lowercase()
        frame?.snapshot?.bodies
            ?.sortedWith(compareBy<BodyState> { it.name.lowercase() }.thenBy { it.id.lowercase() })
            ?.filter { body ->
                normalizedQuery.isBlank() ||
                    body.name.lowercase().contains(normalizedQuery) ||
                    body.id.lowercase().contains(normalizedQuery)
            }
            ?.take(24)
            .orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Find a body") },
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
                    if (bodies.isEmpty()) {
                        Text(
                            text = "No matching bodies in the current snapshot.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    bodies.forEach { body ->
                        val selected = body.id == selectedBodyId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else OverlayStroke),
                                    RoundedCornerShape(14.dp),
                                ),
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
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
                                        text = body.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "${body.prettyCategoryLabel()} · ${body.id}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { onSelectBody(body.id) },
                                    modifier = Modifier.testTag(SolarLabTestTags.stageFirstSearchFocusTag(body.id)),
                                ) {
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
private fun DebugDialog(
    backendStatus: String,
    diagnosticsText: String,
    bodyCount: Int,
    selectedBodyId: String?,
    observerMode: ObserverMode,
    collisionMode: CollisionMode,
    renderProcessingMode: RenderProcessingMode,
    authoringStatusText: String,
    onDismiss: () -> Unit,
    onToggleProcessingMode: () -> Unit,
    onToggleCollisionMode: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Stage debug") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = backendStatus,
                    color = TimelineText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Bodies: $bodyCount\nSelected: ${selectedBodyId ?: "none"}\n${observerMode.displayLabel()}\n${renderProcessingMode.displayLabel()}\n${collisionMode.displayLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = authoringStatusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StageActionButton(
                        label = renderProcessingMode.displayLabel(),
                        onClick = onToggleProcessingMode,
                        secondary = true,
                    )
                    StageActionButton(
                        label = collisionMode.displayLabel(),
                        onClick = onToggleCollisionMode,
                        secondary = true,
                    )
                }
                Text(
                    text = diagnosticsText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = BodyText,
                )
                Text(
                    text = "Pinch to zoom, drag to pan, tap a body to select it, or add a custom object and place it directly on the stage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BodyEditorDialog(
    editorState: BodyEditorDialogState,
    onDismiss: () -> Unit,
    onSave: (EditableBodyDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember(editorState) { mutableStateOf(editorState.draft.name) }
    var category by remember(editorState) { mutableStateOf(editorState.draft.category) }
    var gravitationalRole by remember(editorState) { mutableStateOf(editorState.draft.gravitationalRole) }
    var massKg by remember(editorState) { mutableStateOf(editorState.draft.massKg.toEditorString()) }
    var radiusM by remember(editorState) { mutableStateOf(editorState.draft.radiusM.toEditorString()) }
    var positionX by remember(editorState) { mutableStateOf(editorState.draft.positionM.x.toEditorString()) }
    var positionY by remember(editorState) { mutableStateOf(editorState.draft.positionM.y.toEditorString()) }
    var positionZ by remember(editorState) { mutableStateOf(editorState.draft.positionM.z.toEditorString()) }
    var velocityX by remember(editorState) { mutableStateOf(editorState.draft.velocityMps.x.toEditorString()) }
    var velocityY by remember(editorState) { mutableStateOf(editorState.draft.velocityMps.y.toEditorString()) }
    var velocityZ by remember(editorState) { mutableStateOf(editorState.draft.velocityMps.z.toEditorString()) }
    var colorHex by remember(editorState) {
        mutableStateOf(editorState.draft.colorArgb.toUInt().toString(16).uppercase().padStart(8, '0'))
    }
    var placeOnSceneAfterSave by remember(editorState) { mutableStateOf(editorState.draft.placeOnSceneAfterSave) }
    var errorMessage by remember(editorState) { mutableStateOf<String?>(null) }

    val colorPreview = remember(colorHex) {
        parseColorArgb(colorHex) ?: editorState.draft.colorArgb
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 680.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = if (editorState.isNewBody) "Add object" else "Edit ${editorState.draft.name}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (editorState.isNewBody) {
                        "Create a sandbox object, then either add it immediately or place it directly on the stage."
                    } else {
                        "Adjust the selected body without leaving the immersive stage."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StageEditorSectionLabel("Identity")
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Text(
                        text = "Category",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BodyCategory.entries.forEach { entry ->
                            FilterChip(
                                selected = category == entry,
                                onClick = {
                                    category = entry
                                    errorMessage = null
                                },
                                label = { Text(entry.prettyCategoryLabel()) },
                            )
                        }
                    }
                    Text(
                        text = "Gravity role",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GravitationalRole.entries.forEach { entry ->
                            FilterChip(
                                selected = gravitationalRole == entry,
                                onClick = {
                                    gravitationalRole = entry
                                    errorMessage = null
                                },
                                label = { Text(entry.prettyRoleLabel(includeHints = true)) },
                            )
                        }
                    }
                    if (!editorState.isNewBody && editorState.draft.existingHostBodyId != null) {
                        Text(
                            text = "Host linkage is preserved: ${editorState.draft.existingHostBodyId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    StageEditorSectionLabel("Physical")
                    StageEditorFieldRow(
                        first = EditorFieldState(
                            label = "Mass (kg)",
                            value = massKg,
                            onValueChange = {
                                massKg = it
                                errorMessage = null
                            },
                            keyboardType = KeyboardType.Decimal,
                        ),
                        second = EditorFieldState(
                            label = "Radius (m)",
                            value = radiusM,
                            onValueChange = {
                                radiusM = it
                                errorMessage = null
                            },
                            keyboardType = KeyboardType.Decimal,
                        ),
                    )

                    StageEditorSectionLabel("Position (m)")
                    StageEditorFieldRow(
                        first = EditorFieldState(
                            label = "X",
                            value = positionX,
                            onValueChange = {
                                positionX = it
                                errorMessage = null
                            },
                            keyboardType = KeyboardType.Decimal,
                        ),
                        second = EditorFieldState(
                            label = "Y",
                            value = positionY,
                            onValueChange = {
                                positionY = it
                                errorMessage = null
                            },
                            keyboardType = KeyboardType.Decimal,
                        ),
                    )
                    StageEditorFieldRow(
                        first = EditorFieldState(
                            label = "Z",
                            value = positionZ,
                            onValueChange = {
                                positionZ = it
                                errorMessage = null
                            },
                            keyboardType = KeyboardType.Decimal,
                        ),
                    )

                    StageEditorSectionLabel("Velocity (m/s)")
                    StageEditorFieldRow(
                        first = EditorFieldState(
                            label = "Vx",
                            value = velocityX,
                            onValueChange = {
                                velocityX = it
                                errorMessage = null
                            },
                            keyboardType = KeyboardType.Decimal,
                        ),
                        second = EditorFieldState(
                            label = "Vy",
                            value = velocityY,
                            onValueChange = {
                                velocityY = it
                                errorMessage = null
                            },
                            keyboardType = KeyboardType.Decimal,
                        ),
                    )
                    StageEditorFieldRow(
                        first = EditorFieldState(
                            label = "Vz",
                            value = velocityZ,
                            onValueChange = {
                                velocityZ = it
                                errorMessage = null
                            },
                            keyboardType = KeyboardType.Decimal,
                        ),
                    )

                    StageEditorSectionLabel("Appearance")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(28.dp),
                            shape = CircleShape,
                            color = Color(colorPreview),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {}
                        OutlinedTextField(
                            value = colorHex,
                            onValueChange = {
                                colorHex = it.uppercase()
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Color hex") },
                            supportingText = { Text("Use 6 or 8 hex digits, with or without #.") },
                            singleLine = true,
                        )
                    }

                    if (editorState.isNewBody) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(
                                checked = placeOnSceneAfterSave,
                                onCheckedChange = {
                                    placeOnSceneAfterSave = it
                                    errorMessage = null
                                },
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Place on scene after save",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Save first, then tap or drag on the stage to place the new object.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text("Delete")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            fun fail(message: String) {
                                errorMessage = message
                            }

                            val trimmedName = name.trim()
                            if (trimmedName.isBlank()) {
                                fail("Name is required.")
                                return@Button
                            }

                            val parsedMassKg = parseValidatedDouble(
                                rawValue = massKg,
                                fieldLabel = "Mass",
                                allowNegative = false,
                            ) ?: run {
                                fail("Mass must be zero or positive.")
                                return@Button
                            }
                            val parsedRadiusM = parseValidatedDouble(
                                rawValue = radiusM,
                                fieldLabel = "Radius",
                                allowNegative = false,
                            ) ?: run {
                                fail("Radius must be zero or positive.")
                                return@Button
                            }
                            val parsedPositionX = parseValidatedDouble(positionX, "Position X") ?: run {
                                fail("Position X must be a valid number.")
                                return@Button
                            }
                            val parsedPositionY = parseValidatedDouble(positionY, "Position Y") ?: run {
                                fail("Position Y must be a valid number.")
                                return@Button
                            }
                            val parsedPositionZ = parseValidatedDouble(positionZ, "Position Z") ?: run {
                                fail("Position Z must be a valid number.")
                                return@Button
                            }
                            val parsedVelocityX = parseValidatedDouble(velocityX, "Velocity X") ?: run {
                                fail("Velocity X must be a valid number.")
                                return@Button
                            }
                            val parsedVelocityY = parseValidatedDouble(velocityY, "Velocity Y") ?: run {
                                fail("Velocity Y must be a valid number.")
                                return@Button
                            }
                            val parsedVelocityZ = parseValidatedDouble(velocityZ, "Velocity Z") ?: run {
                                fail("Velocity Z must be a valid number.")
                                return@Button
                            }
                            val parsedColorArgb = parseColorArgb(colorHex) ?: run {
                                fail("Color must be 6 or 8 hex digits.")
                                return@Button
                            }

                            errorMessage = null
                            onSave(
                                EditableBodyDraft(
                                    existingBodyId = editorState.draft.existingBodyId,
                                    existingHostBodyId = editorState.draft.existingHostBodyId,
                                    name = trimmedName,
                                    category = category,
                                    gravitationalRole = gravitationalRole,
                                    massKg = parsedMassKg,
                                    radiusM = parsedRadiusM,
                                    positionM = Vector3d(parsedPositionX, parsedPositionY, parsedPositionZ),
                                    velocityMps = Vector3d(parsedVelocityX, parsedVelocityY, parsedVelocityZ),
                                    colorArgb = parsedColorArgb,
                                    placeOnSceneAfterSave = editorState.isNewBody && placeOnSceneAfterSave,
                                ),
                            )
                        },
                    ) {
                        Text(if (editorState.isNewBody) "Save" else "Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun StageEditorSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun StageEditorFieldRow(
    first: EditorFieldState,
    second: EditorFieldState? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            value = first.value,
            onValueChange = first.onValueChange,
            modifier = Modifier.weight(1f),
            label = { Text(first.label) },
            keyboardOptions = KeyboardOptions(keyboardType = first.keyboardType),
            singleLine = true,
        )
        if (second != null) {
            OutlinedTextField(
                value = second.value,
                onValueChange = second.onValueChange,
                modifier = Modifier.weight(1f),
                label = { Text(second.label) },
                keyboardOptions = KeyboardOptions(keyboardType = second.keyboardType),
                singleLine = true,
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

private fun buildTimelineText(timeline: TimelineStatus?): String {
    if (timeline == null) {
        return "Catalog timeline\nSpeed 6 h/s • Step 6 h"
    }
    val modeLabel = when (timeline.mode) {
        TimelineMode.CATALOG -> "Catalog timeline"
        TimelineMode.SANDBOX_BRANCH -> "Sandbox timeline"
    }
    return if (timeline.absoluteJulianDateTdb != null) {
        "%s | JD(TDB) %.5f\nSpeed %s • Step %s".format(
            modeLabel,
            timeline.absoluteJulianDateTdb,
            timeline.playbackSpeed.label,
            timeline.stepQuantum.label,
        )
    } else {
        "%s\nSpeed %s • Step %s".format(
            modeLabel,
            timeline.playbackSpeed.label,
            timeline.stepQuantum.label,
        )
    }
}

private fun buildSelectionCard(
    frame: LabFrame?,
    selectedBodyId: String?,
    pendingAddDraft: EditableBodyDraft?,
): SelectionCardText {
    if (pendingAddDraft != null) {
        val placementLine = if (pendingAddDraft.placeOnSceneAfterSave) {
            "Tap the stage to place it, or drag to seed initial velocity."
        } else {
            "Save will add the body immediately using the values below."
        }
        return SelectionCardText(
            eyebrow = "OBJECT PLACEMENT",
            title = "Placement armed · ${pendingAddDraft.name}",
            detail = listOf(
                "${pendingAddDraft.prettyCategoryLabel()} · ${pendingAddDraft.prettyRoleLabel()}",
                placementLine,
            ).joinToString(separator = "\n"),
        )
    }
    if (selectedBodyId == null) {
        return buildIdleMissionTrajectoryCard(frame)
    }
    val body = frame?.snapshot?.bodies?.firstOrNull { it.id == selectedBodyId }
        ?: return buildIdleMissionTrajectoryCard(frame)
    val hostName = body.hostBodyId?.let { hostBodyId ->
        frame.snapshot.bodies.firstOrNull { it.id == hostBodyId }?.name ?: hostBodyId
    }
    val roleLine = hostName?.let { resolvedHostName ->
        "${body.prettyRoleLabel()} · host $resolvedHostName"
    } ?: body.prettyRoleLabel()
    val motionLine = "Speed ${formatSpeed(body.velocityMps.magnitude())} • ${formatDistance(body.positionM.magnitude())} from center"
    return SelectionCardText(
        eyebrow = "TRACKING TARGET",
        title = "${body.name} · ${body.prettyCategoryLabel()}",
        detail = listOf(roleLine, motionLine).joinToString(separator = "\n"),
    )
}

private fun buildIdleMissionTrajectoryCard(frame: LabFrame?): SelectionCardText = SelectionCardText(
    eyebrow = "MISSION TRAJECTORY",
    title = "Flight path workbench",
    detail = buildIdleMissionTrajectoryDetail(frame?.snapshot?.bodies?.size),
)

internal fun buildIdleMissionTrajectoryDetail(bodyCount: Int?): String {
    val sceneLine = when {
        bodyCount == null -> "Acquiring ephemeris scene and live fly paths."
        bodyCount <= 0 -> "Waiting for the first trajectory body to enter the scene."
        bodyCount == 1 -> "Tracking 1 body with live fly paths."
        else -> "Tracking $bodyCount bodies with live fly paths."
    }
    return "$sceneLine Tap a luminous body to focus, or open Immersive for the accelerated runtime view."
}

private fun buildDiagnosticsText(frame: LabFrame?): String {
    frame ?: return "Renderer warming up."
    val collisionText = if (frame.collisions.isEmpty()) {
        ""
    } else {
        "\n" + frame.collisions.joinToString(separator = "\n") { collision ->
            when (collision.collisionMode) {
                CollisionMode.MERGE -> "Collision: ${collision.primaryBodyId} + ${collision.secondaryBodyId} → ${collision.resultLabel}"
                CollisionMode.ELASTIC -> "Collision: ${collision.primaryBodyId} ↔ ${collision.secondaryBodyId} (elastic)"
                CollisionMode.FRAGMENTATION -> "Collision: ${collision.primaryBodyId} ↔ ${collision.secondaryBodyId} (fragmentation)"
                CollisionMode.NONE -> "Collision: ${collision.primaryBodyId} / ${collision.secondaryBodyId}"
            }
        }
    }
    val prefix = if (!frame.diagnosticsFresh) {
        "Diagnostics shown from recent running tick\n"
    } else {
        ""
    }
    return prefix + frame.diagnostics.toPrettyString() + collisionText
}

private fun formatSpeed(speedMps: Double): String = when {
    speedMps >= 1_000.0 -> "%.1f km/s".format(speedMps / 1_000.0)
    else -> "%.0f m/s".format(speedMps)
}

internal fun formatDistance(distanceM: Double): String = when {
    distanceM >= 0.01 * PhysicalConstants.ASTRONOMICAL_UNIT_M -> "%.2f AU".format(distanceM / PhysicalConstants.ASTRONOMICAL_UNIT_M)
    distanceM >= 1_000_000.0 -> "%.0f km".format(distanceM / 1_000.0)
    else -> "%.0f m".format(distanceM)
}

private fun parseColorArgb(raw: String): Int? {
    val stripped = raw.trim().removePrefix("#")
    val normalized = when (stripped.length) {
        6 -> "#FF$stripped"
        8 -> "#$stripped"
        else -> return null
    }
    return runCatching { AndroidColor.parseColor(normalized) }.getOrNull()
}

private fun parseValidatedDouble(
    rawValue: String?,
    fieldLabel: String,
    allowNegative: Boolean = true,
): Double? {
    val parsed = rawValue?.trim().orEmpty().toDoubleOrNull() ?: return null
    if (!allowNegative && parsed < 0.0) {
        return null
    }
    return parsed
}

private fun BodyState.prettyCategoryLabel(): String = category.prettyCategoryLabel()

private fun EditableBodyDraft.prettyCategoryLabel(): String = category.prettyCategoryLabel()

private fun BodyCategory.prettyCategoryLabel(): String = when (this) {
    BodyCategory.STAR -> "Star"
    BodyCategory.PLANET -> "Planet"
    BodyCategory.MOON -> "Moon"
    BodyCategory.DWARF_PLANET -> "Dwarf planet"
    BodyCategory.ASTEROID -> "Asteroid"
    BodyCategory.COMET -> "Comet"
    BodyCategory.TEST_OBJECT -> "Test object"
    BodyCategory.PROBE -> "Probe"
}

private fun resolveSandboxSemanticBodyId(frame: LabFrame?, bodyQuery: String): String? {
    val normalizedQuery = bodyQuery.trim().lowercase(java.util.Locale.US)
    if (normalizedQuery.isEmpty()) {
        return null
    }
    return frame?.snapshot?.bodies?.firstOrNull { body ->
        body.id.lowercase(java.util.Locale.US) == normalizedQuery || body.name.lowercase(java.util.Locale.US) == normalizedQuery
    }?.id
}

private fun BodyState.prettyRoleLabel(): String = gravitationalRole.prettyRoleLabel()

private fun EditableBodyDraft.prettyRoleLabel(): String = gravitationalRole.prettyRoleLabel()

private fun GravitationalRole.prettyRoleLabel(includeHints: Boolean = false): String = when (this) {
    GravitationalRole.MASSIVE -> if (includeHints) {
        "Massive (mutual gravity)"
    } else {
        "Massive body"
    }

    GravitationalRole.TRACER -> if (includeHints) {
        "Tracer (passive)"
    } else {
        "Tracer"
    }
}

private fun ObserverMode.displayLabel(): String = when (this) {
    ObserverMode.FREE -> "Observer: Free"
    ObserverMode.FOLLOW_SELECTED -> "Observer: Follow selected"
    ObserverMode.FOLLOW_SELECTED_HOST -> "Observer: Follow selected host"
}

private fun RenderProcessingMode.displayLabel(): String = when (this) {
    RenderProcessingMode.DEFAULT -> "Rendering: Standard"
    RenderProcessingMode.LOW -> "Rendering: Simplified"
}

private fun CollisionMode.displayLabel(): String = when (this) {
    CollisionMode.NONE,
    CollisionMode.MERGE,
    -> "Collisions: Merge"

    CollisionMode.ELASTIC -> "Collisions: Elastic"
    CollisionMode.FRAGMENTATION -> "Collisions: Fragmentation"
}

private data class SelectionCardText(
    val eyebrow: String,
    val title: String,
    val detail: String,
)

private data class BodyEditorDialogState(
    val draft: EditableBodyDraft,
    val isNewBody: Boolean,
)

private data class EditorFieldState(
    val label: String,
    val value: String,
    val onValueChange: (String) -> Unit,
    val keyboardType: KeyboardType,
)
