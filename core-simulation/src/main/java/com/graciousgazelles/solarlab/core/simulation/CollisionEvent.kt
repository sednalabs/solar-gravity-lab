package com.graciousgazelles.solarlab.core.simulation

data class CollisionEvent(
    val primaryBodyId: String,
    val secondaryBodyId: String,
    val mergedBodyId: String,
    val mergedBodyName: String,
)
