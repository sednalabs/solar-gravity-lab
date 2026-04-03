package com.graciousgazelles.solarlab.core.model

data class SimulationSnapshot(
    val epochSeconds: Double,
    val bodies: List<BodyState>,
)
