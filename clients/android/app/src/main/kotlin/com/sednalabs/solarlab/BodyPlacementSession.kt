package com.sednalabs.solarlab

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.feature.lab.render.PlacementGesturePhase
import com.graciousgazelles.solarlab.feature.lab.render.PlacementGestureUpdate
import com.graciousgazelles.solarlab.render.core.RenderBodyKind
import com.graciousgazelles.solarlab.render.core.RenderPlacementPreview
import java.util.UUID
import kotlin.math.sqrt

internal const val PLACEMENT_DRAG_THRESHOLD_PX: Float = 24f
internal const val PLACEMENT_DRAG_LOOKAHEAD_SECONDS: Double = 30.0 * PhysicalConstants.DAY_SECONDS

internal enum class BodyPlacementPhase {
    AwaitingStagePoint,
    Dragging,
    Staged,
}

internal data class BodyPlacementSession(
    val bodyId: String,
    val draft: EditableBodyDraft,
    val stagedPositionM: Vector3d,
    val stagedVelocityMps: Vector3d,
    val hasStagePlacement: Boolean,
    val phase: BodyPlacementPhase,
) {
    val canCommit: Boolean
        get() = hasStagePlacement && phase == BodyPlacementPhase.Staged

    fun withDraftValues(nextDraft: EditableBodyDraft): BodyPlacementSession {
        val updatedDraft = nextDraft.copy(placeOnSceneAfterSave = true)
        return copy(
            draft = updatedDraft,
            stagedPositionM = updatedDraft.positionM,
            stagedVelocityMps = updatedDraft.velocityMps,
            hasStagePlacement = hasStagePlacement,
            phase = if (hasStagePlacement) {
                BodyPlacementPhase.Staged
            } else {
                BodyPlacementPhase.AwaitingStagePoint
            },
        )
    }

    fun draftForAdjustment(): EditableBodyDraft = draft.copy(
        positionM = stagedPositionM,
        velocityMps = stagedVelocityMps,
        placeOnSceneAfterSave = true,
    )

    fun reposition(): BodyPlacementSession = copy(
        hasStagePlacement = false,
        phase = BodyPlacementPhase.AwaitingStagePoint,
    )

    fun applyGesture(update: PlacementGestureUpdate): BodyPlacementSession = when (update.phase) {
        PlacementGesturePhase.Cancelled -> copy(
            hasStagePlacement = false,
            phase = BodyPlacementPhase.AwaitingStagePoint,
        )

        PlacementGesturePhase.Started,
        PlacementGesturePhase.Changed,
        PlacementGesturePhase.Ended -> {
            val placedPosition = Vector3d(
                x = update.startWorldPositionM.x,
                y = update.startWorldPositionM.y,
                z = draft.positionM.z,
            )
            val velocityFromDrag = if (update.gestureDistancePx >= PLACEMENT_DRAG_THRESHOLD_PX) {
                val delta = update.endWorldPositionM - update.startWorldPositionM
                Vector3d(
                    x = draft.velocityMps.x + (delta.x / PLACEMENT_DRAG_LOOKAHEAD_SECONDS),
                    y = draft.velocityMps.y + (delta.y / PLACEMENT_DRAG_LOOKAHEAD_SECONDS),
                    z = draft.velocityMps.z,
                )
            } else {
                draft.velocityMps
            }
            copy(
                stagedPositionM = placedPosition,
                stagedVelocityMps = velocityFromDrag,
                hasStagePlacement = true,
                phase = if (update.phase == PlacementGesturePhase.Ended) {
                    BodyPlacementPhase.Staged
                } else {
                    BodyPlacementPhase.Dragging
                },
            )
        }
    }

    fun toBodyState(): BodyState = draft.toBodyState(
        bodyIdOverride = bodyId,
        positionOverrideM = stagedPositionM,
        velocityOverrideMps = stagedVelocityMps,
    )

    fun toPlacementPreview(massiveBodies: List<BodyState>): RenderPlacementPreview? {
        if (!hasStagePlacement) return null
        return RenderPlacementPreview(
            bodyId = bodyId,
            name = draft.name,
            positionM = stagedPositionM,
            velocityMps = stagedVelocityMps,
            radiusM = draft.radiusM.coerceAtLeast(0.0),
            colorArgb = draft.colorArgb.withAlpha(0xD9),
            kind = draft.category.toRenderBodyKind(),
            isMassive = draft.gravitationalRole == GravitationalRole.MASSIVE,
            sourceMassKg = draft.massKg.coerceAtLeast(0.0),
            forecastPointsM = DraftTrajectoryProjector.project(
                startPositionM = stagedPositionM,
                startVelocityMps = stagedVelocityMps,
                massiveBodies = massiveBodies,
            ),
        )
    }

    fun toSaveableValues(): List<Any?> = listOf(
        bodyId,
        draft.existingBodyId,
        draft.existingHostBodyId,
        draft.name,
        draft.category.name,
        draft.gravitationalRole.name,
        draft.massKg,
        draft.radiusM,
        draft.positionM.x,
        draft.positionM.y,
        draft.positionM.z,
        draft.velocityMps.x,
        draft.velocityMps.y,
        draft.velocityMps.z,
        draft.colorArgb,
        stagedPositionM.x,
        stagedPositionM.y,
        stagedPositionM.z,
        stagedVelocityMps.x,
        stagedVelocityMps.y,
        stagedVelocityMps.z,
        hasStagePlacement,
        phase.name,
    )

    companion object {
        fun fromDraft(
            draft: EditableBodyDraft,
            bodyId: String = draft.existingBodyId ?: "custom:${UUID.randomUUID()}",
        ): BodyPlacementSession = BodyPlacementSession(
            bodyId = bodyId,
            draft = draft.copy(placeOnSceneAfterSave = true),
            stagedPositionM = draft.positionM,
            stagedVelocityMps = draft.velocityMps,
            hasStagePlacement = false,
            phase = BodyPlacementPhase.AwaitingStagePoint,
        )

        fun restore(values: List<*>): BodyPlacementSession? = runCatching {
            val draft = EditableBodyDraft(
                existingBodyId = values[1] as String?,
                existingHostBodyId = values[2] as String?,
                name = values[3] as String,
                category = BodyCategory.valueOf(values[4] as String),
                gravitationalRole = GravitationalRole.valueOf(values[5] as String),
                massKg = values[6] as Double,
                radiusM = values[7] as Double,
                positionM = Vector3d(values[8] as Double, values[9] as Double, values[10] as Double),
                velocityMps = Vector3d(values[11] as Double, values[12] as Double, values[13] as Double),
                colorArgb = values[14] as Int,
                placeOnSceneAfterSave = true,
            )
            BodyPlacementSession(
                bodyId = values[0] as String,
                draft = draft,
                stagedPositionM = Vector3d(values[15] as Double, values[16] as Double, values[17] as Double),
                stagedVelocityMps = Vector3d(values[18] as Double, values[19] as Double, values[20] as Double),
                hasStagePlacement = values[21] as Boolean,
                phase = BodyPlacementPhase.valueOf(values[22] as String),
            )
        }.getOrNull()
    }
}

internal object DraftTrajectoryProjector {
    private const val DEFAULT_FORECAST_SAMPLES = 32
    private const val DEFAULT_FORECAST_HORIZON_SECONDS = 30.0 * PhysicalConstants.DAY_SECONDS
    private const val MIN_DISTANCE_SQUARED_M2 = 1.0

    fun project(
        startPositionM: Vector3d,
        startVelocityMps: Vector3d,
        massiveBodies: List<BodyState>,
        horizonSeconds: Double = DEFAULT_FORECAST_HORIZON_SECONDS,
        sampleCount: Int = DEFAULT_FORECAST_SAMPLES,
    ): List<Vector3d> {
        if (sampleCount <= 1 || horizonSeconds <= 0.0) {
            return listOf(startPositionM)
        }
        val attractors = massiveBodies.filter { body ->
            body.sourceMassKg > 0.0 &&
                body.positionM.isFinite() &&
                body.sourceMassKg.isFinite()
        }
        val stepSeconds = horizonSeconds / (sampleCount - 1).toDouble()
        val points = ArrayList<Vector3d>(sampleCount)
        var position = startPositionM
        var velocity = startVelocityMps
        points += position

        repeat(sampleCount - 1) {
            val acceleration = attractors.fold(Vector3d.ZERO) { total, body ->
                total + accelerationFrom(body, position)
            }
            velocity += acceleration * stepSeconds
            position += velocity * stepSeconds
            if (position.isFinite()) {
                points += position
            }
        }
        return points
    }

    private fun accelerationFrom(body: BodyState, positionM: Vector3d): Vector3d {
        val delta = body.positionM - positionM
        val distanceSquared = delta.magnitudeSquared().coerceAtLeast(MIN_DISTANCE_SQUARED_M2)
        if (!distanceSquared.isFinite()) return Vector3d.ZERO
        val distance = sqrt(distanceSquared)
        val scale = PhysicalConstants.GRAVITATIONAL_CONSTANT_M3_PER_KG_S2 * body.sourceMassKg /
            (distanceSquared * distance)
        return if (scale.isFinite()) delta * scale else Vector3d.ZERO
    }
}

private fun BodyCategory.toRenderBodyKind(): RenderBodyKind = when (this) {
    BodyCategory.STAR -> RenderBodyKind.STAR
    BodyCategory.PLANET -> RenderBodyKind.PLANET
    BodyCategory.MOON -> RenderBodyKind.PLANET
    BodyCategory.DWARF_PLANET -> RenderBodyKind.DWARF_PLANET
    BodyCategory.ASTEROID -> RenderBodyKind.ASTEROID
    BodyCategory.COMET -> RenderBodyKind.COMET
    BodyCategory.PROBE -> RenderBodyKind.PROBE
    BodyCategory.TEST_OBJECT -> RenderBodyKind.TEST_OBJECT
}

private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FF_FFFF) or (alpha.coerceIn(0, 255) shl 24)

private fun Vector3d.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
