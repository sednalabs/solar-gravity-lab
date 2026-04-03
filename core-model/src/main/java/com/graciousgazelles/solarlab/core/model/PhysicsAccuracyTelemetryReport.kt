package com.graciousgazelles.solarlab.core.model

data class PhysicsAccuracyTelemetryReport(
    val schemaVersion: String,
    val runLabel: String,
    val scenarioId: String,
    val scenarioLabel: String,
    val stepSeconds: Double,
    val steps: Int,
    val simulatedSeconds: Double,
    val provenance: PhysicsAccuracyTelemetryProvenance,
    val metrics: List<PhysicsAccuracyTelemetryMetric>,
)
