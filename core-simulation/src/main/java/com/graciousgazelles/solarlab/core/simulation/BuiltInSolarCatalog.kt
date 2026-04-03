package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.GravitationalRole

/**
 * Starter in-app catalog meant to be useful immediately while leaving a clean path for external
 * agents to replace or extend it with imported real ephemeris/cross-section data.
 *
 * The bundled definitions below are intentionally approximate. They provide plausible dynamical
 * placement and timeline propagation, but they are not a substitute for NAIF/Horizons-derived
 * packs. The runtime merges imported catalog rows over these starter definitions by body id.
 */
object BuiltInSolarCatalog {

    private const val COLOR_MOON: Int = 0xFFCCCCCC.toInt()
    private const val COLOR_ICY_MOON: Int = 0xFFD8EEFF.toInt()
    private const val COLOR_DARK_MOON: Int = 0xFF9A9A9A.toInt()
    private const val COLOR_ASTEROID: Int = 0xFFBDBDBD.toInt()
    private const val COLOR_COMET: Int = 0xFF9ED0FF.toInt()

    val starterPlanetaryMoons: List<CatalogBodyDefinition> = listOf(
        // Earth
        moon("moon", "Moon", "earth", 7.342e22, 1.7374e6, COLOR_MOON,
            km(384_400.0, 0.0549, 5.145, 125.08, 318.15, 135.27)),

        // Mars
        moon("phobos", "Phobos", "mars", 1.0659e16, 11_266.7, COLOR_DARK_MOON,
            km(9_376.0, 0.0151, 1.08, 49.0, 150.0, 45.0)),
        moon("deimos", "Deimos", "mars", 1.4762e15, 6_200.0, COLOR_DARK_MOON,
            km(23_463.2, 0.0002, 0.93, 79.0, 260.0, 180.0)),

        // Jupiter
        moon("io", "Io", "jupiter", 8.9319e22, 1.8216e6, 0xFFE7C56A.toInt(),
            km(421_700.0, 0.0041, 0.036, 43.98, 84.13, 200.0)),
        moon("europa", "Europa", "jupiter", 4.7998e22, 1.5608e6, COLOR_ICY_MOON,
            km(671_100.0, 0.009, 0.466, 219.1, 88.97, 10.0)),
        moon("ganymede", "Ganymede", "jupiter", 1.4819e23, 2.6341e6, 0xFFBBAE8C.toInt(),
            km(1_070_400.0, 0.0013, 0.177, 63.55, 192.42, 120.0)),
        moon("callisto", "Callisto", "jupiter", 1.0759e23, 2.4103e6, 0xFF8E8E8E.toInt(),
            km(1_882_700.0, 0.0074, 0.192, 298.85, 52.64, 300.0)),
        moon("amalthea", "Amalthea", "jupiter", 2.08e18, 83_500.0, COLOR_DARK_MOON,
            km(181_400.0, 0.003, 0.374, 95.0, 80.0, 40.0), role = GravitationalRole.TRACER),
        moon("himalia", "Himalia", "jupiter", 4.2e18, 69_800.0, COLOR_DARK_MOON,
            km(11_460_000.0, 0.162, 27.5, 44.0, 82.0, 160.0), role = GravitationalRole.TRACER),

        // Saturn
        moon("mimas", "Mimas", "saturn", 3.7493e19, 1.981e5, COLOR_ICY_MOON,
            km(185_540.0, 0.0196, 1.57, 173.0, 270.0, 210.0)),
        moon("enceladus", "Enceladus", "saturn", 1.08022e20, 2.521e5, COLOR_ICY_MOON,
            km(238_040.0, 0.0047, 0.009, 169.0, 300.0, 60.0)),
        moon("tethys", "Tethys", "saturn", 6.17449e20, 5.314e5, COLOR_ICY_MOON,
            km(294_670.0, 0.0001, 1.09, 259.0, 10.0, 240.0)),
        moon("dione", "Dione", "saturn", 1.095452e21, 5.613e5, COLOR_ICY_MOON,
            km(377_400.0, 0.0022, 0.028, 168.0, 310.0, 20.0)),
        moon("rhea", "Rhea", "saturn", 2.306518e21, 7.638e5, COLOR_ICY_MOON,
            km(527_040.0, 0.001, 0.345, 180.0, 20.0, 140.0)),
        moon("titan", "Titan", "saturn", 1.3452e23, 2.5747e6, 0xFFD3B07D.toInt(),
            km(1_221_870.0, 0.0288, 0.348, 28.0, 186.0, 90.0)),
        moon("hyperion", "Hyperion", "saturn", 5.6e18, 1.35e5, 0xFFAA9A7A.toInt(),
            km(1_500_930.0, 0.1042, 0.43, 207.0, 53.0, 250.0), role = GravitationalRole.TRACER),
        moon("iapetus", "Iapetus", "saturn", 1.805635e21, 7.346e5, 0xFFB7B7A1.toInt(),
            km(3_560_820.0, 0.0286, 15.47, 80.0, 85.0, 330.0)),
        moon("phoebe", "Phoebe", "saturn", 8.292e18, 1.066e5, COLOR_DARK_MOON,
            km(12_952_000.0, 0.1634, 175.2, 245.0, 150.0, 140.0), role = GravitationalRole.TRACER),

        // Uranus
        moon("miranda", "Miranda", "uranus", 6.59e19, 2.358e5, COLOR_ICY_MOON,
            km(129_390.0, 0.0013, 4.34, 326.4, 68.3, 110.0)),
        moon("ariel", "Ariel", "uranus", 1.353e21, 5.789e5, COLOR_ICY_MOON,
            km(190_900.0, 0.0012, 0.26, 22.4, 115.0, 20.0)),
        moon("umbriel", "Umbriel", "uranus", 1.172e21, 5.847e5, 0xFFA2A2A2.toInt(),
            km(266_000.0, 0.0039, 0.13, 33.5, 84.7, 230.0)),
        moon("titania", "Titania", "uranus", 3.527e21, 7.889e5, COLOR_ICY_MOON,
            km(435_910.0, 0.0011, 0.34, 99.8, 284.4, 45.0)),
        moon("oberon", "Oberon", "uranus", 3.014e21, 7.614e5, COLOR_ICY_MOON,
            km(583_520.0, 0.0014, 0.07, 279.8, 104.4, 180.0)),
        moon("puck", "Puck", "uranus", 2.9e18, 8.1e4, COLOR_DARK_MOON,
            km(86_010.0, 0.0001, 0.32, 0.0, 0.0, 300.0), role = GravitationalRole.TRACER),

        // Neptune
        moon("naiad", "Naiad", "neptune", 1.9e17, 3.3e4, COLOR_DARK_MOON,
            km(48_227.0, 0.0003, 4.7, 0.0, 0.0, 10.0), role = GravitationalRole.TRACER),
        moon("thalassa", "Thalassa", "neptune", 3.5e17, 4.0e4, COLOR_DARK_MOON,
            km(50_074.0, 0.0002, 0.2, 0.0, 0.0, 50.0), role = GravitationalRole.TRACER),
        moon("despina", "Despina", "neptune", 2.1e18, 7.4e4, COLOR_DARK_MOON,
            km(52_526.0, 0.0001, 0.1, 0.0, 0.0, 120.0), role = GravitationalRole.TRACER),
        moon("galatea", "Galatea", "neptune", 2.12e18, 8.8e4, COLOR_DARK_MOON,
            km(61_953.0, 0.0002, 0.1, 0.0, 0.0, 200.0), role = GravitationalRole.TRACER),
        moon("larissa", "Larissa", "neptune", 4.2e18, 9.7e4, COLOR_DARK_MOON,
            km(73_548.0, 0.0014, 0.2, 0.0, 0.0, 260.0), role = GravitationalRole.TRACER),
        moon("hippocamp", "Hippocamp", "neptune", 2.0e17, 1.7e4, COLOR_DARK_MOON,
            km(105_283.0, 0.0003, 0.0, 0.0, 0.0, 90.0), role = GravitationalRole.TRACER),
        moon("proteus", "Proteus", "neptune", 4.4e19, 2.10e5, COLOR_DARK_MOON,
            km(117_647.0, 0.0005, 0.52, 0.0, 0.0, 300.0)),
        moon("triton", "Triton", "neptune", 2.139e22, 1.3534e6, COLOR_ICY_MOON,
            km(354_759.0, 0.0, 156.865, 177.6, 112.8, 45.0)),
        moon("nereid", "Nereid", "neptune", 3.1e19, 1.70e5, COLOR_DARK_MOON,
            km(5_513_400.0, 0.7507, 7.23, 320.0, 250.0, 120.0), role = GravitationalRole.TRACER),

        // Pluto system
        moon("charon", "Charon", "pluto", 1.586e21, 6.06e5, COLOR_ICY_MOON,
            km(19_591.0, 0.0, 112.8, 223.0, 0.0, 180.0)),
        moon("styx", "Styx", "pluto", 7.5e15, 8_000.0, COLOR_DARK_MOON,
            km(42_656.0, 0.005, 112.8, 223.0, 0.0, 20.0), role = GravitationalRole.TRACER),
        moon("nix", "Nix", "pluto", 4.5e16, 2.0e4, COLOR_DARK_MOON,
            km(48_694.0, 0.003, 112.8, 223.0, 0.0, 100.0), role = GravitationalRole.TRACER),
        moon("kerberos", "Kerberos", "pluto", 1.6e16, 1.3e4, COLOR_DARK_MOON,
            km(57_783.0, 0.004, 112.8, 223.0, 0.0, 220.0), role = GravitationalRole.TRACER),
        moon("hydra", "Hydra", "pluto", 4.8e16, 2.6e4, COLOR_DARK_MOON,
            km(64_738.0, 0.005, 112.8, 223.0, 0.0, 310.0), role = GravitationalRole.TRACER),
    )

    val curatedSmallBodies: List<CatalogBodyDefinition> = listOf(
        asteroid("vesta", "Vesta", 2.59076e20, 2.626e5,
            au(2.361, 0.089, 7.14, 103.8, 150.9, 40.0), role = GravitationalRole.MASSIVE),
        asteroid("pallas", "Pallas", 2.14e20, 2.56e5,
            au(2.773, 0.231, 34.84, 173.1, 310.2, 220.0), role = GravitationalRole.MASSIVE),
        asteroid("hygiea", "Hygiea", 8.32e19, 2.17e5,
            au(3.141, 0.117, 3.83, 283.2, 313.4, 120.0), role = GravitationalRole.MASSIVE),
        asteroid("psyche", "Psyche", 2.3e19, 1.13e5,
            au(2.923, 0.140, 3.10, 150.0, 228.0, 280.0)),
        asteroid("eros", "Eros", 6.687e15, 8_420.0,
            au(1.458, 0.223, 10.83, 304.4, 178.7, 60.0)),
        asteroid("bennu", "Bennu", 7.329e10, 245.0,
            au(1.1264, 0.2037, 6.03, 2.06, 66.22, 300.0)),
        asteroid("ryugu", "Ryugu", 4.5e11, 448.0,
            au(1.1896, 0.1902, 5.88, 251.45, 211.61, 170.0)),
        asteroid("itokawa", "Itokawa", 3.51e10, 165.0,
            au(1.324, 0.280, 1.62, 69.1, 162.8, 25.0)),
        asteroid("apophis", "Apophis", 6.1e10, 185.0,
            au(0.9224, 0.1912, 3.34, 204.4, 126.4, 320.0)),
        asteroid("didymos", "Didymos", 5.24e11, 390.0,
            au(1.644, 0.384, 3.41, 73.2, 319.6, 80.0)),
        comet("halley", "1P/Halley", 2.2e14, 5_500.0,
            au(17.834, 0.967, 162.26, 58.42, 111.33, 38.0)),
        comet("encke", "2P/Encke", 3.5e13, 2_400.0,
            au(2.215, 0.850, 11.78, 334.6, 186.5, 140.0)),
        comet("churyumov-gerasimenko", "67P/Churyumov-Gerasimenko", 9.98e12, 2_000.0,
            au(3.463, 0.641, 7.04, 50.17, 12.78, 90.0)),
        comet("wild-2", "81P/Wild 2", 2.3e13, 2_000.0,
            au(3.447, 0.538, 3.24, 136.1, 41.0, 260.0)),
    )

    fun mergedDefinitions(
        importedPlanetaryMoons: List<CatalogBodyDefinition> = emptyList(),
        importedSmallBodies: List<CatalogBodyDefinition> = emptyList(),
    ): List<CatalogBodyDefinition> {
        val merged = linkedMapOf<String, CatalogBodyDefinition>()
        starterPlanetaryMoons.forEach { merged[it.id] = it }
        curatedSmallBodies.forEach { merged[it.id] = it }
        importedPlanetaryMoons.forEach { merged[it.id] = it }
        importedSmallBodies.forEach { merged[it.id] = it }
        return merged.values.filter { it.enabledByDefault }
    }

    private fun moon(
        id: String,
        name: String,
        hostId: String,
        massKg: Double,
        radiusM: Double,
        colorArgb: Int,
        orbit: KeplerianOrbitAtEpoch,
        role: GravitationalRole = GravitationalRole.MASSIVE,
    ): CatalogBodyDefinition = CatalogBodyDefinition(
        id = id,
        name = name,
        category = BodyCategory.MOON,
        gravitationalRole = role,
        hostBodyId = hostId,
        massKg = massKg,
        radiusM = radiusM,
        colorArgb = colorArgb,
        orbit = orbit,
    )

    private fun asteroid(
        id: String,
        name: String,
        massKg: Double,
        radiusM: Double,
        orbit: KeplerianOrbitAtEpoch,
        role: GravitationalRole = GravitationalRole.TRACER,
    ): CatalogBodyDefinition = CatalogBodyDefinition(
        id = id,
        name = name,
        category = BodyCategory.ASTEROID,
        gravitationalRole = role,
        hostBodyId = "sun",
        massKg = massKg,
        radiusM = radiusM,
        colorArgb = COLOR_ASTEROID,
        orbit = orbit,
    )

    private fun comet(
        id: String,
        name: String,
        massKg: Double,
        radiusM: Double,
        orbit: KeplerianOrbitAtEpoch,
        role: GravitationalRole = GravitationalRole.TRACER,
    ): CatalogBodyDefinition = CatalogBodyDefinition(
        id = id,
        name = name,
        category = BodyCategory.COMET,
        gravitationalRole = role,
        hostBodyId = "sun",
        massKg = massKg,
        radiusM = radiusM,
        colorArgb = COLOR_COMET,
        orbit = orbit,
    )

    private fun km(
        semiMajorAxisKm: Double,
        eccentricity: Double,
        inclinationDeg: Double,
        ascendingNodeDeg: Double,
        periapsisDeg: Double,
        meanAnomalyDeg: Double,
    ): KeplerianOrbitAtEpoch = KeplerianOrbitAtEpoch.fromKilometers(
        semiMajorAxisKm = semiMajorAxisKm,
        eccentricity = eccentricity,
        inclinationDeg = inclinationDeg,
        ascendingNodeDeg = ascendingNodeDeg,
        periapsisDeg = periapsisDeg,
        meanAnomalyDeg = meanAnomalyDeg,
    )

    private fun au(
        semiMajorAxisAu: Double,
        eccentricity: Double,
        inclinationDeg: Double,
        ascendingNodeDeg: Double,
        periapsisDeg: Double,
        meanAnomalyDeg: Double,
    ): KeplerianOrbitAtEpoch = KeplerianOrbitAtEpoch.fromAstronomicalUnits(
        semiMajorAxisAu = semiMajorAxisAu,
        eccentricity = eccentricity,
        inclinationDeg = inclinationDeg,
        ascendingNodeDeg = ascendingNodeDeg,
        periapsisDeg = periapsisDeg,
        meanAnomalyDeg = meanAnomalyDeg,
    )
}
