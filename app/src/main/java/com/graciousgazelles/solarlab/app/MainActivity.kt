package com.graciousgazelles.solarlab.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.graciousgazelles.solarlab.app.databinding.ActivityMainBinding
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.TimelineMode
import com.graciousgazelles.solarlab.feature.lab.LabFrame
import com.graciousgazelles.solarlab.feature.lab.LabFrameListener
import com.graciousgazelles.solarlab.feature.lab.LabSession
import com.graciousgazelles.solarlab.feature.lab.TimelineStatus
import com.graciousgazelles.solarlab.feature.lab.render.RenderInteractionListener
import com.graciousgazelles.solarlab.feature.lab.render.SceneInteractionMode
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus

class MainActivity : AppCompatActivity(), LabFrameListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: LabSession

    private var latestFrame: LabFrame? = null
    private var selectedBodyId: String? = null
    private var observerMode: ObserverMode = ObserverMode.FREE
    private var pendingAddDraft: EditableBodyDraft? = null
    private var currentCollisionMode: CollisionMode = CollisionMode.MERGE
    private var resumeSimulationOnForeground: Boolean = false
    private var resumeSimulationAfterModalInteraction: Boolean = false
    private var infoPanelVisible: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = LabSession.createDefault(context = this, listener = this)
        currentCollisionMode = session.collisionMode()

        binding.renderHost.setOnBackendStatusChangedListener(::onBackendStatusChanged)
        binding.renderHost.setInteractionListener(object : RenderInteractionListener {
            override fun onBodySelectionChanged(bodyId: String?) {
                updateSelectedBodyId(bodyId)
            }

            override fun onPlacementGesture(startWorldPositionM: Vector3d, endWorldPositionM: Vector3d, gestureDistancePx: Float) {
                handlePlacementGesture(startWorldPositionM, endWorldPositionM, gestureDistancePx)
            }
        })

        binding.buttonStartPause.setOnClickListener {
            if (session.isRunning()) {
                session.pause()
            } else {
                session.start()
            }
            updateSimulationButtons()
        }

        binding.buttonStep.setOnClickListener {
            session.stepOnce()
        }

        binding.buttonTimeBack.setOnClickListener {
            session.jumpTimelineByStep(-1)
        }

        binding.buttonTimeForward.setOnClickListener {
            session.jumpTimelineByStep(1)
        }

        binding.buttonStepQuantum.setOnClickListener {
            session.cycleStepQuantum(+1)
        }

        binding.buttonSpeedDown.setOnClickListener {
            session.cyclePlaybackSpeed(-1)
        }

        binding.buttonSpeedUp.setOnClickListener {
            session.cyclePlaybackSpeed(+1)
        }

        binding.buttonFollow.setOnClickListener {
            cycleObserverMode()
        }

        binding.buttonReset.setOnClickListener {
            cancelPendingPlacement(shouldResume = false)
            setObserverMode(ObserverMode.FREE)
            updateSelectedBodyId(null)
            session.resetDefault()
            binding.renderHost.resetScene()
            binding.renderHost.resetCamera()
            updateSimulationButtons()
        }

        binding.buttonAddBody.setOnClickListener {
            if (pendingAddDraft != null) {
                cancelPendingPlacement(shouldResume = true)
            } else {
                showAddBodyDialog()
            }
        }

        binding.buttonEditBody.setOnClickListener {
            showEditSelectedBodyDialog()
        }

        binding.buttonCollisionMode.setOnClickListener {
            currentCollisionMode = when (currentCollisionMode) {
                CollisionMode.NONE -> CollisionMode.MERGE
                CollisionMode.MERGE -> CollisionMode.ELASTIC
                CollisionMode.ELASTIC -> CollisionMode.FRAGMENTATION
                CollisionMode.FRAGMENTATION -> CollisionMode.MERGE
            }
            session.setCollisionMode(currentCollisionMode)
            updateCollisionButtonText()
        }

        binding.buttonInfoToggle.setOnClickListener {
            infoPanelVisible = !infoPanelVisible
            updateInfoPanelVisibility()
        }

        updateCollisionButtonText()
        updateTimelineControls(null)
        updateAddButtonText()
        updateSelectedBodySummary()
        updateObserverButtonText()
        updateInfoPanelVisibility()

        session.dispatchCurrentFrame()
        session.start()
        updateSimulationButtons()
    }

    override fun onLabFrame(frame: LabFrame) {
        latestFrame = frame
        binding.renderHost.submitSnapshot(frame.snapshot)
        binding.textDiagnostics.text = buildDiagnosticsText(frame)
        binding.textTimeline.text = buildTimelineText(frame.timeline)
        updateTimelineControls(frame.timeline)

        if (selectedBodyId != null && frame.snapshot.bodies.none { it.id == selectedBodyId }) {
            updateSelectedBodyId(null)
        } else {
            updateSelectedBodySummary()
        }

        updateObserverButtonText()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.renderHost.onHostResume()
        }
        if (::session.isInitialized && resumeSimulationOnForeground) {
            session.start()
            updateSimulationButtons()
        }
    }

    override fun onPause() {
        if (::session.isInitialized) {
            resumeSimulationOnForeground = session.isRunning()
            session.pause()
            updateSimulationButtons()
        }
        if (::binding.isInitialized) {
            binding.renderHost.onHostPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::session.isInitialized) {
            session.release()
        }
        if (::binding.isInitialized) {
            binding.renderHost.release()
        }
        super.onDestroy()
    }

    private fun onBackendStatusChanged(status: RenderBackendStatus) {
        binding.textBackend.text = status.message
        if (!status.isHardwareAccelerated && !infoPanelVisible) {
            infoPanelVisible = true
            updateInfoPanelVisibility()
        }
    }

    private fun showAddBodyDialog() {
        prepareForModalInteraction()
        BodyEditorDialogs.show(
            activity = this,
            draft = EditableBodyDraft.newDefault(),
            isNewBody = true,
            onSave = { draft ->
                if (draft.placeOnSceneAfterSave) {
                    pendingAddDraft = draft
                    binding.renderHost.setInteractionMode(SceneInteractionMode.PLACE_BODY)
                    updateAddButtonText()
                    updateSelectedBodySummary()
                } else {
                    val body = draft.toBodyState()
                    session.addBody(body)
                    updateSelectedBodyId(body.id)
                }
            },
            onDismiss = {
                if (pendingAddDraft == null) {
                    maybeResumeAfterModalInteraction()
                }
            },
        )
    }

    private fun showEditSelectedBodyDialog() {
        val body = latestFrame?.snapshot?.bodies?.firstOrNull { it.id == selectedBodyId } ?: return
        prepareForModalInteraction()
        BodyEditorDialogs.show(
            activity = this,
            draft = EditableBodyDraft.fromBodyState(body),
            isNewBody = false,
            onSave = { draft ->
                val updatedBody = draft.toBodyState()
                session.updateBody(updatedBody)
                updateSelectedBodyId(updatedBody.id)
            },
            onDelete = {
                session.removeBody(body.id)
                updateSelectedBodyId(null)
            },
            onDismiss = {
                maybeResumeAfterModalInteraction()
            },
        )
    }

    private fun handlePlacementGesture(
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
        binding.renderHost.setInteractionMode(SceneInteractionMode.NAVIGATE_AND_SELECT)
        session.addBody(placedBody)
        updateSelectedBodyId(placedBody.id)
        updateAddButtonText()
        maybeResumeAfterModalInteraction()
    }

    private fun updateSelectedBodyId(bodyId: String?) {
        selectedBodyId = bodyId
        if (bodyId == null && observerMode != ObserverMode.FREE) {
            observerMode = ObserverMode.FREE
            binding.renderHost.setObserverMode(observerMode)
        }
        binding.renderHost.setSelectedBodyId(bodyId)
        binding.buttonEditBody.isEnabled = bodyId != null && pendingAddDraft == null
        updateSelectedBodySummary()
        updateObserverButtonText()
        updateSimulationButtons()
    }

    private fun setObserverMode(mode: ObserverMode) {
        observerMode = mode
        binding.renderHost.setObserverMode(mode)
        updateObserverButtonText()
        updateSimulationButtons()
    }

    private fun cycleObserverMode() {
        val nextMode = when (observerMode) {
            ObserverMode.FREE -> ObserverMode.FOLLOW_SELECTED
            ObserverMode.FOLLOW_SELECTED -> ObserverMode.FOLLOW_SELECTED_HOST
            ObserverMode.FOLLOW_SELECTED_HOST -> ObserverMode.FREE
        }
        setObserverMode(nextMode)
    }

    private fun updateSelectedBodySummary() {
        val selectionCard = when {
            pendingAddDraft != null -> SelectionCardText(
                title = getString(R.string.selection_pending_add_title),
                detail = getString(R.string.selection_pending_add),
            )
            selectedBodyId == null -> SelectionCardText(
                title = getString(R.string.selection_none_title),
                detail = getString(R.string.selection_none),
            )
            else -> {
                val body = latestFrame?.snapshot?.bodies?.firstOrNull { it.id == selectedBodyId }
                if (body == null) {
                    SelectionCardText(
                        title = getString(R.string.selection_none_title),
                        detail = getString(R.string.selection_none),
                    )
                } else {
                    val headline = getString(
                        R.string.selection_format,
                        body.name,
                        prettyCategoryLabel(body.category),
                    )
                    val hostName = body.hostBodyId?.let { hostBodyId ->
                        latestFrame?.snapshot?.bodies?.firstOrNull { it.id == hostBodyId }?.name ?: hostBodyId
                    }
                    val roleLine = hostName?.let { resolvedHostName ->
                        getString(
                            R.string.selection_host_format,
                            prettyRoleLabel(body.gravitationalRole),
                            resolvedHostName,
                        )
                    } ?: prettyRoleLabel(body.gravitationalRole)
                    val motionLine = getString(
                        R.string.selection_motion_format,
                        formatSpeed(body.velocityMps.magnitude()),
                        getString(
                            R.string.selection_distance_format,
                            formatDistance(body.positionM.magnitude()),
                        ),
                    )
                    SelectionCardText(
                        title = headline,
                        detail = listOf(roleLine, motionLine).joinToString(separator = "\n"),
                    )
                }
            }
        }
        binding.textSelectionTitle.text = selectionCard.title
        binding.textSelectionDetail.text = selectionCard.detail
    }

    private fun buildDiagnosticsText(frame: LabFrame): String {
        if (frame.collisions.isEmpty()) {
            return frame.diagnostics.toPrettyString()
        }
        val collisionText = frame.collisions.joinToString(separator = "\n") { collision ->
            when (collision.collisionMode) {
                CollisionMode.MERGE -> "Collision: ${collision.primaryBodyId} + ${collision.secondaryBodyId} → ${collision.resultLabel}"
                CollisionMode.ELASTIC -> "Collision: ${collision.primaryBodyId} ↔ ${collision.secondaryBodyId} (elastic)"
                CollisionMode.FRAGMENTATION -> "Collision: ${collision.primaryBodyId} ↔ ${collision.secondaryBodyId} (fragmentation)"
                CollisionMode.NONE -> "Collision: ${collision.primaryBodyId} / ${collision.secondaryBodyId}"
            }
        }
        return frame.diagnostics.toPrettyString() + "\n" + collisionText
    }

    private fun buildTimelineText(timeline: TimelineStatus): String {
        val modeLabel = when (timeline.mode) {
            TimelineMode.CATALOG -> getString(R.string.timeline_label_catalog)
            TimelineMode.SANDBOX_BRANCH -> getString(R.string.timeline_label_sandbox)
        }
        return if (timeline.absoluteJulianDateTdb != null) {
            getString(
                R.string.timeline_format_with_epoch,
                modeLabel,
                timeline.absoluteJulianDateTdb,
                timeline.playbackSpeed.label,
                timeline.stepQuantum.label,
            )
        } else {
            getString(
                R.string.timeline_format_without_epoch,
                modeLabel,
                timeline.playbackSpeed.label,
                timeline.stepQuantum.label,
            )
        }
    }

    private fun prepareForModalInteraction() {
        resumeSimulationAfterModalInteraction = session.isRunning()
        if (resumeSimulationAfterModalInteraction) {
            session.pause()
            updateSimulationButtons()
        }
    }

    private fun maybeResumeAfterModalInteraction() {
        if (pendingAddDraft != null) return
        if (resumeSimulationAfterModalInteraction && !session.isRunning()) {
            session.start()
            updateSimulationButtons()
        }
        resumeSimulationAfterModalInteraction = false
    }

    private fun cancelPendingPlacement(shouldResume: Boolean) {
        pendingAddDraft = null
        binding.renderHost.setInteractionMode(SceneInteractionMode.NAVIGATE_AND_SELECT)
        updateAddButtonText()
        updateSelectedBodySummary()
        if (shouldResume) {
            maybeResumeAfterModalInteraction()
        }
    }

    private fun updateSimulationButtons() {
        val timeline = latestFrame?.timeline
        binding.buttonStartPause.isEnabled = pendingAddDraft == null
        binding.buttonCollisionMode.isEnabled = pendingAddDraft == null
        binding.buttonStep.isEnabled = !session.isRunning() && pendingAddDraft == null
        binding.buttonTimeBack.isEnabled = !session.isRunning() && pendingAddDraft == null && (timeline?.canStepBackward == true)
        binding.buttonTimeForward.isEnabled = !session.isRunning() && pendingAddDraft == null
        binding.buttonStepQuantum.isEnabled = pendingAddDraft == null
        binding.buttonSpeedDown.isEnabled = pendingAddDraft == null
        binding.buttonSpeedUp.isEnabled = pendingAddDraft == null
        binding.buttonFollow.isEnabled = pendingAddDraft == null && (selectedBodyId != null || observerMode != ObserverMode.FREE)
        binding.buttonStartPause.text = if (session.isRunning()) {
            getString(R.string.action_pause)
        } else {
            getString(R.string.action_start)
        }
    }

    private fun updateAddButtonText() {
        binding.buttonAddBody.text = if (pendingAddDraft == null) {
            getString(R.string.action_add_object)
        } else {
            getString(R.string.action_cancel_add)
        }
        binding.buttonEditBody.isEnabled = selectedBodyId != null && pendingAddDraft == null
        updateSimulationButtons()
    }

    private fun updateCollisionButtonText() {
        binding.buttonCollisionMode.text = when (currentCollisionMode) {
            CollisionMode.NONE,
            CollisionMode.MERGE,
            -> getString(R.string.action_collision_merge)
            CollisionMode.ELASTIC -> getString(R.string.action_collision_elastic)
            CollisionMode.FRAGMENTATION -> getString(R.string.action_collision_fragmentation)
        }
    }

    private fun updateTimelineControls(timeline: TimelineStatus?) {
        binding.buttonStepQuantum.text = getString(
            R.string.action_step_quantum_format,
            timeline?.stepQuantum?.label ?: session.stepQuantumPreset().label,
        )
        binding.buttonSpeedDown.text = getString(R.string.action_speed_down)
        binding.buttonSpeedUp.text = getString(R.string.action_speed_up)
        updateObserverButtonText()
        updateSimulationButtons()
    }

    private fun updateObserverButtonText() {
        binding.buttonFollow.text = when (observerMode) {
            ObserverMode.FREE -> getString(R.string.action_observer_free)
            ObserverMode.FOLLOW_SELECTED -> getString(R.string.action_observer_selected)
            ObserverMode.FOLLOW_SELECTED_HOST -> getString(R.string.action_observer_selected_host)
        }
    }

    private fun updateInfoPanelVisibility() {
        binding.panelInfo.visibility = if (infoPanelVisible) View.VISIBLE else View.GONE
        binding.buttonInfoToggle.text = if (infoPanelVisible) {
            getString(R.string.action_info_hide)
        } else {
            getString(R.string.action_info_show)
        }
    }

    private fun prettyCategoryLabel(category: BodyCategory): String = when (category) {
        BodyCategory.STAR -> getString(R.string.category_star)
        BodyCategory.PLANET -> getString(R.string.category_planet)
        BodyCategory.MOON -> getString(R.string.category_moon)
        BodyCategory.DWARF_PLANET -> getString(R.string.category_dwarf_planet)
        BodyCategory.ASTEROID -> getString(R.string.category_asteroid)
        BodyCategory.COMET -> getString(R.string.category_comet)
        BodyCategory.TEST_OBJECT -> getString(R.string.category_test_object)
        BodyCategory.PROBE -> getString(R.string.category_probe)
    }

    private fun prettyRoleLabel(role: GravitationalRole): String = when (role) {
        GravitationalRole.MASSIVE -> getString(R.string.role_massive_body)
        GravitationalRole.TRACER -> getString(R.string.role_tracer)
    }

    private fun formatSpeed(speedMps: Double): String = when {
        speedMps >= 1_000.0 -> getString(R.string.format_speed_kmps, speedMps / 1_000.0)
        else -> getString(R.string.format_speed_mps, speedMps)
    }

    private fun formatDistance(distanceM: Double): String = when {
        distanceM >= 0.01 * PhysicalConstants.ASTRONOMICAL_UNIT_M -> {
            getString(R.string.format_distance_au, distanceM / PhysicalConstants.ASTRONOMICAL_UNIT_M)
        }
        distanceM >= 1_000_000.0 -> {
            getString(R.string.format_distance_km, distanceM / 1_000.0)
        }
        else -> {
            getString(R.string.format_distance_m, distanceM)
        }
    }

    private companion object {
        private const val PLACEMENT_DRAG_THRESHOLD_PX: Float = 24f
        private const val PLACEMENT_DRAG_LOOKAHEAD_SECONDS: Double = 30.0 * PhysicalConstants.DAY_SECONDS
    }

    private data class SelectionCardText(
        val title: String,
        val detail: String,
    )
}
