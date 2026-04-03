package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.SimulationSnapshot

data class SimulationStepResult(
    val snapshot: SimulationSnapshot,
    val diagnostics: SystemDiagnostics,
    val collisions: List<CollisionEvent>,
)
