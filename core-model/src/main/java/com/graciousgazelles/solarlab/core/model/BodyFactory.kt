package com.graciousgazelles.solarlab.core.model

import com.graciousgazelles.solarlab.core.math.Vector3d
import kotlin.math.PI
import kotlin.math.pow

object BodyFactory {

    fun sphericalBody(
        id: String,
        name: String,
        category: BodyCategory,
        gravitationalRole: GravitationalRole,
        massKg: Double,
        densityKgPerM3: Double,
        positionM: Vector3d,
        velocityMps: Vector3d,
        colorArgb: Int,
    ): BodyState {
        require(densityKgPerM3 > 0.0) { "densityKgPerM3 must be > 0" }
        val radiusM = radiusFromMassAndDensity(massKg = massKg, densityKgPerM3 = densityKgPerM3)
        return BodyState(
            id = id,
            name = name,
            category = category,
            gravitationalRole = gravitationalRole,
            massKg = massKg,
            radiusM = radiusM,
            densityKgPerM3 = densityKgPerM3,
            positionM = positionM,
            velocityMps = velocityMps,
            colorArgb = colorArgb,
        )
    }

    fun radiusFromMassAndDensity(
        massKg: Double,
        densityKgPerM3: Double,
    ): Double {
        if (massKg == 0.0) return 0.0
        return ((3.0 * massKg) / (4.0 * PI * densityKgPerM3)).pow(1.0 / 3.0)
    }

    fun densityFromMassAndRadius(
        massKg: Double,
        radiusM: Double,
    ): Double {
        if (massKg == 0.0 || radiusM == 0.0) return 0.0
        val volumeM3 = (4.0 / 3.0) * PI * radiusM * radiusM * radiusM
        return massKg / volumeM3
    }
}
