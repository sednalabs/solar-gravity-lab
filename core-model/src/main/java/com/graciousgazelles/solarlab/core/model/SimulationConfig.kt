package com.graciousgazelles.solarlab.core.model

data class SimulationConfig(
    val gravitationalConstant: Double = PhysicalConstants.GRAVITATIONAL_CONSTANT_M3_PER_KG_S2,
    val softeningLengthM: Double = 0.0,
    val collisionMode: CollisionMode = CollisionMode.MERGE,
    val includeTracerMutualGravity: Boolean = false,
)
