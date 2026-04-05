package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.simulation.SimulationEngine
import kotlin.collections.ArrayDeque
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
        val futurePaths = buildFuturePaths(snapshot, options)

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

    private fun buildFuturePaths(
        snapshot: SimulationSnapshot,
        options: RenderSceneAssemblyOptions,
    ): List<RenderFuturePath> {
        if (options.futurePathVisibilityMode == RenderFuturePathVisibilityMode.NONE) {
            return emptyList()
        }
        val includedBodyIds = snapshot.bodies
            .filter { shouldIncludeFuturePath(it, options) }
            .mapTo(linkedSetOf()) { it.id }
        if (includedBodyIds.isEmpty()) {
            return emptyList()
        }

        val bodiesById = snapshot.bodies.associateBy { it.id }
        val plan = buildForecastPlan(
            snapshot = snapshot,
            bodiesById = bodiesById,
            includedBodyIds = includedBodyIds,
            options = options,
        )
        val pointsByBodyId = linkedMapOf<String, MutableList<Vector3d>>()
        snapshot.bodies.forEach { body ->
            if (body.id in includedBodyIds) {
                pointsByBodyId[body.id] = mutableListOf(body.positionM)
            }
        }

        val engine = SimulationEngine(
            initialSnapshot = snapshot,
            config = SimulationConfig(
                collisionMode = CollisionMode.NONE,
                includeTracerMutualGravity = options.includeTracerMutualGravityInForecast,
            ),
        )

        var elapsedSeconds = 0.0
        var nextSampleSeconds = plan.sampleStepSeconds
        while (elapsedSeconds + FORECAST_EPSILON < plan.horizonSeconds) {
            val stepSeconds = min(plan.integrationStepSeconds, plan.horizonSeconds - elapsedSeconds)
            val forecastSnapshot = engine.step(
                deltaTimeSeconds = stepSeconds,
                recomputeDiagnostics = false,
            ).snapshot
            elapsedSeconds += stepSeconds
            var sampledPointCount = pointsByBodyId.values.firstOrNull()?.size ?: 0
            while (
                elapsedSeconds + FORECAST_EPSILON >= nextSampleSeconds &&
                sampledPointCount < plan.sampleCount
            ) {
                forecastSnapshot.bodies.forEach { body ->
                    pointsByBodyId[body.id]?.add(body.positionM)
                }
                sampledPointCount += 1
                nextSampleSeconds += plan.sampleStepSeconds
            }
        }

        return snapshot.bodies.mapNotNull { body ->
            val points = pointsByBodyId[body.id] ?: return@mapNotNull null
            RenderFuturePath(
                bodyId = body.id,
                colorArgb = body.colorArgb,
                alpha = body.futurePathAlpha(),
                pointsM = points,
                horizonSeconds = plan.horizonSeconds,
                sampleStepSeconds = plan.sampleStepSeconds,
            )
        }
    }

    private fun buildForecastPlan(
        snapshot: SimulationSnapshot,
        bodiesById: Map<String, BodyState>,
        includedBodyIds: Set<String>,
        options: RenderSceneAssemblyOptions,
    ): ForecastPlan {
        val requestedHorizonSeconds = options.futurePathHorizonSeconds
        val requestedSampleCount = options.futurePathSampleCount
        val selectedBody = options.selectedBodyId?.let(bodiesById::get)
        val adaptivePeriodSeconds = if (
            options.futurePathForecastMode == RenderFuturePathForecastMode.ADAPTIVE_ORBITAL &&
            includedBodyIds.size == 1 &&
            selectedBody != null &&
            selectedBody.id in includedBodyIds
        ) {
            estimateBoundOrbitalPeriodSeconds(selectedBody, snapshot.bodies)
        } else {
            null
        }

        val horizonSeconds = adaptivePeriodSeconds
            ?.coerceAtLeast(requestedHorizonSeconds)
            ?.coerceAtMost(options.futurePathMaxHorizonSeconds)
            ?: requestedHorizonSeconds
        val sampleCount = if (horizonSeconds > requestedHorizonSeconds + FORECAST_EPSILON) {
            val scaledSampleCount = 1 + ceil(
                (requestedSampleCount - 1).toDouble() * (horizonSeconds / requestedHorizonSeconds),
            ).toInt()
            min(options.futurePathMaxSampleCount, max(requestedSampleCount, scaledSampleCount))
        } else {
            requestedSampleCount
        }
        val sampleStepSeconds = horizonSeconds / (sampleCount - 1).toDouble()
        val targetIntegrationStepSeconds = adaptivePeriodSeconds
            ?.div(512.0)
            ?.coerceIn(MIN_FORECAST_INTEGRATION_STEP_SECONDS, MAX_FORECAST_INTEGRATION_STEP_SECONDS)
            ?: sampleStepSeconds
        val integrationSteps = min(
            options.futurePathMaxIntegrationSteps,
            max(sampleCount - 1, ceil(horizonSeconds / targetIntegrationStepSeconds).toInt()),
        )
        val integrationStepSeconds = horizonSeconds / integrationSteps.toDouble()
        return ForecastPlan(
            horizonSeconds = horizonSeconds,
            sampleCount = sampleCount,
            sampleStepSeconds = sampleStepSeconds,
            integrationStepSeconds = integrationStepSeconds,
        )
    }

    private fun estimateBoundOrbitalPeriodSeconds(
        body: BodyState,
        bodies: List<BodyState>,
    ): Double? {
        val primary = selectForecastPrimary(body, bodies) ?: return null
        val relativePosition = body.positionM - primary.positionM
        val relativeVelocity = body.velocityMps - primary.velocityMps
        val radius = relativePosition.magnitude()
        if (radius <= FORECAST_EPSILON) {
            return null
        }
        val mu = FORECAST_GRAVITATIONAL_CONSTANT * (primary.massKg + body.massKg)
        if (mu <= FORECAST_EPSILON) {
            return null
        }

        val speedSquared = relativeVelocity.magnitudeSquared()
        val specificOrbitalEnergy = (speedSquared * 0.5) - (mu / radius)
        if (specificOrbitalEnergy < -FORECAST_EPSILON) {
            val semiMajorAxis = -mu / (2.0 * specificOrbitalEnergy)
            return 2.0 * PI * sqrt((semiMajorAxis * semiMajorAxis * semiMajorAxis) / mu)
        }

        val speed = sqrt(speedSquared)
        return if (speed > FORECAST_EPSILON) {
            (2.0 * PI * radius / speed).coerceAtMost(MAX_FALLBACK_ORBITAL_PERIOD_SECONDS)
        } else {
            null
        }
    }

    private fun selectForecastPrimary(body: BodyState, bodies: List<BodyState>): BodyState? {
        body.hostBodyId?.let { hostId ->
            bodies.firstOrNull { candidate ->
                candidate.id == hostId && candidate.gravitationalRole == GravitationalRole.MASSIVE
            }?.let { return it }
        }

        return bodies
            .asSequence()
            .filter { candidate ->
                candidate.id != body.id &&
                    candidate.gravitationalRole == GravitationalRole.MASSIVE &&
                    candidate.massKg > FORECAST_EPSILON
            }
            .maxByOrNull { candidate ->
                val distanceSquared = candidate.positionM.distanceSquaredTo(body.positionM)
                    .coerceAtLeast(FORECAST_EPSILON)
                candidate.massKg / distanceSquared
            }
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

private data class ForecastPlan(
    val horizonSeconds: Double,
    val sampleCount: Int,
    val sampleStepSeconds: Double,
    val integrationStepSeconds: Double,
)

private const val FORECAST_GRAVITATIONAL_CONSTANT: Double = 6.67430e-11
private const val FORECAST_EPSILON: Double = 1e-9
private const val MIN_FORECAST_INTEGRATION_STEP_SECONDS: Double = 60.0
private const val MAX_FORECAST_INTEGRATION_STEP_SECONDS: Double = 6.0 * 3_600.0
private const val MAX_FALLBACK_ORBITAL_PERIOD_SECONDS: Double = 365.25 * 86_400.0

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
