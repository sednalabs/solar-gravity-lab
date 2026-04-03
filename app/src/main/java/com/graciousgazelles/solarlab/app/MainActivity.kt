package com.graciousgazelles.solarlab.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.graciousgazelles.solarlab.app.databinding.ActivityMainBinding
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.TimelineMode
import com.graciousgazelles.solarlab.feature.lab.LabFrame
import com.graciousgazelles.solarlab.feature.lab.LabFrameListener
import com.graciousgazelles.solarlab.feature.lab.LabSession
import com.graciousgazelles.solarlab.feature.lab.TimelineStatus
import com.graciousgazelles.solarlab.feature.lab.render.RenderInteractionListener
import com.graciousgazelles.solarlab.feature.lab.render.SceneInteractionMode
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderBackend
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

        binding.buttonBackend.setOnClickListener {
            binding.renderHost.cycleBackendPreference()
            updateBackendButtonText(binding.renderHost.backendPreference())
        }

        updateBackendButtonText(binding.renderHost.backendPreference())
        updateCollisionButtonText()
        updateTimelineControls(null)
        updateAddButtonText()
        updateSelectedBodySummary()
        updateObserverButtonText()

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
        updateBackendButtonText(status.requested)
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
        val selectionText = when {
            pendingAddDraft != null -> getString(R.string.selection_pending_add)
            selectedBodyId == null -> getString(R.string.selection_none)
            else -> {
                val body = latestFrame?.snapshot?.bodies?.firstOrNull { it.id == selectedBodyId }
                if (body == null) {
                    getString(R.string.selection_none)
                } else {
                    buildString {
                        appendLine("Selected: ${body.name} (${body.category.name.lowercase().replace('_', ' ')})")
                        appendLine("Mass: ${body.massKg.toEditorString()} kg | Radius: ${body.radiusM.toEditorString()} m")
                        body.hostBodyId?.let { appendLine("Host: $it") }
                        appendLine("Pos: [${body.positionM.x.toEditorString()}, ${body.positionM.y.toEditorString()}, ${body.positionM.z.toEditorString()}] m")
                        append("Vel: [${body.velocityMps.x.toEditorString()}, ${body.velocityMps.y.toEditorString()}, ${body.velocityMps.z.toEditorString()}] m/s")
                    }
                }
            }
        }
        binding.textSelection.text = selectionText
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

    private fun buildTimelineText(timeline: TimelineStatus): String = buildString {
        append("Timeline: ")
        val modeLabel = when (timeline.mode) {
            TimelineMode.CATALOG -> "catalog"
            TimelineMode.SANDBOX_BRANCH -> "sandbox"
        }
        append(modeLabel)
        timeline.absoluteJulianDateTdb?.let {
            append(" | JD(TDB) ")
            append("%.5f".format(it))
        }
        append(" | Speed ")
        append(timeline.playbackSpeed.label)
        append(" | Step ")
        append(timeline.stepQuantum.label)
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
        binding.buttonFollow.isEnabled = pendingAddDraft == null
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

    private fun updateBackendButtonText(requested: RenderBackend) {
        binding.buttonBackend.text = when (requested) {
            RenderBackend.AUTO -> getString(R.string.action_backend_auto)
            RenderBackend.VULKAN -> getString(R.string.action_backend_vulkan)
            RenderBackend.OPENGL -> getString(R.string.action_backend_opengl)
        }
    }

    private fun updateTimelineControls(timeline: TimelineStatus?) {
        binding.buttonStepQuantum.text = "Step: ${timeline?.stepQuantum?.label ?: session.stepQuantumPreset().label}"
        binding.buttonSpeedDown.text = "Speed -"
        binding.buttonSpeedUp.text = "Speed +"
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

    private companion object {
        private const val PLACEMENT_DRAG_THRESHOLD_PX: Float = 24f
        private const val PLACEMENT_DRAG_LOOKAHEAD_SECONDS: Double = 30.0 * PhysicalConstants.DAY_SECONDS
    }
}
