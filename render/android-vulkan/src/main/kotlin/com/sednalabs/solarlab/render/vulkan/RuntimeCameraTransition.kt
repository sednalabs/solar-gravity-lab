package com.sednalabs.solarlab.render.vulkan

import com.graciousgazelles.solarlab.render.core.CameraState

/** Centres are local offsets when a reference is present; native presentation resolves its live position. */
internal data class RuntimeCameraTransition(
    val start: CameraState,
    val target: CameraState,
    val referenceBodyId: String?,
)
