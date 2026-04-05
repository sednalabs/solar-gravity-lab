package com.graciousgazelles.solarlab.feature.lab.render

import com.graciousgazelles.solarlab.render.core.RenderTrailVisibilityMode

data class RenderSceneOverlaySettings(
    val trailVisibilityMode: RenderTrailVisibilityMode = RenderTrailVisibilityMode.TRACKED_CLASSES,
    val showPredictedTrails: Boolean = false,
)
