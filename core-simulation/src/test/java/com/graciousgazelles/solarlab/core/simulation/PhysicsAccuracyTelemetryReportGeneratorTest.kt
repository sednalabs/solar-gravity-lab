package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.PhysicsAccuracyTelemetryMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PhysicsAccuracyTelemetryReportGeneratorTest {

    companion object {
        // Baseline evidence from merged-main validation-lab run 23972131559:
        // relative_angular_momentum_drift = 4.877054098182e-9
        // barycenter_drift_m = 5.338326145015
        // barycenter_fine_baseline_distance_error_m = 2.709861458553
        // moon_earth_distance_fine_baseline_error_ratio = 3.108167239133e-4
        // Thresholds are rounded up with deliberate headroom to avoid overfitting while still
        // catching meaningful regressions in this telemetry-only seam.
        private const val RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX = 1e-6
        private const val BARYCENTER_DRIFT_M_MAX = 50.0
        private const val BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX = 10.0
        private const val MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX = 1e-3
    }

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
        assertTrue(json.contains("\"schemaVersion\": \"physics-accuracy-telemetry.v2\""))
        assertTrue(json.contains("\"scenarioId\": \"major-bodies-with-starter-moons\""))
        assertTrue(json.contains("\"hardwareAccelerationProfile\": {"))
        assertTrue(json.contains("\"authoritativeSolverBackend\": \"kotlin-reference\""))
        assertTrue(json.contains("\"tracerIntegrationBackend\": \"cpu-direct\""))
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
        assertEquals("host-jvm", first.provenance.hardwareAccelerationProfile?.target)
        assertEquals("kotlin-reference", first.provenance.hardwareAccelerationProfile?.authoritativeSolverBackend)
        assertEquals("cpu-direct", first.provenance.hardwareAccelerationProfile?.tracerIntegrationBackend)

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
    fun `key physics telemetry metrics stay within conservative regression thresholds`() {
        val generator = PhysicsAccuracyTelemetryReportGenerator()
        val report = generator.generate(
            PhysicsAccuracyTelemetryReportGenerator.GenerationConfig(
                runLabel = "threshold-guardrail",
                stepSeconds = 3600.0,
                steps = 6,
            ),
        )

        val metrics = report.metrics.associateBy { it.name }
        val relativeAngularMomentumDrift = requiredMetricValue(metrics, "relative_angular_momentum_drift")
        val barycenterDriftM = requiredMetricValue(metrics, "barycenter_drift_m")
        val barycenterFineBaselineDistanceErrorM =
            requiredMetricValue(metrics, "barycenter_fine_baseline_distance_error_m")
        val moonEarthFineBaselineErrorRatio =
            requiredMetricValue(metrics, "moon_earth_distance_fine_baseline_error_ratio")

        assertTrue(
            "relative_angular_momentum_drift=$relativeAngularMomentumDrift exceeded max $RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX",
            relativeAngularMomentumDrift <= RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX,
        )
        assertTrue(
            "barycenter_drift_m=$barycenterDriftM exceeded max $BARYCENTER_DRIFT_M_MAX",
            barycenterDriftM <= BARYCENTER_DRIFT_M_MAX,
        )
        assertTrue(
            "barycenter_fine_baseline_distance_error_m=$barycenterFineBaselineDistanceErrorM exceeded max $BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX",
            barycenterFineBaselineDistanceErrorM <= BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX,
        )
        assertTrue(
            "moon_earth_distance_fine_baseline_error_ratio=$moonEarthFineBaselineErrorRatio exceeded max $MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX",
            moonEarthFineBaselineErrorRatio <= MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX,
        )
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
        assertTrue(Files.readString(jsonPath).contains("\"hardwareAccelerationProfile\": {"))
        assertTrue(Files.readString(markdownPath).contains("- authoritative_solver_backend: `kotlin-reference`"))
        assertTrue(Files.readString(markdownPath).contains("# Physics Accuracy Telemetry"))
    }

    private fun requiredMetricValue(
        metrics: Map<String, PhysicsAccuracyTelemetryMetric>,
        metricName: String,
    ): Double {
        val metric = metrics[metricName]
        assertTrue("Expected metric '$metricName' to be present", metric != null)
        return metric!!.value
    }
}
