package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.HardwareAccelerationProfile
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.PhysicsAccuracyTelemetryMetric
import com.graciousgazelles.solarlab.core.model.PhysicsAccuracyTelemetryProvenance
import com.graciousgazelles.solarlab.core.model.PhysicsAccuracyTelemetryReport
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

class PhysicsAccuracyTelemetryReportGenerator(
    private val scenarioFactory: () -> SimulationSnapshot = {
        SolarSystemScenarios.defaultLabScenario(
            asteroidCount = 0,
            oortCount = 0,
            config = SimulationConfig(collisionMode = CollisionMode.NONE),
            includeCuratedSmallBodies = false,
            includeSyntheticAsteroidBelt = false,
            includeSyntheticOortCloud = false,
        )
    },
) {

    data class GenerationConfig(
        val runLabel: String = "validation-lab-physics-accuracy",
        val scenarioId: String = "major-bodies-with-starter-moons",
        val scenarioLabel: String = "Solar major bodies + starter moons baseline",
        val stepSeconds: Double = DEFAULT_STEP_SECONDS,
        val steps: Int = DEFAULT_STEPS,
        val baselineStepSeconds: Double = 3600.0,
    )

    fun generate(config: GenerationConfig = GenerationConfig()): PhysicsAccuracyTelemetryReport {
        require(config.stepSeconds.isFinite()) { "stepSeconds must be finite" }
        require(config.stepSeconds > 0.0) { "stepSeconds must be > 0" }
        require(config.steps > 0) { "steps must be > 0" }
        require(config.baselineStepSeconds.isFinite()) { "baselineStepSeconds must be finite" }
        require(config.baselineStepSeconds > 0.0) { "baselineStepSeconds must be > 0" }

        val snapshot = scenarioFactory()
        val simulatedDurationSeconds = config.stepSeconds * config.steps
        val effectiveBaselineStepSeconds = min(config.baselineStepSeconds, config.stepSeconds / 2.0)
        val engine = SimulationEngine(
            initialSnapshot = snapshot,
            config = SimulationConfig(collisionMode = CollisionMode.NONE),
        )
        val baselineRun = runSimulationUntilDuration(
            initialSnapshot = snapshot,
            stepSeconds = effectiveBaselineStepSeconds,
            totalDurationSeconds = simulatedDurationSeconds,
        )
        val baselineFinalSnapshot = baselineRun.snapshot
        val baselineFinalDiagnostics = baselineRun.diagnostics

        val startingDiagnostics = engine.diagnostics()
        val coarseTimeline = ArrayList<SimulationSnapshot>(config.steps + 1)
        coarseTimeline += snapshot
        repeat(config.steps) {
            engine.step(config.stepSeconds)
            coarseTimeline += engine.snapshot()
        }

        val finalSnapshot = coarseTimeline.last()
        val finalDiagnostics = engine.diagnostics()
        val sunStart = snapshot.bodies.firstOrNull { it.id == "sun" }
            ?: error("Scenario '${config.scenarioId}' did not include sun body")
        val earthStart = snapshot.bodies.firstOrNull { it.id == "earth" }
            ?: error("Scenario '${config.scenarioId}' did not include earth body")
        val sun = finalSnapshot.bodies.firstOrNull { it.id == "sun" }
            ?: error("Scenario '${config.scenarioId}' did not include sun body")
        val earth = finalSnapshot.bodies.firstOrNull { it.id == "earth" }
            ?: error("Scenario '${config.scenarioId}' did not include earth body")

        val earthDistanceAu = (earth.positionM - sun.positionM).magnitude() / PhysicalConstants.ASTRONOMICAL_UNIT_M
        val earthStartDistanceAu = (earthStart.positionM - sunStart.positionM).magnitude() / PhysicalConstants.ASTRONOMICAL_UNIT_M
        val energyDeltaJ = finalDiagnostics.totalEnergyJ - startingDiagnostics.totalEnergyJ
        val relativeEnergyDrift = if (startingDiagnostics.totalEnergyJ == 0.0) {
            0.0
        } else {
            energyDeltaJ / startingDiagnostics.totalEnergyJ
        }
        val angularMomentumStartMagnitude = startingDiagnostics.angularMomentumKgM2PerS.magnitude()
        val angularMomentumDeltaMagnitude =
            (finalDiagnostics.angularMomentumKgM2PerS - startingDiagnostics.angularMomentumKgM2PerS).magnitude()
        val relativeAngularMomentumDrift = if (angularMomentumStartMagnitude == 0.0) {
            0.0
        } else {
            angularMomentumDeltaMagnitude / angularMomentumStartMagnitude
        }
        val barycenterDriftMeters = finalDiagnostics.barycenterM.distanceTo(startingDiagnostics.barycenterM)
        val barycenterVelocityDriftMps =
            finalDiagnostics.barycenterVelocityMps.distanceTo(startingDiagnostics.barycenterVelocityMps)
        val barycenterFineBaselineDistanceErrorM =
            finalDiagnostics.barycenterM.distanceTo(baselineFinalDiagnostics.barycenterM)
        val barycenterFineBaselineVelocityErrorMps =
            finalDiagnostics.barycenterVelocityMps.distanceTo(baselineFinalDiagnostics.barycenterVelocityMps)
        val baselineAngularMomentumMagnitude = baselineFinalDiagnostics.angularMomentumKgM2PerS.magnitude()
        val angularMomentumFineBaselineErrorRatio = if (baselineAngularMomentumMagnitude == 0.0) {
            0.0
        } else {
            abs(finalDiagnostics.angularMomentumKgM2PerS.magnitude() - baselineAngularMomentumMagnitude) /
                baselineAngularMomentumMagnitude
        }
        val moonStart = snapshot.bodies.firstOrNull { it.id == "moon" && it.hostBodyId == "earth" }
        val moonFinal = finalSnapshot.bodies.firstOrNull { it.id == "moon" && it.hostBodyId == "earth" }
        val moonBaseline = baselineFinalSnapshot.bodies.firstOrNull { it.id == "moon" && it.hostBodyId == "earth" }
        val earthBaseline = baselineFinalSnapshot.bodies.firstOrNull { it.id == "earth" }
        val moonToEarthMetrics = if (moonStart != null && moonFinal != null) {
            val moonEarthStartDistanceAu = (moonStart.positionM - earthStart.positionM).magnitude() /
                PhysicalConstants.ASTRONOMICAL_UNIT_M
            val moonEarthFinalDistanceAu = (moonFinal.positionM - earth.positionM).magnitude() /
                PhysicalConstants.ASTRONOMICAL_UNIT_M
            val moonEarthBaselineDistanceAu = if (moonBaseline != null && earthBaseline != null) {
                (moonBaseline.positionM - earthBaseline.positionM).magnitude() / PhysicalConstants.ASTRONOMICAL_UNIT_M
            } else {
                null
            }
            val moonEarthDistanceDriftAu = moonEarthFinalDistanceAu - moonEarthStartDistanceAu
            val moonEarthRelativeDrift = if (moonEarthStartDistanceAu == 0.0) {
                0.0
            } else {
                moonEarthDistanceDriftAu / moonEarthStartDistanceAu
            }
            val moonEarthBaselineErrorAu = moonEarthBaselineDistanceAu?.let { abs(moonEarthFinalDistanceAu - it) }
            val moonEarthBaselineErrorRatio = if (moonEarthBaselineDistanceAu == null || moonEarthBaselineDistanceAu == 0.0) {
                null
            } else {
                moonEarthBaselineErrorAu!! / moonEarthBaselineDistanceAu
            }

            listOfNotNull(
                PhysicsAccuracyTelemetryMetric(
                    name = "moon_earth_distance_au",
                    value = moonEarthFinalDistanceAu,
                    unit = "au",
                    description = "Earth-Moon distance at final step, measured in astronomical units",
                ),
                PhysicsAccuracyTelemetryMetric(
                    name = "moon_earth_distance_change_au",
                    value = moonEarthDistanceDriftAu,
                    unit = "au",
                    description = "Final Earth-Moon distance minus initial Earth-Moon distance",
                ),
                PhysicsAccuracyTelemetryMetric(
                    name = "moon_earth_distance_change_ratio",
                    value = moonEarthRelativeDrift,
                    unit = "ratio",
                    description = "Earth-Moon distance change normalized by initial Earth-Moon distance",
                ),
                moonEarthBaselineErrorAu?.let {
                    PhysicsAccuracyTelemetryMetric(
                        name = "moon_earth_distance_fine_baseline_error_au",
                        value = it,
                        unit = "au",
                        description = "Absolute error of final Earth-Moon distance versus finer baseline",
                    )
                },
                moonEarthBaselineErrorRatio?.let {
                    PhysicsAccuracyTelemetryMetric(
                        name = "moon_earth_distance_fine_baseline_error_ratio",
                        value = it,
                        unit = "ratio",
                        description = "Relative error of final Earth-Moon distance versus finer baseline",
                    )
                },
            )
        } else {
            emptyList()
        }
        val tracerParityMetrics = buildGpuTracerParityMetrics(
            initialSnapshot = snapshot,
            coarseTimeline = coarseTimeline,
            stepSeconds = config.stepSeconds,
        )

        return PhysicsAccuracyTelemetryReport(
            schemaVersion = SCHEMA_VERSION,
            runLabel = config.runLabel,
            scenarioId = config.scenarioId,
            scenarioLabel = config.scenarioLabel,
            stepSeconds = config.stepSeconds,
            steps = config.steps,
            simulatedSeconds = config.stepSeconds * config.steps,
            provenance = PhysicsAccuracyTelemetryProvenance(
                timelineMode = snapshot.timelineMode,
                seedEpochJulianDateTdb = snapshot.referenceEpochJdTdb,
                datasetLabel = snapshot.provenanceLabel,
                datasetSource = snapshot.provenanceSource,
                hardwareAccelerationProfile = HardwareAccelerationProfile(
                    target = "host-jvm",
                    authoritativeSolverBackend = "kotlin-reference",
                    tracerIntegrationBackend = "cpu-direct",
                ),
            ),
            metrics = buildList {
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "relative_energy_drift",
                        value = relativeEnergyDrift,
                        unit = "ratio",
                        description = "Final total energy drift ratio against initial total energy",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "absolute_energy_drift_joules",
                        value = energyDeltaJ,
                        unit = "joules",
                        description = "Final total energy minus initial total energy",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "relative_angular_momentum_drift",
                        value = relativeAngularMomentumDrift,
                        unit = "ratio",
                        description = "Angular momentum vector drift magnitude normalized by initial magnitude",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "absolute_angular_momentum_drift_kg_m2_per_s",
                        value = angularMomentumDeltaMagnitude,
                        unit = "kg_m2_per_s",
                        description = "Absolute drift magnitude of total angular momentum vector",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "barycenter_drift_m",
                        value = barycenterDriftMeters,
                        unit = "meters",
                        description = "Distance between final and initial system barycenter positions",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "barycenter_velocity_drift_mps",
                        value = barycenterVelocityDriftMps,
                        unit = "mps",
                        description = "Distance between final and initial barycenter velocity vectors",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "angular_momentum_fine_baseline_error_ratio",
                        value = angularMomentumFineBaselineErrorRatio,
                        unit = "ratio",
                        description = "Relative error of angular momentum magnitude versus finer baseline",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "barycenter_fine_baseline_distance_error_m",
                        value = barycenterFineBaselineDistanceErrorM,
                        unit = "meters",
                        description = "Distance error of final barycenter position versus finer baseline",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "barycenter_fine_baseline_velocity_error_mps",
                        value = barycenterFineBaselineVelocityErrorMps,
                        unit = "mps",
                        description = "Velocity-vector error of final barycenter velocity versus finer baseline",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "earth_distance_au",
                        value = earthDistanceAu,
                        unit = "au",
                        description = "Sun-Earth distance in astronomical units at final step",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "earth_distance_change_from_initial_au",
                        value = abs(earthDistanceAu - earthStartDistanceAu),
                        unit = "au",
                        description = "Absolute change in Sun-Earth distance from the initial step",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "massive_body_count",
                        value = finalDiagnostics.massiveBodyCount.toDouble(),
                        unit = "count",
                        description = "Massive body count in the simulated snapshot",
                    ),
                )
                add(
                    PhysicsAccuracyTelemetryMetric(
                        name = "tracer_body_count",
                        value = finalDiagnostics.tracerBodyCount.toDouble(),
                        unit = "count",
                        description = "Tracer body count in the simulated snapshot",
                    ),
                )
                addAll(moonToEarthMetrics)
                addAll(tracerParityMetrics)
            },
        )
    }

    private fun runSimulationUntilDuration(
        initialSnapshot: SimulationSnapshot,
        stepSeconds: Double,
        totalDurationSeconds: Double,
    ): SimulationRunResult {
        require(stepSeconds.isFinite()) { "stepSeconds must be finite" }
        require(stepSeconds > 0.0) { "stepSeconds must be > 0" }
        require(totalDurationSeconds.isFinite()) { "totalDurationSeconds must be finite" }
        require(totalDurationSeconds >= 0.0) { "totalDurationSeconds must be >= 0" }

        val engine = SimulationEngine(
            initialSnapshot = initialSnapshot,
            config = SimulationConfig(collisionMode = CollisionMode.NONE),
        )

        var remainingDurationSeconds = totalDurationSeconds
        while (remainingDurationSeconds > 0.0) {
            val nextStep = min(stepSeconds, remainingDurationSeconds)
            engine.step(nextStep)
            remainingDurationSeconds -= nextStep
        }
        return SimulationRunResult(
            snapshot = engine.snapshot(),
            diagnostics = engine.diagnostics(),
        )
    }

    private data class SimulationRunResult(
        val snapshot: SimulationSnapshot,
        val diagnostics: SystemDiagnostics,
    )

    private fun buildGpuTracerParityMetrics(
        initialSnapshot: SimulationSnapshot,
        coarseTimeline: List<SimulationSnapshot>,
        stepSeconds: Double,
    ): List<PhysicsAccuracyTelemetryMetric> {
        if (coarseTimeline.size < 2) return emptyList()

        val sun = initialSnapshot.bodies.firstOrNull { it.id == "sun" } ?: return emptyList()
        val mediumIds = mutableListOf<String>()
        val farIds = mutableListOf<String>()
        for (body in initialSnapshot.bodies) {
            if (body.gravitationalRole != com.graciousgazelles.solarlab.core.model.GravitationalRole.TRACER) continue
            val heliocentricDistanceAu =
                body.positionM.distanceTo(sun.positionM) / PhysicalConstants.ASTRONOMICAL_UNIT_M
            when {
                heliocentricDistanceAu <= GPU_TRACER_MEDIUM_MAX_HELIOCENTRIC_DISTANCE_AU -> mediumIds += body.id
                else -> farIds += body.id
            }
        }

        val shortHorizonSteps = min(GPU_TRACER_SHORT_HORIZON_STEPS, coarseTimeline.lastIndex)
        val mediumHorizonSteps = min(GPU_TRACER_MEDIUM_HORIZON_MAX_STEPS, coarseTimeline.lastIndex)
        val emulatedStates = emulateGpuTracerTimeline(
            initialSnapshot = initialSnapshot,
            coarseTimeline = coarseTimeline,
            stepSeconds = stepSeconds,
            maxSteps = mediumHorizonSteps,
        )

        return buildList {
            addAll(
                buildTracerCohortMetrics(
                    cohortName = "medium",
                    tracerIds = mediumIds,
                    emulatedStates = emulatedStates,
                    coarseTimeline = coarseTimeline,
                    shortHorizonSteps = shortHorizonSteps,
                    mediumHorizonSteps = mediumHorizonSteps,
                ),
            )
            addAll(
                buildTracerCohortMetrics(
                    cohortName = "far",
                    tracerIds = farIds,
                    emulatedStates = emulatedStates,
                    coarseTimeline = coarseTimeline,
                    shortHorizonSteps = shortHorizonSteps,
                    mediumHorizonSteps = mediumHorizonSteps,
                ),
            )
        }
    }

    private fun emulateGpuTracerTimeline(
        initialSnapshot: SimulationSnapshot,
        coarseTimeline: List<SimulationSnapshot>,
        stepSeconds: Double,
        maxSteps: Int,
    ): Map<String, List<TracerXyState>> {
        val stateById = linkedMapOf<String, TracerXyState>()
        val timelineById = linkedMapOf<String, MutableList<TracerXyState>>()
        initialSnapshot.bodies
            .filter { it.gravitationalRole == com.graciousgazelles.solarlab.core.model.GravitationalRole.TRACER }
            .forEach { body ->
                val state = TracerXyState(
                    x = body.positionM.x,
                    y = body.positionM.y,
                    vx = body.velocityMps.x,
                    vy = body.velocityMps.y,
                )
                stateById[body.id] = state
                timelineById.getOrPut(body.id) { mutableListOf() }.add(state)
            }

        repeat(maxSteps) { stepIndex ->
            val influenceSnapshot = coarseTimeline[stepIndex]
            val influences = influenceSnapshot.bodies
                .filter { it.gravitationalRole == com.graciousgazelles.solarlab.core.model.GravitationalRole.MASSIVE }
                .map { InfluenceBodyXy(x = it.positionM.x, y = it.positionM.y, massKg = it.massKg) }
            stateById.forEach { (bodyId, state) ->
                val nextState = integrateGpuStyleTracerStep(
                    state = state,
                    influences = influences,
                    stepSeconds = stepSeconds,
                )
                stateById[bodyId] = nextState
                timelineById.getValue(bodyId).add(nextState)
            }
        }

        return timelineById
    }

    private fun buildTracerCohortMetrics(
        cohortName: String,
        tracerIds: List<String>,
        emulatedStates: Map<String, List<TracerXyState>>,
        coarseTimeline: List<SimulationSnapshot>,
        shortHorizonSteps: Int,
        mediumHorizonSteps: Int,
    ): List<PhysicsAccuracyTelemetryMetric> {
        val shortErrors = tracerIds.mapNotNull { bodyId ->
            tracerXyErrorAtStep(bodyId, shortHorizonSteps, emulatedStates, coarseTimeline)
        }
        val mediumErrors = tracerIds.mapNotNull { bodyId ->
            tracerXyErrorAtStep(bodyId, mediumHorizonSteps, emulatedStates, coarseTimeline)
        }
        return listOf(
            PhysicsAccuracyTelemetryMetric(
                name = "gpu_tracer_${cohortName}_cohort_count",
                value = tracerIds.size.toDouble(),
                unit = "count",
                description = "Tracer cohort size for GPU parity checks using ${cohortName} heliocentric-distance selection",
            ),
            PhysicsAccuracyTelemetryMetric(
                name = "gpu_tracer_${cohortName}_short_horizon_steps",
                value = shortHorizonSteps.toDouble(),
                unit = "steps",
                description = "Short-horizon coarse-step count used for GPU tracer parity checks",
            ),
            PhysicsAccuracyTelemetryMetric(
                name = "gpu_tracer_${cohortName}_short_horizon_xy_rms_error_m",
                value = rms(shortErrors),
                unit = "meters",
                description = "RMS XY position error versus CPU coarse reference at the short horizon for the ${cohortName} tracer cohort",
            ),
            PhysicsAccuracyTelemetryMetric(
                name = "gpu_tracer_${cohortName}_short_horizon_xy_max_error_m",
                value = maxOrZero(shortErrors),
                unit = "meters",
                description = "Maximum XY position error versus CPU coarse reference at the short horizon for the ${cohortName} tracer cohort",
            ),
            PhysicsAccuracyTelemetryMetric(
                name = "gpu_tracer_${cohortName}_medium_horizon_steps",
                value = mediumHorizonSteps.toDouble(),
                unit = "steps",
                description = "Medium-horizon coarse-step count used for GPU tracer parity checks",
            ),
            PhysicsAccuracyTelemetryMetric(
                name = "gpu_tracer_${cohortName}_medium_horizon_xy_rms_error_m",
                value = rms(mediumErrors),
                unit = "meters",
                description = "RMS XY position error versus CPU coarse reference at the medium horizon for the ${cohortName} tracer cohort",
            ),
            PhysicsAccuracyTelemetryMetric(
                name = "gpu_tracer_${cohortName}_medium_horizon_xy_max_error_m",
                value = maxOrZero(mediumErrors),
                unit = "meters",
                description = "Maximum XY position error versus CPU coarse reference at the medium horizon for the ${cohortName} tracer cohort",
            ),
        )
    }

    private fun tracerXyErrorAtStep(
        bodyId: String,
        stepIndex: Int,
        emulatedStates: Map<String, List<TracerXyState>>,
        coarseTimeline: List<SimulationSnapshot>,
    ): Double? {
        if (stepIndex <= 0 || stepIndex >= coarseTimeline.size) return null
        val state = emulatedStates[bodyId]?.getOrNull(stepIndex) ?: return null
        val cpuBody = coarseTimeline[stepIndex].bodies.firstOrNull { it.id == bodyId } ?: return null
        return hypot(cpuBody.positionM.x - state.x, cpuBody.positionM.y - state.y)
    }

    private fun integrateGpuStyleTracerStep(
        state: TracerXyState,
        influences: List<InfluenceBodyXy>,
        stepSeconds: Double,
    ): TracerXyState {
        val acceleration0 = accelerationAt(state.x, state.y, influences)
        val velocityHalfX = state.vx + (acceleration0.first * (0.5 * stepSeconds))
        val velocityHalfY = state.vy + (acceleration0.second * (0.5 * stepSeconds))
        val positionNextX = state.x + (velocityHalfX * stepSeconds)
        val positionNextY = state.y + (velocityHalfY * stepSeconds)
        val acceleration1 = accelerationAt(positionNextX, positionNextY, influences)
        return TracerXyState(
            x = positionNextX,
            y = positionNextY,
            vx = velocityHalfX + (acceleration1.first * (0.5 * stepSeconds)),
            vy = velocityHalfY + (acceleration1.second * (0.5 * stepSeconds)),
        )
    }

    private fun accelerationAt(
        x: Double,
        y: Double,
        influences: List<InfluenceBodyXy>,
    ): Pair<Double, Double> {
        var ax = 0.0
        var ay = 0.0
        for (influence in influences) {
            val dx = influence.x - x
            val dy = influence.y - y
            val distanceSquared = maxOf((dx * dx) + (dy * dy) + GPU_TRACER_SOFTENING_SQUARED_M2, 1.0)
            val inverseDistance = 1.0 / sqrt(distanceSquared)
            val inverseDistanceCubed = inverseDistance * inverseDistance * inverseDistance
            val scale =
                PhysicalConstants.GRAVITATIONAL_CONSTANT_M3_PER_KG_S2 * influence.massKg * inverseDistanceCubed
            ax += dx * scale
            ay += dy * scale
        }
        return ax to ay
    }

    private fun rms(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return sqrt(values.sumOf { it * it } / values.size.toDouble())
    }

    private fun maxOrZero(values: List<Double>): Double = values.maxOrNull() ?: 0.0

    private data class TracerXyState(
        val x: Double,
        val y: Double,
        val vx: Double,
        val vy: Double,
    )

    private data class InfluenceBodyXy(
        val x: Double,
        val y: Double,
        val massKg: Double,
    )

    fun toJson(report: PhysicsAccuracyTelemetryReport): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": \"${escapeJson(report.schemaVersion)}\",")
        appendLine("  \"runLabel\": \"${escapeJson(report.runLabel)}\",")
        appendLine("  \"scenarioId\": \"${escapeJson(report.scenarioId)}\",")
        appendLine("  \"scenarioLabel\": \"${escapeJson(report.scenarioLabel)}\",")
        appendLine("  \"stepSeconds\": ${formatDouble(report.stepSeconds)},")
        appendLine("  \"steps\": ${report.steps},")
        appendLine("  \"simulatedSeconds\": ${formatDouble(report.simulatedSeconds)},")
        appendLine("  \"provenance\": {")
        appendLine("    \"timelineMode\": \"${report.provenance.timelineMode.name}\",")
        appendLine("    \"seedEpochJulianDateTdb\": ${formatNullableDouble(report.provenance.seedEpochJulianDateTdb)},")
        appendLine("    \"datasetLabel\": ${formatNullableString(report.provenance.datasetLabel)},")
        appendLine("    \"datasetSource\": ${formatNullableString(report.provenance.datasetSource)},")
        appendLine(
            "    \"hardwareAccelerationProfile\": ${formatHardwareAccelerationProfile(report.provenance.hardwareAccelerationProfile)}",
        )
        appendLine("  },")
        appendLine("  \"metrics\": [")
        report.metrics.forEachIndexed { index, metric ->
            appendLine("    {")
            appendLine("      \"name\": \"${escapeJson(metric.name)}\",")
            appendLine("      \"value\": ${formatDouble(metric.value)},")
            appendLine("      \"unit\": \"${escapeJson(metric.unit)}\",")
            appendLine("      \"description\": \"${escapeJson(metric.description)}\"")
            append("    }")
            if (index < report.metrics.lastIndex) {
                append(",")
            }
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    fun toMarkdown(report: PhysicsAccuracyTelemetryReport): String = buildString {
        appendLine("# Physics Accuracy Telemetry")
        appendLine()
        appendLine("- schema: `${report.schemaVersion}`")
        appendLine("- run_label: `${report.runLabel}`")
        appendLine("- scenario: `${report.scenarioId}` (${report.scenarioLabel})")
        appendLine("- step_seconds: `${formatDouble(report.stepSeconds)}`")
        appendLine("- steps: `${report.steps}`")
        appendLine("- simulated_seconds: `${formatDouble(report.simulatedSeconds)}`")
        appendLine("- timeline_mode: `${report.provenance.timelineMode.name}`")
        appendLine("- seed_epoch_jd_tdb: `${formatNullableDouble(report.provenance.seedEpochJulianDateTdb)}`")
        appendLine("- dataset_label: `${report.provenance.datasetLabel ?: "null"}`")
        appendLine("- dataset_source: `${report.provenance.datasetSource ?: "null"}`")
        report.provenance.hardwareAccelerationProfile?.let { profile ->
            appendLine("- acceleration_target: `${profile.target}`")
            appendLine("- authoritative_solver_backend: `${profile.authoritativeSolverBackend}`")
            appendLine("- simd_path: `${profile.simdPath ?: "null"}`")
            appendLine("- tracer_integration_backend: `${profile.tracerIntegrationBackend}`")
            appendLine("- vulkan_compaction_backend: `${profile.vulkanCompactionBackend ?: "null"}`")
            appendLine("- qnn_backend: `${profile.qnnBackend ?: "null"}`")
        }
        appendLine()
        appendLine("| Metric | Value | Unit | Description |")
        appendLine("| --- | ---: | --- | --- |")
        report.metrics.forEach { metric ->
            appendLine(
                "| ${metric.name} | ${formatDouble(metric.value)} | ${metric.unit} | ${metric.description} |",
            )
        }
    }

    fun writeReportArtifacts(report: PhysicsAccuracyTelemetryReport, jsonOutput: Path, markdownOutput: Path) {
        Files.createDirectories(jsonOutput.parent)
        Files.createDirectories(markdownOutput.parent)
        jsonOutput.writeText(toJson(report))
        markdownOutput.writeText(toMarkdown(report))
    }

    private fun formatDouble(value: Double): String = String.format(Locale.US, "%.12e", value)

    private fun formatNullableDouble(value: Double?): String = value?.let(::formatDouble) ?: "null"

    private fun formatNullableString(value: String?): String = value?.let { "\"${escapeJson(it)}\"" } ?: "null"

    private fun formatHardwareAccelerationProfile(profile: HardwareAccelerationProfile?): String {
        if (profile == null) return "null"
        return buildString {
            append("{")
            append("\"target\": \"${escapeJson(profile.target)}\", ")
            append("\"authoritativeSolverBackend\": \"${escapeJson(profile.authoritativeSolverBackend)}\", ")
            append("\"simdPath\": ${formatNullableString(profile.simdPath)}, ")
            append("\"tracerIntegrationBackend\": \"${escapeJson(profile.tracerIntegrationBackend)}\", ")
            append("\"vulkanCompactionBackend\": ${formatNullableString(profile.vulkanCompactionBackend)}, ")
            append("\"qnnBackend\": ${formatNullableString(profile.qnnBackend)}")
            append("}")
        }
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION: String = "physics-accuracy-telemetry.v2"
        const val DEFAULT_STEP_SECONDS: Double = 6.0 * 3600.0
        const val DEFAULT_STEPS: Int = (PhysicalConstants.JULIAN_YEAR_SECONDS / DEFAULT_STEP_SECONDS).toInt()
        private const val GPU_TRACER_SHORT_HORIZON_STEPS: Int = 1
        private const val GPU_TRACER_MEDIUM_HORIZON_MAX_STEPS: Int = 24
        private const val GPU_TRACER_MEDIUM_MAX_HELIOCENTRIC_DISTANCE_AU: Double = 10.0
        private const val GPU_TRACER_SOFTENING_SQUARED_M2: Double = 1.0
    }
}

object PhysicsAccuracyTelemetryCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val options = parseArgs(args)
        val jsonOutput = Path.of(options.getValue("--json-output"))
        val markdownOutput = Path.of(options.getValue("--markdown-output"))
        val runLabel = options["--run-label"] ?: "validation-lab-physics-accuracy"
        val stepSeconds = options["--step-seconds"]?.toDouble() ?: PhysicsAccuracyTelemetryReportGenerator.DEFAULT_STEP_SECONDS
        val steps = options["--steps"]?.toInt() ?: PhysicsAccuracyTelemetryReportGenerator.DEFAULT_STEPS

        val generator = PhysicsAccuracyTelemetryReportGenerator()
        val report = generator.generate(
            PhysicsAccuracyTelemetryReportGenerator.GenerationConfig(
                runLabel = runLabel,
                stepSeconds = stepSeconds,
                steps = steps,
            ),
        )
        generator.writeReportArtifacts(
            report = report,
            jsonOutput = jsonOutput,
            markdownOutput = markdownOutput,
        )
        println("Wrote physics accuracy telemetry artifacts:")
        println("- JSON: ${jsonOutput.toAbsolutePath()}")
        println("- Markdown: ${markdownOutput.toAbsolutePath()}")
    }

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index]
            require(index + 1 < args.size) { "Missing value for argument '$key'" }
            val value = args[index + 1]
            parsed[key] = value
            index += 2
        }
        require("--json-output" in parsed) { "Missing required argument --json-output <path>" }
        require("--markdown-output" in parsed) { "Missing required argument --markdown-output <path>" }
        return parsed
    }
}
