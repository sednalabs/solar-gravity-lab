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

data class RenderFuturePath(
    val bodyId: String,
    val colorArgb: Int,
    val alpha: Float,
    val pointsM: List<Vector3d>,
    val sampleStepSeconds: Double,
)

enum class RenderTrailVisibilityMode {
    SELECTED_ONLY,
    TRACKED_CLASSES,
    ALL_OBJECTS,
}

enum class RenderFuturePathVisibilityMode {
    NONE,
    SELECTED_ONLY,
    TRACKED_CLASSES,
    ALL_OBJECTS,
}

data class RenderSceneFrame(
    val epochSeconds: Double,
    val authoritativeBodies: List<RenderBody>,
    val tracerBodies: List<RenderBody>,
    val trails: List<RenderTrail>,
    val futurePaths: List<RenderFuturePath> = emptyList(),
    val sourceRevision: Long = 0L,
)

data class RenderSceneAssemblyOptions(
    val selectedBodyId: String? = null,
    val trailVisibilityMode: RenderTrailVisibilityMode = RenderTrailVisibilityMode.TRACKED_CLASSES,
    val trackedTrailKinds: Set<RenderBodyKind> = DEFAULT_TRACKED_TRAIL_KINDS,
    val futurePathVisibilityMode: RenderFuturePathVisibilityMode = RenderFuturePathVisibilityMode.SELECTED_ONLY,
    val futurePathHorizonSeconds: Double = 14_400.0,
    val futurePathSampleCount: Int = 24,
) {
    init {
        require(futurePathHorizonSeconds > 0.0) { "futurePathHorizonSeconds must be positive." }
        require(futurePathSampleCount >= 2) { "futurePathSampleCount must be at least 2." }
    }
}

val DEFAULT_TRACKED_TRAIL_KINDS: Set<RenderBodyKind> = setOf(
    RenderBodyKind.STAR,
    RenderBodyKind.PLANET,
    RenderBodyKind.DWARF_PLANET,
    RenderBodyKind.COMET,
    RenderBodyKind.PROBE,
    RenderBodyKind.TEST_OBJECT,
)
