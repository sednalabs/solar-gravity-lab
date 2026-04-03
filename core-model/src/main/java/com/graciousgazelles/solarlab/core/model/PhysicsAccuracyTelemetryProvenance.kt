package com.graciousgazelles.solarlab.core.model

data class PhysicsAccuracyTelemetryProvenance(
    val timelineMode: TimelineMode,
    val seedEpochJulianDateTdb: Double?,
    val datasetLabel: String?,
    val datasetSource: String?,
)
