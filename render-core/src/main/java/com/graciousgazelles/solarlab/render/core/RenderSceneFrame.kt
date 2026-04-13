package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d

enum class RenderBodyKind {
    STAR,
    PLANET,
    DWARF_PLANET,
    ASTEROID,
    COMET,
    PROBE,
    TEST_OBJECT,
}

data class RenderBody(
    val id: String,
    val name: String,
    val positionM: Vector3d,
    val velocityMps: Vector3d = Vector3d.ZERO,
    val radiusM: Double,
    val colorArgb: Int,
    val kind: RenderBodyKind,
    val isMassive: Boolean,
    val sourceMassKg: Double = 0.0,
    val hostBodyId: String? = null,
)

data class RenderTrail(
    val bodyId: String,
    val colorArgb: Int,
    val alpha: Float,
    val pointsM: List<Vector3d>,
)

data class RenderSceneFrame(
    val epochSeconds: Double,
    val authoritativeBodies: List<RenderBody>,
    val tracerBodies: List<RenderBody>,
    val trails: List<RenderTrail>,
    val sourceRevision: Long = 0L,
)
