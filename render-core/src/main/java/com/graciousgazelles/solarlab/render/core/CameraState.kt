package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants

data class CameraState(
    val centerM: Vector3d = Vector3d.ZERO,
    val viewRadiusM: Double = 24.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
)
