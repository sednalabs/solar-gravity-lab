package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.TimelineMode
import org.junit.Assert.assertEquals
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
}
