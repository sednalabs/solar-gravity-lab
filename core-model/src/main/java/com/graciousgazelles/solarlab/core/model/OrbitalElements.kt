package com.graciousgazelles.solarlab.core.model

data class OrbitalElements(
    val semiMajorAxisM: Double,
    val eccentricity: Double,
    val inclinationRad: Double,
    val longitudeOfAscendingNodeRad: Double,
    val argumentOfPeriapsisRad: Double,
    val trueAnomalyRad: Double,
) {
    init {
        require(semiMajorAxisM > 0.0) { "semiMajorAxisM must be > 0" }
        require(eccentricity in 0.0..<1.0) { "eccentricity must be in [0, 1)" }
    }
}
