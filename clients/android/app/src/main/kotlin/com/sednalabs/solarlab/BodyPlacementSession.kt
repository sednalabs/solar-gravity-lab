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
import kotlin.math.PI
import kotlin.math.sqrt

internal const val PLACEMENT_DRAG_THRESHOLD_PX: Float = 24f
internal const val PLACEMENT_DRAG_LOOKAHEAD_SECONDS: Double = 30.0 * PhysicalConstants.DAY_SECONDS
internal const val PLACEMENT_FORECAST_SAMPLE_COUNT: Int = 96
internal const val MIN_PLACEMENT_FORECAST_HORIZON_SECONDS: Double = 30.0 * 60.0
internal const val MAX_PLACEMENT_FORECAST_HORIZON_SECONDS: Double = 7.0 * PhysicalConstants.DAY_SECONDS

private const val BODY_PLACEMENT_SESSION_SAVE_VERSION = 1
private const val FALLBACK_PLACEMENT_FORECAST_HORIZON_SECONDS = 3.0 * PhysicalConstants.DAY_SECONDS
private const val PLACEMENT_FORECAST_ORBIT_FRACTION = 0.125

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

    fun toSaveableMap(): Map<String, Any?> = linkedMapOf(
        "version" to BODY_PLACEMENT_SESSION_SAVE_VERSION,
        "bodyId" to bodyId,
        "existingBodyId" to draft.existingBodyId,
        "existingHostBodyId" to draft.existingHostBodyId,
        "name" to draft.name,
        "category" to draft.category.name,
        "gravitationalRole" to draft.gravitationalRole.name,
        "massKg" to draft.massKg,
        "radiusM" to draft.radiusM,
        "positionX" to draft.positionM.x,
        "positionY" to draft.positionM.y,
        "positionZ" to draft.positionM.z,
        "velocityX" to draft.velocityMps.x,
        "velocityY" to draft.velocityMps.y,
        "velocityZ" to draft.velocityMps.z,
        "colorArgb" to draft.colorArgb,
        "stagedPositionX" to stagedPositionM.x,
        "stagedPositionY" to stagedPositionM.y,
        "stagedPositionZ" to stagedPositionM.z,
        "stagedVelocityX" to stagedVelocityMps.x,
        "stagedVelocityY" to stagedVelocityMps.y,
        "stagedVelocityZ" to stagedVelocityMps.z,
        "hasStagePlacement" to hasStagePlacement,
        "phase" to phase.name,
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

        fun restore(values: Map<*, *>): BodyPlacementSession? = runCatching {
            require(values.requiredInt("version") == BODY_PLACEMENT_SESSION_SAVE_VERSION)
            val draft = EditableBodyDraft(
                existingBodyId = values.optionalString("existingBodyId"),
                existingHostBodyId = values.optionalString("existingHostBodyId"),
                name = values.requiredString("name"),
                category = BodyCategory.valueOf(values.requiredString("category")),
                gravitationalRole = GravitationalRole.valueOf(values.requiredString("gravitationalRole")),
                massKg = values.requiredDouble("massKg"),
                radiusM = values.requiredDouble("radiusM"),
                positionM = Vector3d(
                    values.requiredDouble("positionX"),
                    values.requiredDouble("positionY"),
                    values.requiredDouble("positionZ"),
                ),
                velocityMps = Vector3d(
                    values.requiredDouble("velocityX"),
                    values.requiredDouble("velocityY"),
                    values.requiredDouble("velocityZ"),
                ),
                colorArgb = values.requiredInt("colorArgb"),
                placeOnSceneAfterSave = true,
            )
            BodyPlacementSession(
                bodyId = values.requiredString("bodyId"),
                draft = draft,
                stagedPositionM = Vector3d(
                    values.requiredDouble("stagedPositionX"),
                    values.requiredDouble("stagedPositionY"),
                    values.requiredDouble("stagedPositionZ"),
                ),
                stagedVelocityMps = Vector3d(
                    values.requiredDouble("stagedVelocityX"),
                    values.requiredDouble("stagedVelocityY"),
                    values.requiredDouble("stagedVelocityZ"),
                ),
                hasStagePlacement = values.requiredBoolean("hasStagePlacement"),
                phase = BodyPlacementPhase.valueOf(values.requiredString("phase")),
            )
        }.getOrNull()
    }
}

internal object DraftTrajectoryProjector {
    private const val MIN_DISTANCE_SQUARED_M2 = 1.0

    fun project(
        startPositionM: Vector3d,
        startVelocityMps: Vector3d,
        massiveBodies: List<BodyState>,
        horizonSeconds: Double = Double.NaN,
        sampleCount: Int = PLACEMENT_FORECAST_SAMPLE_COUNT,
    ): List<Vector3d> {
        val attractors = massiveBodies.filter { body ->
            body.sourceMassKg > 0.0 &&
                body.positionM.isFinite() &&
                body.sourceMassKg.isFinite()
        }
        val resolvedHorizonSeconds = if (horizonSeconds.isFinite() && horizonSeconds > 0.0) {
            horizonSeconds
        } else {
            forecastHorizonSeconds(startPositionM, attractors)
        }
        if (sampleCount <= 1 || resolvedHorizonSeconds <= 0.0) {
            return listOf(startPositionM)
        }
        val stepSeconds = resolvedHorizonSeconds / (sampleCount - 1).toDouble()
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

    internal fun forecastHorizonSeconds(
        startPositionM: Vector3d,
        massiveBodies: List<BodyState>,
    ): Double {
        val shortestLocalPeriodSeconds = massiveBodies.asSequence()
            .filter { body ->
                body.sourceMassKg > 0.0 &&
                    body.positionM.isFinite() &&
                    body.sourceMassKg.isFinite()
            }
            .mapNotNull { body ->
                val delta = body.positionM - startPositionM
                val distanceSquared = delta.magnitudeSquared().coerceAtLeast(MIN_DISTANCE_SQUARED_M2)
                if (!distanceSquared.isFinite()) return@mapNotNull null
                val distance = sqrt(distanceSquared)
                val gravitationalParameter =
                    PhysicalConstants.GRAVITATIONAL_CONSTANT_M3_PER_KG_S2 * body.sourceMassKg
                if (!gravitationalParameter.isFinite() || gravitationalParameter <= 0.0) {
                    return@mapNotNull null
                }
                val periodSeconds = 2.0 * PI * sqrt(distanceSquared * distance / gravitationalParameter)
                periodSeconds.takeIf { it.isFinite() && it > 0.0 }
            }
            .minOrNull()

        val forecastSeconds = shortestLocalPeriodSeconds
            ?.times(PLACEMENT_FORECAST_ORBIT_FRACTION)
            ?: FALLBACK_PLACEMENT_FORECAST_HORIZON_SECONDS
        return forecastSeconds.coerceIn(
            MIN_PLACEMENT_FORECAST_HORIZON_SECONDS,
            MAX_PLACEMENT_FORECAST_HORIZON_SECONDS,
        )
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

private fun Map<*, *>.optionalString(key: String): String? = this[key] as? String

private fun Map<*, *>.requiredString(key: String): String =
    optionalString(key) ?: error("Missing string placement session field: $key")

private fun Map<*, *>.requiredDouble(key: String): Double =
    (this[key] as? Number)?.toDouble() ?: error("Missing numeric placement session field: $key")

private fun Map<*, *>.requiredInt(key: String): Int =
    (this[key] as? Number)?.toInt() ?: error("Missing integer placement session field: $key")

private fun Map<*, *>.requiredBoolean(key: String): Boolean =
    this[key] as? Boolean ?: error("Missing boolean placement session field: $key")
