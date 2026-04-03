package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants

enum class ObserverMode {
    FREE,
    FOLLOW_SELECTED,
    FOLLOW_SELECTED_HOST,
}

data class ObserverCameraTarget(
    val centerM: Vector3d,
    val suggestedViewRadiusM: Double,
)

object ObserverCameraResolver {

    fun resolveCameraTarget(
        frame: RenderSceneFrame,
        selectedBodyId: String?,
        observerMode: ObserverMode,
    ): ObserverCameraTarget? {
        val resolvedTarget = resolveTargetBodies(
            frame = frame,
            selectedBodyId = selectedBodyId,
            observerMode = observerMode,
        ) ?: return null
        return ObserverCameraTarget(
            centerM = resolvedTarget.focus.positionM,
            suggestedViewRadiusM = resolveSuggestedViewRadiusM(
                focus = resolvedTarget.focus,
                companion = resolvedTarget.companion,
            ),
        )
    }

    fun resolveCameraCenterM(
        frame: RenderSceneFrame,
        selectedBodyId: String?,
        observerMode: ObserverMode,
    ): Vector3d? = resolveCameraTarget(frame, selectedBodyId, observerMode)?.centerM

    fun resolveSuggestedViewRadiusM(
        frame: RenderSceneFrame,
        selectedBodyId: String?,
        observerMode: ObserverMode,
    ): Double? = resolveCameraTarget(frame, selectedBodyId, observerMode)?.suggestedViewRadiusM

    fun isCameraLocked(
        frame: RenderSceneFrame,
        selectedBodyId: String?,
        observerMode: ObserverMode,
    ): Boolean = resolveCameraCenterM(
        frame = frame,
        selectedBodyId = selectedBodyId,
        observerMode = observerMode,
    ) != null

    fun resolveTargetBodyId(
        frame: RenderSceneFrame,
        selectedBodyId: String?,
        observerMode: ObserverMode,
    ): String? {
        return resolveTargetBodies(frame, selectedBodyId, observerMode)?.focus?.id
    }

    private fun resolveTargetBodies(
        frame: RenderSceneFrame,
        selectedBodyId: String?,
        observerMode: ObserverMode,
    ): ResolvedTarget? {
        val selectedBody = selectedBodyId?.let { frame.bodyById(it) }
        return when (observerMode) {
            ObserverMode.FREE -> null
            ObserverMode.FOLLOW_SELECTED -> selectedBody?.let { body ->
                ResolvedTarget(
                    focus = body,
                    companion = body.hostBodyId?.let { hostBodyId ->
                        frame.bodyById(hostBodyId)
                    },
                )
            }
            ObserverMode.FOLLOW_SELECTED_HOST -> selectedBody?.hostBodyId?.let { hostBodyId ->
                frame.bodyById(hostBodyId)?.let { host ->
                    ResolvedTarget(
                        focus = host,
                        companion = selectedBody,
                    )
                }
            }
        }
    }

    private fun resolveSuggestedViewRadiusM(
        focus: RenderBody,
        companion: RenderBody?,
    ): Double {
        val baselineRadius = when (focus.kind) {
            RenderBodyKind.STAR -> 4.5 * PhysicalConstants.ASTRONOMICAL_UNIT_M
            RenderBodyKind.PLANET -> 0.14 * PhysicalConstants.ASTRONOMICAL_UNIT_M
            RenderBodyKind.DWARF_PLANET -> 0.08 * PhysicalConstants.ASTRONOMICAL_UNIT_M
            RenderBodyKind.ASTEROID,
            RenderBodyKind.COMET,
            -> 0.035 * PhysicalConstants.ASTRONOMICAL_UNIT_M
            RenderBodyKind.PROBE,
            RenderBodyKind.TEST_OBJECT,
            -> 0.020 * PhysicalConstants.ASTRONOMICAL_UNIT_M
        }
        val relationalRadius = companion?.let { body ->
            val distanceM = focus.positionM.distanceTo(body.positionM)
            if (distanceM <= 0.0) {
                0.0
            } else if (focus.kind == RenderBodyKind.STAR || body.kind == RenderBodyKind.STAR) {
                (distanceM * 2.2).coerceAtMost(3.5 * PhysicalConstants.ASTRONOMICAL_UNIT_M)
            } else {
                distanceM * 2.2
            }
        } ?: 0.0
        return maxOf(MIN_LOCKED_VIEW_RADIUS_M, baselineRadius, relationalRadius)
            .coerceAtMost(MAX_LOCKED_VIEW_RADIUS_M)
    }

    private fun RenderSceneFrame.bodyById(bodyId: String): RenderBody? {
        return authoritativeBodies.firstOrNull { it.id == bodyId }
            ?: tracerBodies.firstOrNull { it.id == bodyId }
    }

    private data class ResolvedTarget(
        val focus: RenderBody,
        val companion: RenderBody?,
    )

    private val MIN_LOCKED_VIEW_RADIUS_M: Double = 0.003 * PhysicalConstants.ASTRONOMICAL_UNIT_M
    private val MAX_LOCKED_VIEW_RADIUS_M: Double = 12.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M
}
