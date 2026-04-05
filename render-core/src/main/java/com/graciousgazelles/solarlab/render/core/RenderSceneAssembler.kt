package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import kotlin.collections.ArrayDeque

class RenderSceneAssembler(
    private val maxTrailPointsPerBody: Int = 128,
    private val selectedBodyHistoryMultiplier: Int = 2,
) {
    private val trackedTrailHistory: MutableMap<String, ArrayDeque<Vector3d>> = linkedMapOf()
    private var revisionCounter: Long = 0L

    init {
        require(maxTrailPointsPerBody >= 2) { "maxTrailPointsPerBody must be at least 2." }
        require(selectedBodyHistoryMultiplier >= 1) { "selectedBodyHistoryMultiplier must be at least 1." }
    }

    fun clear() {
        trackedTrailHistory.clear()
        revisionCounter = 0L
    }

    fun assemble(
        snapshot: SimulationSnapshot,
        options: RenderSceneAssemblyOptions = RenderSceneAssemblyOptions(),
    ): RenderSceneFrame {
        updateTrailHistory(snapshot, options)
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
                sourceMassKg = if (body.gravitationalRole == GravitationalRole.TRACER) body.massKg else body.sourceMassKg,
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
                val sampledPoints = sampleHistoryPoints(
                    points = history.toList(),
                    maxPoints = if (id == options.selectedBodyId) {
                        maxStoredTrailPointsPerBody()
                    } else {
                        maxTrailPointsPerBody
                    },
                )
                RenderTrail(
                    bodyId = id,
                    colorArgb = body.colorArgb,
                    alpha = body.trailAlpha(),
                    pointsM = sampledPoints,
                )
            }
        }
        val futurePaths = snapshot.bodies.mapNotNull { body ->
            if (!shouldIncludeFuturePath(body, options)) {
                null
            } else {
                buildFuturePath(body, options)
            }
        }

        return RenderSceneFrame(
            epochSeconds = snapshot.epochSeconds,
            authoritativeBodies = authoritative,
            tracerBodies = tracers,
            trails = trails,
            futurePaths = futurePaths,
            sourceRevision = revision,
        )
    }

    private fun updateTrailHistory(snapshot: SimulationSnapshot, options: RenderSceneAssemblyOptions) {
        val trackedBodies = snapshot.bodies.filter { shouldTrackTrail(it, options) }
        val activeIds = trackedBodies.map { it.id }.toSet()
        trackedTrailHistory.keys.retainAll(activeIds)

        for (body in trackedBodies) {
            val history = trackedTrailHistory.getOrPut(body.id) { ArrayDeque() }
            history.add(body.positionM)
            while (history.size > maxStoredTrailPointsPerBody()) {
                history.removeFirst()
            }
        }
    }

    private fun maxStoredTrailPointsPerBody(): Int = maxTrailPointsPerBody * selectedBodyHistoryMultiplier

    private fun shouldTrackTrail(body: BodyState, options: RenderSceneAssemblyOptions): Boolean = when (
        options.trailVisibilityMode
    ) {
        RenderTrailVisibilityMode.SELECTED_ONLY -> body.id == options.selectedBodyId
        RenderTrailVisibilityMode.TRACKED_CLASSES -> body.category.toRenderKind() in options.trackedTrailKinds
        RenderTrailVisibilityMode.ALL_OBJECTS -> true
    }

    private fun shouldIncludeFuturePath(
        body: BodyState,
        options: RenderSceneAssemblyOptions,
    ): Boolean = when (options.futurePathVisibilityMode) {
        RenderFuturePathVisibilityMode.NONE -> false
        RenderFuturePathVisibilityMode.SELECTED_ONLY -> body.id == options.selectedBodyId
        RenderFuturePathVisibilityMode.TRACKED_CLASSES -> body.category.toRenderKind() in options.trackedTrailKinds
        RenderFuturePathVisibilityMode.ALL_OBJECTS -> true
    }

    private fun buildFuturePath(
        body: BodyState,
        options: RenderSceneAssemblyOptions,
    ): RenderFuturePath {
        val sampleStepSeconds = options.futurePathHorizonSeconds / (options.futurePathSampleCount - 1).toDouble()
        val points = ArrayList<Vector3d>(options.futurePathSampleCount)
        for (index in 0 until options.futurePathSampleCount) {
            val dt = sampleStepSeconds * index.toDouble()
            points += body.positionM + (body.velocityMps * dt)
        }
        return RenderFuturePath(
            bodyId = body.id,
            colorArgb = body.colorArgb,
            alpha = body.futurePathAlpha(),
            pointsM = points,
            sampleStepSeconds = sampleStepSeconds,
        )
    }

    private fun sampleHistoryPoints(points: List<Vector3d>, maxPoints: Int): List<Vector3d> {
        if (points.size <= maxPoints) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1).toDouble()
        return buildList(maxPoints) {
            var cursor = 0.0
            repeat(maxPoints - 1) {
                add(points[cursor.toInt()])
                cursor += step
            }
            add(points.last())
        }
    }
}

private fun BodyState.trailAlpha(): Float = when {
    category == BodyCategory.STAR -> 0.20f
    gravitationalRole == GravitationalRole.MASSIVE -> 0.34f
    category == BodyCategory.DWARF_PLANET -> 0.42f
    category == BodyCategory.MOON -> 0.46f
    category == BodyCategory.COMET || category == BodyCategory.PROBE || category == BodyCategory.TEST_OBJECT -> 0.62f
    else -> 0.30f
}

private fun BodyState.futurePathAlpha(): Float = when {
    gravitationalRole == GravitationalRole.MASSIVE -> 0.22f
    category == BodyCategory.DWARF_PLANET || category == BodyCategory.MOON -> 0.28f
    category == BodyCategory.COMET || category == BodyCategory.PROBE || category == BodyCategory.TEST_OBJECT -> 0.38f
    else -> 0.24f
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
