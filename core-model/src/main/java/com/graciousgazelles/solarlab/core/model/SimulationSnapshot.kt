package com.graciousgazelles.solarlab.core.model

data class SimulationSnapshot(
    val epochSeconds: Double,
    val bodies: List<BodyState>,
    val referenceEpochJdTdb: Double? = null,
    val timelineMode: TimelineMode = TimelineMode.SANDBOX_BRANCH,
    val provenanceLabel: String? = null,
    val provenanceSource: String? = null,
) {
    fun absoluteJulianDateTdbOrNull(): Double? =
        referenceEpochJdTdb?.let { it + (epochSeconds / PhysicalConstants.DAY_SECONDS) }

    val isCatalogBacked: Boolean
        get() = timelineMode == TimelineMode.CATALOG && referenceEpochJdTdb != null
}
