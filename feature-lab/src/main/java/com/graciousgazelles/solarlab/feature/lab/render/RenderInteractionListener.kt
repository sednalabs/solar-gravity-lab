package com.graciousgazelles.solarlab.feature.lab.render

import com.graciousgazelles.solarlab.core.math.Vector3d

interface RenderInteractionListener {
    fun onBodySelectionChanged(bodyId: String?)
    fun onPlacementGesture(startWorldPositionM: Vector3d, endWorldPositionM: Vector3d, gestureDistancePx: Float)
}

enum class SceneInteractionMode {
    NAVIGATE_AND_SELECT,
    PLACE_BODY,
}
