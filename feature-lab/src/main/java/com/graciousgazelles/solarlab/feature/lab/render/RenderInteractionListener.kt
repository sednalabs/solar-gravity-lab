package com.graciousgazelles.solarlab.feature.lab.render

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.render.core.ObserverMode

interface RenderInteractionListener {
    fun onBodySelectionChanged(bodyId: String?)
    fun onCameraNavigationModeChanged(observerMode: ObserverMode) = Unit
    fun onPlacementGesture(startWorldPositionM: Vector3d, endWorldPositionM: Vector3d, gestureDistancePx: Float)
    fun onPlacementGestureUpdate(update: PlacementGestureUpdate) {
        if (update.phase == PlacementGesturePhase.Ended) {
            onPlacementGesture(
                startWorldPositionM = update.startWorldPositionM,
                endWorldPositionM = update.endWorldPositionM,
                gestureDistancePx = update.gestureDistancePx,
            )
        }
    }
}

enum class SceneInteractionMode {
    NAVIGATE_AND_SELECT,
    PLACE_BODY,
}

enum class PlacementGesturePhase {
    Started,
    Changed,
    Ended,
    Cancelled,
}

data class PlacementGestureUpdate(
    val phase: PlacementGesturePhase,
    val startWorldPositionM: Vector3d,
    val endWorldPositionM: Vector3d,
    val gestureDistancePx: Float,
)
