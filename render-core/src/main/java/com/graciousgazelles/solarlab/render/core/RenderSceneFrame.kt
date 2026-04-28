package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import java.util.Locale

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

enum class TraceLayerMode {
    FOCUS,
    ALL,
    OFF,
}

data class RenderLayerOptions(
    val traceLayerMode: TraceLayerMode = TraceLayerMode.FOCUS,
    val focusedBodyIds: Set<String> = emptySet(),
)

data class RenderSceneFrame(
    val epochSeconds: Double,
    val authoritativeBodies: List<RenderBody>,
    val tracerBodies: List<RenderBody>,
    val trails: List<RenderTrail>,
    val sourceRevision: Long = 0L,
)

fun RenderSceneFrame.withLayerOptions(options: RenderLayerOptions): RenderSceneFrame {
    val focusIds = options.focusedBodyIds
        .map { it.lowercase(Locale.US) }
        .filter { it.isNotBlank() }
        .toSet()

    return when (options.traceLayerMode) {
        TraceLayerMode.ALL -> this
        TraceLayerMode.OFF -> copy(
            tracerBodies = emptyList(),
            trails = emptyList(),
        )
        TraceLayerMode.FOCUS -> {
            if (focusIds.isEmpty()) {
                this
            } else {
                val focusedTracerBodies = tracerBodies.filter { body ->
                    val bodyId = body.id.lowercase(Locale.US)
                    val hostId = body.hostBodyId?.lowercase(Locale.US)
                    bodyId in focusIds || (hostId != null && hostId in focusIds)
                }
                val focusedTrailIds = focusIds + focusedTracerBodies.map { it.id.lowercase(Locale.US) }
                copy(
                    tracerBodies = focusedTracerBodies,
                    trails = trails.filter { trail ->
                        trail.bodyId.lowercase(Locale.US) in focusedTrailIds
                    },
                )
            }
        }
    }
}
