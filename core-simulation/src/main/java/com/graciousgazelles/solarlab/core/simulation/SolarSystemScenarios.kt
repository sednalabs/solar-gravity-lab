package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.math.degToRad
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyFactory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.DensityPreset
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.OrbitalElements
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.model.TimelineMode
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object SolarSystemScenarios {

    private const val COLOR_SUN: Int = 0xFFFFD166.toInt()
    private const val COLOR_MERCURY: Int = 0xFFA8A8A8.toInt()
    private const val COLOR_VENUS: Int = 0xFFE6C97F.toInt()
    private const val COLOR_EARTH: Int = 0xFF5DA9FF.toInt()
    private const val COLOR_MARS: Int = 0xFFC95C38.toInt()
    private const val COLOR_JUPITER: Int = 0xFFD1A16A.toInt()
    private const val COLOR_SATURN: Int = 0xFFE7D3A1.toInt()
    private const val COLOR_URANUS: Int = 0xFF9DE6E6.toInt()
    private const val COLOR_NEPTUNE: Int = 0xFF5B7CFF.toInt()
    private const val COLOR_DWARF: Int = 0xFFB7B7D7.toInt()
    private const val COLOR_BELT: Int = 0x77C8C8C8
    private const val COLOR_OORT: Int = 0x55A0C4FF

    fun defaultSeedJulianDateTdb(seedBundle: CartesianSeedBundle? = null): Double =
        seedBundle?.metadata?.epochJdTdb ?: JplApproximateSeedCatalog.DEFAULT_SEED_JULIAN_DATE_TDB

    fun majorBodiesWithDwarfs(
        config: SimulationConfig = SimulationConfig(),
        seedBundle: CartesianSeedBundle? = null,
        julianDateTdb: Double = defaultSeedJulianDateTdb(seedBundle),
    ): SimulationSnapshot {
        val authoritativeBundle = seedBundle?.takeIf { kotlin.math.abs(it.metadata.epochJdTdb - julianDateTdb) <= 1.0e-9 }
        val sun = bodyFromCartesianRecordOrDefault(
            record = authoritativeBundle?.recordFor("sun"),
            id = "sun",
            name = "Sun",
            category = BodyCategory.STAR,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.98847e30,
            radiusM = 6.9634e8,
            colorArgb = COLOR_SUN,
        )

        val bodies = mutableListOf<BodyState>()
        bodies += sun
        bodies += seededFromCatalogOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "mercury",
            name = "Mercury",
            category = BodyCategory.PLANET,
            massKg = 3.3011e23,
            radiusM = 2.4397e6,
            colorArgb = COLOR_MERCURY,
        )
        bodies += seededFromCatalogOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "venus",
            name = "Venus",
            category = BodyCategory.PLANET,
            massKg = 4.8675e24,
            radiusM = 6.0518e6,
            colorArgb = COLOR_VENUS,
        )
        bodies += seededFromCatalogOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "earth",
            name = "Earth",
            category = BodyCategory.PLANET,
            massKg = 5.97237e24,
            radiusM = 6.3710e6,
            colorArgb = COLOR_EARTH,
        )
        bodies += seededFromCatalogOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "mars",
            name = "Mars",
            category = BodyCategory.PLANET,
            massKg = 6.4171e23,
            radiusM = 3.3895e6,
            colorArgb = COLOR_MARS,
        )
        bodies += seededFromCatalogOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "jupiter",
            name = "Jupiter",
            category = BodyCategory.PLANET,
            massKg = 1.8982e27,
            radiusM = 6.9911e7,
            colorArgb = COLOR_JUPITER,
        )
        bodies += seededFromCatalogOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "saturn",
            name = "Saturn",
            category = BodyCategory.PLANET,
            massKg = 5.6834e26,
            radiusM = 5.8232e7,
            colorArgb = COLOR_SATURN,
        )
        bodies += seededFromCatalogOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "uranus",
            name = "Uranus",
            category = BodyCategory.PLANET,
            massKg = 8.6810e25,
            radiusM = 2.5362e7,
            colorArgb = COLOR_URANUS,
        )
        bodies += seededFromCatalogOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "neptune",
            name = "Neptune",
            category = BodyCategory.PLANET,
            massKg = 1.02413e26,
            radiusM = 2.4622e7,
            colorArgb = COLOR_NEPTUNE,
        )
        bodies += seededAroundPrimaryOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "ceres",
            name = "Ceres",
            category = BodyCategory.DWARF_PLANET,
            massKg = 9.3835e20,
            radiusM = 4.731e5,
            colorArgb = COLOR_DWARF,
            fallbackElements = seed(
                semiMajorAxisAu = 2.7675,
                eccentricity = 0.0758,
                inclinationDeg = 10.593,
                ascendingNodeDeg = 80.305,
                periapsisDeg = 73.597,
                trueAnomalyDeg = 330.0,
            ),
        )
        bodies += seededAroundPrimaryOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "pluto",
            name = "Pluto",
            category = BodyCategory.DWARF_PLANET,
            massKg = 1.303e22,
            radiusM = 1.1883e6,
            colorArgb = COLOR_DWARF,
            fallbackElements = seed(
                semiMajorAxisAu = 39.48168677,
                eccentricity = 0.24880766,
                inclinationDeg = 17.14175,
                ascendingNodeDeg = 110.30347,
                periapsisDeg = 113.76329,
                trueAnomalyDeg = 15.0,
            ),
        )
        bodies += seededAroundPrimaryOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "haumea",
            name = "Haumea",
            category = BodyCategory.DWARF_PLANET,
            massKg = 4.006e21,
            radiusM = 7.16e5,
            colorArgb = COLOR_DWARF,
            fallbackElements = seed(
                semiMajorAxisAu = 43.13,
                eccentricity = 0.191,
                inclinationDeg = 28.19,
                ascendingNodeDeg = 122.0,
                periapsisDeg = 240.0,
                trueAnomalyDeg = 80.0,
            ),
        )
        bodies += seededAroundPrimaryOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "makemake",
            name = "Makemake",
            category = BodyCategory.DWARF_PLANET,
            massKg = 3.1e21,
            radiusM = 7.15e5,
            colorArgb = COLOR_DWARF,
            fallbackElements = seed(
                semiMajorAxisAu = 45.79,
                eccentricity = 0.159,
                inclinationDeg = 28.96,
                ascendingNodeDeg = 79.6,
                periapsisDeg = 294.0,
                trueAnomalyDeg = 170.0,
            ),
        )
        bodies += seededAroundPrimaryOrBundle(
            bundle = authoritativeBundle,
            primary = sun,
            config = config,
            julianDateTdb = julianDateTdb,
            id = "eris",
            name = "Eris",
            category = BodyCategory.DWARF_PLANET,
            massKg = 1.6466e22,
            radiusM = 1.163e6,
            colorArgb = COLOR_DWARF,
            fallbackElements = seed(
                semiMajorAxisAu = 67.78,
                eccentricity = 0.44,
                inclinationDeg = 44.04,
                ascendingNodeDeg = 35.95,
                periapsisDeg = 151.4,
                trueAnomalyDeg = 260.0,
            ),
        )

        return SimulationSnapshot(
            epochSeconds = 0.0,
            referenceEpochJdTdb = julianDateTdb,
            timelineMode = TimelineMode.CATALOG,
            provenanceLabel = authoritativeBundle?.metadata?.datasetName ?: "Built-in approximate solar catalog",
            provenanceSource = authoritativeBundle?.metadata?.source ?: "built-in-catalog",
            bodies = OrbitalMechanics.recenterToBarycenter(bodies),
        )
    }

    fun withSyntheticAsteroidBelt(
        base: SimulationSnapshot,
        count: Int,
        config: SimulationConfig = SimulationConfig(),
        seed: Long = 42L,
    ): SimulationSnapshot {
        val random = Random(seed)
        val sun = base.bodies.first { it.id == "sun" }

        val tracers = buildList {
            repeat(count) { index ->
                val semiMajorAxisAu = random.nextDouble(2.1, 3.3)
                val eccentricity = random.nextDouble(0.0, 0.18)
                val inclinationDeg = random.nextDouble(0.0, 18.0)
                val ascendingNodeDeg = random.nextDouble(0.0, 360.0)
                val periapsisDeg = random.nextDouble(0.0, 360.0)
                val trueAnomalyDeg = random.nextDouble(0.0, 360.0)
                val radiusM = random.nextDouble(500.0, 50_000.0)

                add(
                    tracerAroundPrimary(
                        primary = sun,
                        config = config,
                        id = "belt-$index",
                        name = "Belt $index",
                        category = BodyCategory.ASTEROID,
                        radiusM = radiusM,
                        colorArgb = COLOR_BELT,
                        elements = seed(
                            semiMajorAxisAu = semiMajorAxisAu,
                            eccentricity = eccentricity,
                            inclinationDeg = inclinationDeg,
                            ascendingNodeDeg = ascendingNodeDeg,
                            periapsisDeg = periapsisDeg,
                            trueAnomalyDeg = trueAnomalyDeg,
                        ),
                    ),
                )
            }
        }

        return base.copy(bodies = base.bodies + tracers)
    }

    fun withSyntheticOortShell(
        base: SimulationSnapshot,
        count: Int,
        config: SimulationConfig = SimulationConfig(),
        seed: Long = 43L,
    ): SimulationSnapshot {
        val random = Random(seed)
        val sun = base.bodies.first { it.id == "sun" }

        val tracers = buildList {
            repeat(count) { index ->
                val logSemiMajorAxis = random.nextDouble(3.3, 5.0)
                val semiMajorAxisAu = 10.0.pow(logSemiMajorAxis)
                val eccentricity = random.nextDouble(0.85, 0.999)
                val cosI = random.nextDouble(-1.0, 1.0)
                val inclinationDeg = acos(cosI).let { Math.toDegrees(it) }
                val ascendingNodeDeg = random.nextDouble(0.0, 360.0)
                val periapsisDeg = random.nextDouble(0.0, 360.0)
                val trueAnomalyDeg = random.nextDouble(0.0, 360.0)
                val radiusM = random.nextDouble(1_000.0, 20_000.0)

                add(
                    tracerAroundPrimary(
                        primary = sun,
                        config = config,
                        id = "oort-$index",
                        name = "Oort $index",
                        category = BodyCategory.COMET,
                        radiusM = radiusM,
                        colorArgb = COLOR_OORT,
                        elements = seed(
                            semiMajorAxisAu = semiMajorAxisAu,
                            eccentricity = eccentricity,
                            inclinationDeg = inclinationDeg,
                            ascendingNodeDeg = ascendingNodeDeg,
                            periapsisDeg = periapsisDeg,
                            trueAnomalyDeg = trueAnomalyDeg,
                        ),
                    ),
                )
            }
        }

        return base.copy(bodies = base.bodies + tracers)
    }

    fun defaultLabScenario(
        asteroidCount: Int = 240,
        oortCount: Int = 96,
        config: SimulationConfig = SimulationConfig(),
        seedBundle: CartesianSeedBundle? = null,
        julianDateTdb: Double = defaultSeedJulianDateTdb(seedBundle),
        importedCatalogBodies: List<CatalogBodyDefinition> = emptyList(),
        includeStarterPlanetaryMoons: Boolean = true,
        includeCuratedSmallBodies: Boolean = true,
        includeSyntheticAsteroidBelt: Boolean = true,
        includeSyntheticOortCloud: Boolean = true,
    ): SimulationSnapshot {
        var scenario = majorBodiesWithDwarfs(
            config = config,
            seedBundle = seedBundle,
            julianDateTdb = julianDateTdb,
        )

        val importedMoons = importedCatalogBodies.filter { it.category == BodyCategory.MOON }
        val importedSmallBodies = importedCatalogBodies.filter { it.category != BodyCategory.MOON }
        val mergedCatalog = BuiltInSolarCatalog.mergedDefinitions(
            importedPlanetaryMoons = importedMoons,
            importedSmallBodies = importedSmallBodies,
        )

        if (includeStarterPlanetaryMoons || includeCuratedSmallBodies || importedMoons.isNotEmpty() || importedSmallBodies.isNotEmpty()) {
            val selectedCatalog = mergedCatalog.filter { definition ->
                when (definition.category) {
                    BodyCategory.MOON -> includeStarterPlanetaryMoons || importedMoons.any { it.id == definition.id }
                    BodyCategory.ASTEROID, BodyCategory.COMET -> includeCuratedSmallBodies || importedSmallBodies.any { it.id == definition.id }
                    else -> true
                }
            }
            scenario = withCatalogBodies(
                base = scenario,
                definitions = selectedCatalog,
                julianDateTdb = julianDateTdb,
                config = config,
            )
        }

        val withBelt = if (includeSyntheticAsteroidBelt && asteroidCount > 0) {
            withSyntheticAsteroidBelt(scenario, count = asteroidCount, config = config)
        } else {
            scenario
        }
        return if (includeSyntheticOortCloud && oortCount > 0) {
            withSyntheticOortShell(withBelt, count = oortCount, config = config)
        } else {
            withBelt
        }
    }

    fun withCatalogBodies(
        base: SimulationSnapshot,
        definitions: List<CatalogBodyDefinition>,
        julianDateTdb: Double,
        config: SimulationConfig = SimulationConfig(),
    ): SimulationSnapshot {
        if (definitions.isEmpty()) return base

        val resolvedBodies = base.bodies.toMutableList()
        val bodiesById = resolvedBodies.associateBy { it.id }.toMutableMap()
        val pending = definitions.filter { definition ->
            definition.enabledByDefault && !bodiesById.containsKey(definition.id)
        }.toMutableList()

        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val definition = iterator.next()
                val host = bodiesById[definition.hostBodyId] ?: continue
                val state = stateFromCatalogDefinition(
                    definition = definition,
                    host = host,
                    julianDateTdb = julianDateTdb,
                    config = config,
                )
                resolvedBodies += state
                bodiesById[state.id] = state
                iterator.remove()
                progress = true
            }
        }

        return base.copy(bodies = OrbitalMechanics.recenterToBarycenter(resolvedBodies))
    }

    private fun seed(
        semiMajorAxisAu: Double,
        eccentricity: Double,
        inclinationDeg: Double,
        ascendingNodeDeg: Double,
        periapsisDeg: Double,
        trueAnomalyDeg: Double,
    ): OrbitalElements = OrbitalElements(
        semiMajorAxisM = semiMajorAxisAu * PhysicalConstants.ASTRONOMICAL_UNIT_M,
        eccentricity = eccentricity,
        inclinationRad = inclinationDeg.degToRad(),
        longitudeOfAscendingNodeRad = ascendingNodeDeg.degToRad(),
        argumentOfPeriapsisRad = periapsisDeg.degToRad(),
        trueAnomalyRad = trueAnomalyDeg.degToRad(),
    )

    private fun seededFromCatalogOrBundle(
        bundle: CartesianSeedBundle?,
        primary: BodyState,
        config: SimulationConfig,
        julianDateTdb: Double,
        id: String,
        name: String,
        category: BodyCategory,
        massKg: Double,
        radiusM: Double,
        colorArgb: Int,
    ): BodyState {
        val bundled = bundle?.recordFor(id)?.let { record ->
            bodyFromCartesianRecordOrDefault(
                record = record,
                id = id,
                name = name,
                category = category,
                gravitationalRole = GravitationalRole.MASSIVE,
                massKg = massKg,
                radiusM = radiusM,
                colorArgb = colorArgb,
                hostBodyId = primary.id,
            )
        }
        if (bundled != null) return bundled

        val stateVector = JplApproximateSeedCatalog.stateVectorForPlanet(
            planetId = id,
            primaryMassKg = primary.massKg,
            bodyMassKg = massKg,
            gravitationalConstant = config.gravitationalConstant,
            julianDateTdb = julianDateTdb,
        )

        return realBody(
            id = id,
            name = name,
            category = category,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = massKg,
            radiusM = radiusM,
            colorArgb = colorArgb,
            hostBodyId = primary.id,
            positionM = primary.positionM + stateVector.positionM,
            velocityMps = primary.velocityMps + stateVector.velocityMps,
        )
    }

    private fun seededAroundPrimaryOrBundle(
        bundle: CartesianSeedBundle?,
        primary: BodyState,
        config: SimulationConfig,
        julianDateTdb: Double,
        id: String,
        name: String,
        category: BodyCategory,
        massKg: Double,
        radiusM: Double,
        colorArgb: Int,
        fallbackElements: OrbitalElements,
    ): BodyState {
        val bundled = bundle?.recordFor(id)?.let { record ->
            bodyFromCartesianRecordOrDefault(
                record = record,
                id = id,
                name = name,
                category = category,
                gravitationalRole = GravitationalRole.MASSIVE,
                massKg = massKg,
                radiusM = radiusM,
                colorArgb = colorArgb,
                hostBodyId = primary.id,
            )
        }
        if (bundled != null) return bundled

        val fallbackOrbitAtEpoch = fallbackElements.toOrbitAtEpoch()
        val stateVector = OrbitalMechanics.stateVectorAroundPrimaryAtEpoch(
            primaryMassKg = primary.massKg,
            bodyMassKg = massKg,
            orbit = fallbackOrbitAtEpoch,
            targetJulianDateTdb = julianDateTdb,
            gravitationalConstant = config.gravitationalConstant,
        )

        return realBody(
            id = id,
            name = name,
            category = category,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = massKg,
            radiusM = radiusM,
            colorArgb = colorArgb,
            hostBodyId = primary.id,
            positionM = primary.positionM + stateVector.positionM,
            velocityMps = primary.velocityMps + stateVector.velocityMps,
        )
    }

    private fun OrbitalElements.toOrbitAtEpoch(
        epochJdTdb: Double = JplApproximateSeedCatalog.DEFAULT_SEED_JULIAN_DATE_TDB,
    ): KeplerianOrbitAtEpoch {
        val eccentricAnomaly = 2.0 * atan2(
            sqrt(1.0 - eccentricity) * sin(trueAnomalyRad / 2.0),
            sqrt(1.0 + eccentricity) * cos(trueAnomalyRad / 2.0),
        )
        val meanAnomalyAtEpoch = OrbitalMechanics.normalizeRadians(
            eccentricAnomaly - (eccentricity * sin(eccentricAnomaly)),
        )

        return KeplerianOrbitAtEpoch(
            epochJdTdb = epochJdTdb,
            semiMajorAxisM = semiMajorAxisM,
            eccentricity = eccentricity,
            inclinationRad = inclinationRad,
            longitudeOfAscendingNodeRad = longitudeOfAscendingNodeRad,
            argumentOfPeriapsisRad = argumentOfPeriapsisRad,
            meanAnomalyAtEpochRad = meanAnomalyAtEpoch,
        )
    }

    private fun seededAroundPrimary(
        primary: BodyState,
        config: SimulationConfig,
        id: String,
        name: String,
        category: BodyCategory,
        massKg: Double,
        radiusM: Double,
        colorArgb: Int,
        elements: OrbitalElements,
    ): BodyState {
        val stateVector = OrbitalMechanics.stateVectorAroundPrimary(
            primaryMassKg = primary.massKg,
            bodyMassKg = massKg,
            elements = elements,
            gravitationalConstant = config.gravitationalConstant,
        )

        return realBody(
            id = id,
            name = name,
            category = category,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = massKg,
            radiusM = radiusM,
            colorArgb = colorArgb,
            hostBodyId = primary.id,
            positionM = primary.positionM + stateVector.positionM,
            velocityMps = primary.velocityMps + stateVector.velocityMps,
        )
    }

    private fun bodyFromCartesianRecordOrDefault(
        record: CartesianSeedRecord?,
        id: String,
        name: String,
        category: BodyCategory,
        gravitationalRole: GravitationalRole,
        massKg: Double,
        radiusM: Double,
        colorArgb: Int,
        hostBodyId: String? = null,
    ): BodyState = realBody(
        id = id,
        name = name,
        category = category,
        gravitationalRole = gravitationalRole,
        massKg = massKg,
        radiusM = radiusM,
        colorArgb = colorArgb,
        hostBodyId = hostBodyId,
        positionM = record?.positionM ?: Vector3d.ZERO,
        velocityMps = record?.velocityMps ?: Vector3d.ZERO,
    )

    private fun tracerAroundPrimary(
        primary: BodyState,
        config: SimulationConfig,
        id: String,
        name: String,
        category: BodyCategory,
        radiusM: Double,
        colorArgb: Int,
        elements: OrbitalElements,
    ): BodyState {
        val stateVector = OrbitalMechanics.stateVectorAroundPrimary(
            primaryMassKg = primary.massKg,
            bodyMassKg = 0.0,
            elements = elements,
            gravitationalConstant = config.gravitationalConstant,
        )

        return BodyState(
            id = id,
            name = name,
            category = category,
            gravitationalRole = GravitationalRole.TRACER,
            massKg = 0.0,
            radiusM = radiusM,
            densityKgPerM3 = 0.0,
            positionM = primary.positionM + stateVector.positionM,
            velocityMps = primary.velocityMps + stateVector.velocityMps,
            colorArgb = colorArgb,
            hostBodyId = primary.id,
        )
    }

    private fun stateFromCatalogDefinition(
        definition: CatalogBodyDefinition,
        host: BodyState,
        julianDateTdb: Double,
        config: SimulationConfig,
    ): BodyState {
        val stateVector = OrbitalMechanics.stateVectorAroundPrimaryAtEpoch(
            primaryMassKg = host.massKg,
            bodyMassKg = definition.massKg,
            orbit = definition.orbit,
            targetJulianDateTdb = julianDateTdb,
            gravitationalConstant = config.gravitationalConstant,
        )
        return realBody(
            id = definition.id,
            name = definition.name,
            category = definition.category,
            gravitationalRole = definition.gravitationalRole,
            massKg = definition.massKg,
            radiusM = definition.radiusM,
            colorArgb = definition.colorArgb,
            hostBodyId = host.id,
            positionM = host.positionM + stateVector.positionM,
            velocityMps = host.velocityMps + stateVector.velocityMps,
        )
    }

    private fun realBody(
        id: String,
        name: String,
        category: BodyCategory,
        gravitationalRole: GravitationalRole,
        massKg: Double,
        radiusM: Double,
        colorArgb: Int,
        hostBodyId: String? = null,
        positionM: Vector3d = Vector3d.ZERO,
        velocityMps: Vector3d = Vector3d.ZERO,
    ): BodyState {
        val density = BodyFactory.densityFromMassAndRadius(massKg, radiusM).takeIf { it > 0.0 }
            ?: when (category) {
                BodyCategory.STAR -> DensityPreset.GAS_GIANT_KG_PER_M3
                BodyCategory.PLANET -> DensityPreset.ROCKY_KG_PER_M3
                BodyCategory.MOON -> DensityPreset.ICY_KG_PER_M3
                BodyCategory.DWARF_PLANET -> DensityPreset.ICY_KG_PER_M3
                BodyCategory.ASTEROID -> DensityPreset.ROCKY_KG_PER_M3
                BodyCategory.COMET -> DensityPreset.ICY_KG_PER_M3
                BodyCategory.TEST_OBJECT -> DensityPreset.ROCKY_KG_PER_M3
                BodyCategory.PROBE -> DensityPreset.METALLIC_KG_PER_M3
            }

        return BodyState(
            id = id,
            name = name,
            category = category,
            gravitationalRole = gravitationalRole,
            massKg = massKg,
            radiusM = radiusM,
            densityKgPerM3 = density,
            positionM = positionM,
            velocityMps = velocityMps,
            colorArgb = colorArgb,
            hostBodyId = hostBodyId,
        )
    }

    private fun Double.pow(power: Double): Double = Math.pow(this, power)
}
