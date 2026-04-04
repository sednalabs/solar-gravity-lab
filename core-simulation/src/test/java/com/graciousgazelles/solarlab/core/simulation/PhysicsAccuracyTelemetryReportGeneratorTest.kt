package com.graciousgazelles.solarlab.core.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PhysicsAccuracyTelemetryReportGeneratorTest {

    @Test
    fun `generator is deterministic for fixed config`() {
        val generator = PhysicsAccuracyTelemetryReportGenerator()
        val config = PhysicsAccuracyTelemetryReportGenerator.GenerationConfig(
            runLabel = "determinism-test",
            stepSeconds = 3600.0,
            steps = 24,
        )

        val first = generator.generate(config)
        val second = generator.generate(config)

        assertEquals(first, second)
        assertEquals(generator.toJson(first), generator.toJson(second))
        assertEquals(generator.toMarkdown(first), generator.toMarkdown(second))
    }

    @Test
    fun `json report includes expected deterministic keys`() {
        val generator = PhysicsAccuracyTelemetryReportGenerator()
        val report = generator.generate(
            PhysicsAccuracyTelemetryReportGenerator.GenerationConfig(
                runLabel = "json-shape-test",
                stepSeconds = 3600.0,
                steps = 6,
            ),
        )

        val json = generator.toJson(report)
        assertTrue(json.contains("\"schemaVersion\": \"physics-accuracy-telemetry.v1\""))
        assertTrue(json.contains("\"scenarioId\": \"major-bodies-with-starter-moons\""))
        assertTrue(json.contains("\"metrics\": ["))
        assertTrue(json.contains("\"relative_energy_drift\""))
        assertTrue(json.contains("\"relative_angular_momentum_drift\""))
        assertTrue(json.contains("\"barycenter_drift_m\""))
        assertTrue(json.contains("\"moon_earth_distance_au\""))
        assertTrue(json.contains("\"moon_earth_distance_fine_baseline_error_au\""))
    }

    @Test
    fun `moon-host metrics are emitted for default telemetry scenario`() {
        val generator = PhysicsAccuracyTelemetryReportGenerator()
        val config = PhysicsAccuracyTelemetryReportGenerator.GenerationConfig(
            runLabel = "moon-host-metrics",
            stepSeconds = 3600.0,
            steps = 6,
        )

        val first = generator.generate(config)
        val second = generator.generate(config)
        assertEquals(first, second)
        assertEquals(generator.toJson(first), generator.toJson(second))

        val metrics = first.metrics.associateBy { it.name }
        assertTrue(metrics.containsKey("moon_earth_distance_au"))
        assertTrue(metrics.containsKey("moon_earth_distance_change_au"))
        assertTrue(metrics.containsKey("moon_earth_distance_change_ratio"))
        assertTrue(metrics.containsKey("moon_earth_distance_fine_baseline_error_au"))
        assertTrue(metrics.containsKey("moon_earth_distance_fine_baseline_error_ratio"))
        assertTrue(metrics["moon_earth_distance_au"]!!.value > 0.0)
        assertTrue(metrics["moon_earth_distance_fine_baseline_error_au"]!!.value >= 0.0)
        assertTrue(metrics["moon_earth_distance_fine_baseline_error_ratio"]!!.value >= 0.0)
        assertTrue(metrics.containsKey("relative_angular_momentum_drift"))
        assertTrue(metrics.containsKey("absolute_angular_momentum_drift_kg_m2_per_s"))
        assertTrue(metrics.containsKey("barycenter_drift_m"))
        assertTrue(metrics.containsKey("barycenter_velocity_drift_mps"))
        assertTrue(metrics.containsKey("angular_momentum_fine_baseline_error_ratio"))
        assertTrue(metrics.containsKey("barycenter_fine_baseline_distance_error_m"))
        assertTrue(metrics.containsKey("barycenter_fine_baseline_velocity_error_mps"))
        assertTrue(metrics["relative_angular_momentum_drift"]!!.value >= 0.0)
        assertTrue(metrics["absolute_angular_momentum_drift_kg_m2_per_s"]!!.value >= 0.0)
        assertTrue(metrics["barycenter_drift_m"]!!.value >= 0.0)
        assertTrue(metrics["barycenter_velocity_drift_mps"]!!.value >= 0.0)
        assertTrue(metrics["angular_momentum_fine_baseline_error_ratio"]!!.value >= 0.0)
        assertTrue(metrics["barycenter_fine_baseline_distance_error_m"]!!.value >= 0.0)
        assertTrue(metrics["barycenter_fine_baseline_velocity_error_mps"]!!.value >= 0.0)
    }

    @Test
    fun `fine baseline metrics still emit for sub-hour coarse steps`() {
        val generator = PhysicsAccuracyTelemetryReportGenerator()
        val report = generator.generate(
            PhysicsAccuracyTelemetryReportGenerator.GenerationConfig(
                runLabel = "sub-hour-baseline",
                stepSeconds = 900.0,
                steps = 8,
            ),
        )

        val metrics = report.metrics.associateBy { it.name }
        assertTrue(metrics.containsKey("moon_earth_distance_fine_baseline_error_au"))
        assertTrue(metrics.containsKey("moon_earth_distance_fine_baseline_error_ratio"))
    }

    @Test
    fun `cli writes json and markdown artifacts`() {
        val root = Files.createTempDirectory("physics-accuracy-cli-test")
        val jsonPath = root.resolve("report.json")
        val markdownPath = root.resolve("report.md")

        PhysicsAccuracyTelemetryCli.main(
            arrayOf(
                "--json-output",
                jsonPath.toString(),
                "--markdown-output",
                markdownPath.toString(),
                "--run-label",
                "cli-test",
                "--step-seconds",
                "3600",
                "--steps",
                "4",
            ),
        )

        assertTrue(Files.exists(jsonPath))
        assertTrue(Files.exists(markdownPath))
        assertTrue(Files.readString(jsonPath).contains("\"runLabel\": \"cli-test\""))
        assertTrue(Files.readString(markdownPath).contains("# Physics Accuracy Telemetry"))
    }
}
