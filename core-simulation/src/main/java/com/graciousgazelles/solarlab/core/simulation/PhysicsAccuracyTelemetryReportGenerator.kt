package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.PhysicsAccuracyTelemetryMetric
import com.graciousgazelles.solarlab.core.model.PhysicsAccuracyTelemetryProvenance
import com.graciousgazelles.solarlab.core.model.PhysicsAccuracyTelemetryReport
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.min

class PhysicsAccuracyTelemetryReportGenerator(
    private val scenarioFactory: () -> com.graciousgazelles.solarlab.core.model.SimulationSnapshot = {
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
        repeat(config.steps) {
            engine.step(config.stepSeconds)
        }

        val finalSnapshot = engine.snapshot()
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
            },
        )
    }

    private fun runSimulationUntilDuration(
        initialSnapshot: com.graciousgazelles.solarlab.core.model.SimulationSnapshot,
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
        val snapshot: com.graciousgazelles.solarlab.core.model.SimulationSnapshot,
        val diagnostics: SystemDiagnostics,
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
        appendLine("    \"datasetSource\": ${formatNullableString(report.provenance.datasetSource)}")
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
        const val SCHEMA_VERSION: String = "physics-accuracy-telemetry.v1"
        const val DEFAULT_STEP_SECONDS: Double = 6.0 * 3600.0
        const val DEFAULT_STEPS: Int = (PhysicalConstants.JULIAN_YEAR_SECONDS / DEFAULT_STEP_SECONDS).toInt()
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
