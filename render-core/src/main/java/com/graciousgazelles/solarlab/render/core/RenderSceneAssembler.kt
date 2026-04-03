package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
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
                radiusM = body.radiusM,
                colorArgb = body.colorArgb,
                kind = body.category.toRenderKind(),
                isMassive = body.gravitationalRole == GravitationalRole.MASSIVE,
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
                    alpha = 0.25f,
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
        val trackedBodies = snapshot.bodies.filter {
            it.gravitationalRole == GravitationalRole.MASSIVE || it.category == BodyCategory.DWARF_PLANET
        }
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
