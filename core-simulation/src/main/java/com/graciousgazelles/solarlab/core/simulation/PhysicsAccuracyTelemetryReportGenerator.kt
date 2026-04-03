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

class PhysicsAccuracyTelemetryReportGenerator(
    private val scenarioFactory: () -> com.graciousgazelles.solarlab.core.model.SimulationSnapshot = {
        SolarSystemScenarios.majorBodiesWithDwarfs(
            config = SimulationConfig(collisionMode = CollisionMode.NONE),
        )
    },
) {

    data class GenerationConfig(
        val runLabel: String = "validation-lab-physics-accuracy",
        val scenarioId: String = "major-bodies-with-dwarfs",
        val scenarioLabel: String = "Solar major bodies + dwarfs baseline",
        val stepSeconds: Double = DEFAULT_STEP_SECONDS,
        val steps: Int = DEFAULT_STEPS,
    )

    fun generate(config: GenerationConfig = GenerationConfig()): PhysicsAccuracyTelemetryReport {
        require(config.stepSeconds > 0.0) { "stepSeconds must be > 0" }
        require(config.steps > 0) { "steps must be > 0" }

        val snapshot = scenarioFactory()
        val engine = SimulationEngine(
            initialSnapshot = snapshot,
            config = SimulationConfig(collisionMode = CollisionMode.NONE),
        )

        val startingDiagnostics = engine.diagnostics()
        repeat(config.steps) {
            engine.step(config.stepSeconds)
        }

        val finalSnapshot = engine.snapshot()
        val finalDiagnostics = engine.diagnostics()
        val earth = finalSnapshot.bodies.firstOrNull { it.id == "earth" }
            ?: error("Scenario '${config.scenarioId}' did not include earth body")

        val earthDistanceAu = earth.positionM.magnitude() / PhysicalConstants.ASTRONOMICAL_UNIT_M
        val energyDeltaJ = finalDiagnostics.totalEnergyJ - startingDiagnostics.totalEnergyJ
        val relativeEnergyDrift = if (startingDiagnostics.totalEnergyJ == 0.0) {
            0.0
        } else {
            energyDeltaJ / startingDiagnostics.totalEnergyJ
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
            metrics = listOf(
                PhysicsAccuracyTelemetryMetric(
                    name = "relative_energy_drift",
                    value = relativeEnergyDrift,
                    unit = "ratio",
                    description = "Final total energy drift ratio against initial total energy",
                ),
                PhysicsAccuracyTelemetryMetric(
                    name = "absolute_energy_drift_joules",
                    value = energyDeltaJ,
                    unit = "joules",
                    description = "Final total energy minus initial total energy",
                ),
                PhysicsAccuracyTelemetryMetric(
                    name = "earth_distance_au",
                    value = earthDistanceAu,
                    unit = "au",
                    description = "Earth heliocentric distance in astronomical units at final step",
                ),
                PhysicsAccuracyTelemetryMetric(
                    name = "earth_distance_error_from_1au",
                    value = abs(earthDistanceAu - 1.0),
                    unit = "au",
                    description = "Absolute deviation from 1 astronomical unit at final step",
                ),
                PhysicsAccuracyTelemetryMetric(
                    name = "massive_body_count",
                    value = finalDiagnostics.massiveBodyCount.toDouble(),
                    unit = "count",
                    description = "Massive body count in the simulated snapshot",
                ),
                PhysicsAccuracyTelemetryMetric(
                    name = "tracer_body_count",
                    value = finalDiagnostics.tracerBodyCount.toDouble(),
                    unit = "count",
                    description = "Tracer body count in the simulated snapshot",
                ),
            ),
        )
    }

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
