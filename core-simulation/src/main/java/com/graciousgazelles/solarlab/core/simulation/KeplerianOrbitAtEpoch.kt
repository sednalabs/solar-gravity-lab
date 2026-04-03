package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.degToRad
import com.graciousgazelles.solarlab.core.model.PhysicalConstants

data class KeplerianOrbitAtEpoch(
    val epochJdTdb: Double = JplApproximateSeedCatalog.DEFAULT_SEED_JULIAN_DATE_TDB,
    val semiMajorAxisM: Double,
    val eccentricity: Double,
    val inclinationRad: Double,
    val longitudeOfAscendingNodeRad: Double,
    val argumentOfPeriapsisRad: Double,
    val meanAnomalyAtEpochRad: Double,
) {
    companion object {
        fun fromAstronomicalUnits(
            epochJdTdb: Double = JplApproximateSeedCatalog.DEFAULT_SEED_JULIAN_DATE_TDB,
            semiMajorAxisAu: Double,
            eccentricity: Double,
            inclinationDeg: Double,
            ascendingNodeDeg: Double,
            periapsisDeg: Double,
            meanAnomalyDeg: Double,
        ): KeplerianOrbitAtEpoch = KeplerianOrbitAtEpoch(
            epochJdTdb = epochJdTdb,
            semiMajorAxisM = semiMajorAxisAu * PhysicalConstants.ASTRONOMICAL_UNIT_M,
            eccentricity = eccentricity,
            inclinationRad = inclinationDeg.degToRad(),
            longitudeOfAscendingNodeRad = ascendingNodeDeg.degToRad(),
            argumentOfPeriapsisRad = periapsisDeg.degToRad(),
            meanAnomalyAtEpochRad = meanAnomalyDeg.degToRad(),
        )

        fun fromKilometers(
            epochJdTdb: Double = JplApproximateSeedCatalog.DEFAULT_SEED_JULIAN_DATE_TDB,
            semiMajorAxisKm: Double,
            eccentricity: Double,
            inclinationDeg: Double,
            ascendingNodeDeg: Double,
            periapsisDeg: Double,
            meanAnomalyDeg: Double,
        ): KeplerianOrbitAtEpoch = KeplerianOrbitAtEpoch(
            epochJdTdb = epochJdTdb,
            semiMajorAxisM = semiMajorAxisKm * 1_000.0,
            eccentricity = eccentricity,
            inclinationRad = inclinationDeg.degToRad(),
            longitudeOfAscendingNodeRad = ascendingNodeDeg.degToRad(),
            argumentOfPeriapsisRad = periapsisDeg.degToRad(),
            meanAnomalyAtEpochRad = meanAnomalyDeg.degToRad(),
        )
    }
}
