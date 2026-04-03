package com.graciousgazelles.solarlab.core.model

import com.graciousgazelles.solarlab.core.math.Vector3d

data class BodyState(
    val id: String,
    val name: String,
    val category: BodyCategory,
    val gravitationalRole: GravitationalRole,
    val massKg: Double,
    val radiusM: Double,
    val densityKgPerM3: Double,
    val positionM: Vector3d,
    val velocityMps: Vector3d,
    val colorArgb: Int,
    val hostBodyId: String? = null,
) {
    init {
        require(massKg >= 0.0) { "massKg must be >= 0" }
        require(radiusM >= 0.0) { "radiusM must be >= 0" }
        require(densityKgPerM3 >= 0.0) { "densityKgPerM3 must be >= 0" }
    }

    val sourceMassKg: Double
        get() = if (gravitationalRole == GravitationalRole.MASSIVE) massKg else 0.0

    val inertialMassKg: Double
        get() = massKg
}
