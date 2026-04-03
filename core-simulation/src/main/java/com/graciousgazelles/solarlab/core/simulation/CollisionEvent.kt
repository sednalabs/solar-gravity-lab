package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.CollisionMode

data class CollisionEvent(
    val collisionMode: CollisionMode,
    val primaryBodyId: String,
    val secondaryBodyId: String,
    val resultBodyIds: List<String>,
    val resultLabel: String,
    val impactTimeOffsetSeconds: Double,
)
