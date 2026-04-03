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
        assertTrue(json.contains("\"scenarioId\": \"major-bodies-with-dwarfs\""))
        assertTrue(json.contains("\"metrics\": ["))
        assertTrue(json.contains("\"relative_energy_drift\""))
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
