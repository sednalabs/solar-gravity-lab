package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import kotlin.collections.ArrayDeque

class RenderSceneAssembler(
    private val maxTrailPointsPerBody: Int = 128,
) {
    private val trackedTrailHistory: MutableMap<String, ArrayDeque<Vector3d>> = linkedMapOf()
    private var revisionCounter: Long = 0L

    fun clear() {
        trackedTrailHistory.clear()
        revisionCounter = 0L
    }

    fun assemble(snapshot: SimulationSnapshot): RenderSceneFrame {
        updateTrailHistory(snapshot)
        val revision = ++revisionCounter

        val bodiesById = snapshot.bodies.associateBy { it.id }
        val authoritative = ArrayList<RenderBody>(snapshot.bodies.size)
        val tracers = ArrayList<RenderBody>()
        for (body in snapshot.bodies) {
            val renderBody = RenderBody(
                id = body.id,
                name = body.name,
                positionM = body.positionM,
                velocityMps = body.velocityMps,
                radiusM = body.radiusM,
                colorArgb = body.colorArgb,
                kind = body.category.toRenderKind(),
                isMassive = body.gravitationalRole == GravitationalRole.MASSIVE,
                sourceMassKg = body.sourceMassKg,
                hostBodyId = body.hostBodyId,
            )
            if (body.gravitationalRole == GravitationalRole.TRACER) {
                tracers += renderBody
            } else {
                authoritative += renderBody
            }
        }

        val trails = trackedTrailHistory.mapNotNull { (id, history) ->
            if (history.size < 2) {
                null
            } else {
                val body = bodiesById[id] ?: return@mapNotNull null
                RenderTrail(
                    bodyId = id,
                    colorArgb = body.colorArgb,
                    alpha = body.trailAlpha(),
                    pointsM = history.toList(),
                )
            }
        }

        return RenderSceneFrame(
            epochSeconds = snapshot.epochSeconds,
            authoritativeBodies = authoritative,
            tracerBodies = tracers,
            trails = trails,
            sourceRevision = revision,
        )
    }

    private fun updateTrailHistory(snapshot: SimulationSnapshot) {
        val trackedBodies = snapshot.bodies.filter { it.shouldTrackTrail() }
        val activeIds = trackedBodies.map { it.id }.toSet()
        trackedTrailHistory.keys.retainAll(activeIds)

        for (body in trackedBodies) {
            val history = trackedTrailHistory.getOrPut(body.id) { ArrayDeque() }
            history.add(body.positionM)
            while (history.size > maxTrailPointsPerBody) {
                history.removeFirst()
            }
        }
    }
}

private fun BodyState.shouldTrackTrail(): Boolean = when {
    gravitationalRole == GravitationalRole.MASSIVE -> true
    category == BodyCategory.DWARF_PLANET -> true
    category == BodyCategory.MOON -> true
    category == BodyCategory.COMET -> true
    category == BodyCategory.PROBE -> true
    category == BodyCategory.TEST_OBJECT -> true
    else -> false
}

private fun BodyState.trailAlpha(): Float = when {
    category == BodyCategory.STAR -> 0.20f
    gravitationalRole == GravitationalRole.MASSIVE -> 0.34f
    category == BodyCategory.DWARF_PLANET -> 0.42f
    category == BodyCategory.MOON -> 0.46f
    category == BodyCategory.COMET || category == BodyCategory.PROBE || category == BodyCategory.TEST_OBJECT -> 0.62f
    else -> 0.30f
}

private fun BodyCategory.toRenderKind(): RenderBodyKind = when (this) {
    BodyCategory.STAR -> RenderBodyKind.STAR
    BodyCategory.PLANET -> RenderBodyKind.PLANET
    BodyCategory.MOON -> RenderBodyKind.PLANET
    BodyCategory.DWARF_PLANET -> RenderBodyKind.DWARF_PLANET
    BodyCategory.ASTEROID -> RenderBodyKind.ASTEROID
    BodyCategory.COMET -> RenderBodyKind.COMET
    BodyCategory.PROBE -> RenderBodyKind.PROBE
    BodyCategory.TEST_OBJECT -> RenderBodyKind.TEST_OBJECT
}
