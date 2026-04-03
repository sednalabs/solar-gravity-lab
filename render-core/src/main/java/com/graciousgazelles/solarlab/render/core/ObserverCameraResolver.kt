package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d

enum class ObserverMode {
    FREE,
    FOLLOW_SELECTED,
    FOLLOW_SELECTED_HOST,
}

object ObserverCameraResolver {

    fun resolveCameraCenterM(
        frame: RenderSceneFrame,
        selectedBodyId: String?,
        observerMode: ObserverMode,
    ): Vector3d? {
        val targetBodyId = resolveTargetBodyId(
            frame = frame,
            selectedBodyId = selectedBodyId,
            observerMode = observerMode,
        ) ?: return null
        return frame.bodyById(targetBodyId)?.positionM
    }

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
        val selectedBody = selectedBodyId?.let { frame.bodyById(it) }
        return when (observerMode) {
            ObserverMode.FREE -> null
            ObserverMode.FOLLOW_SELECTED -> selectedBody?.id
            ObserverMode.FOLLOW_SELECTED_HOST -> selectedBody?.hostBodyId?.let { hostBodyId ->
                frame.bodyById(hostBodyId)?.id
            }
        }
    }

    private fun RenderSceneFrame.bodyById(bodyId: String): RenderBody? {
        return authoritativeBodies.firstOrNull { it.id == bodyId }
            ?: tracerBodies.firstOrNull { it.id == bodyId }
    }
}
