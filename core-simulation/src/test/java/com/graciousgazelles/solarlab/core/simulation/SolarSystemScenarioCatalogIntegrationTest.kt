package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.TimelineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarSystemScenarioCatalogIntegrationTest {

    @Test
    fun `default lab scenario is catalog-backed and includes starter moons and curated bodies`() {
        val snapshot = SolarSystemScenarios.defaultLabScenario(
            includeSyntheticAsteroidBelt = false,
            includeSyntheticOortCloud = false,
        )

        assertEquals(TimelineMode.CATALOG, snapshot.timelineMode)
        assertNotNull(snapshot.referenceEpochJdTdb)
        assertTrue(snapshot.bodies.any { it.id == "moon" && it.category == BodyCategory.MOON })
        assertTrue(snapshot.bodies.any { it.id == "io" && it.category == BodyCategory.MOON })
        assertTrue(snapshot.bodies.any { it.id == "titan" && it.category == BodyCategory.MOON })
        assertTrue(snapshot.bodies.any { it.id == "vesta" && it.category == BodyCategory.ASTEROID })
        assertTrue(snapshot.bodies.any { it.id == "halley" && it.category == BodyCategory.COMET })
    }

    @Test
    fun `catalog body propagation changes moon position with epoch`() {
        val t0 = SolarSystemScenarios.defaultSeedJulianDateTdb()
        val snapshot0 = SolarSystemScenarios.defaultLabScenario(
            julianDateTdb = t0,
            includeSyntheticAsteroidBelt = false,
            includeSyntheticOortCloud = false,
        )
        val snapshot1 = SolarSystemScenarios.defaultLabScenario(
            julianDateTdb = t0 + 1.0,
            includeSyntheticAsteroidBelt = false,
            includeSyntheticOortCloud = false,
        )

        val moon0 = snapshot0.bodies.first { it.id == "moon" }
        val moon1 = snapshot1.bodies.first { it.id == "moon" }
        val movement = (moon1.positionM - moon0.positionM).magnitude()

        assertTrue("Expected Moon to move between epochs, movement=$movement", movement > 1.0e6)
    }

    @Test
    fun `curated small bodies remain when starter moons are disabled with no imports`() {
        val snapshot = SolarSystemScenarios.defaultLabScenario(
            includeStarterPlanetaryMoons = false,
            includeSyntheticAsteroidBelt = false,
            includeSyntheticOortCloud = false,
        )

        assertFalse(snapshot.bodies.any { it.category == BodyCategory.MOON })
        assertTrue(snapshot.bodies.any { it.id == "vesta" && it.category == BodyCategory.ASTEROID })
        assertTrue(snapshot.bodies.any { it.id == "halley" && it.category == BodyCategory.COMET })
    }

    @Test
    fun `dwarf fallback propagation depends on target julian date`() {
        val t0 = SolarSystemScenarios.defaultSeedJulianDateTdb()
        val snapshot0 = SolarSystemScenarios.majorBodiesWithDwarfs(
            seedBundle = null,
            julianDateTdb = t0,
        )
        val snapshot1 = SolarSystemScenarios.majorBodiesWithDwarfs(
            seedBundle = null,
            julianDateTdb = t0 + 365.0,
        )

        val sun0 = snapshot0.bodies.first { it.id == "sun" }
        val sun1 = snapshot1.bodies.first { it.id == "sun" }
        val pluto0 = snapshot0.bodies.first { it.id == "pluto" }
        val pluto1 = snapshot1.bodies.first { it.id == "pluto" }

        val relativePluto0 = pluto0.positionM - sun0.positionM
        val relativePluto1 = pluto1.positionM - sun1.positionM
        val movement = (relativePluto1 - relativePluto0).magnitude()

        assertTrue("Expected Pluto fallback orbit to propagate across epochs, movement=$movement", movement > 1.0e6)
    }
}
