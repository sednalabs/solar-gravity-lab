package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.simulation.CollisionEvent
import com.graciousgazelles.solarlab.core.simulation.SystemDiagnostics

data class LabFrame(
    val snapshot: SimulationSnapshot,
    val diagnostics: SystemDiagnostics,
    val collisions: List<CollisionEvent>,
    val timeline: TimelineStatus,
)
