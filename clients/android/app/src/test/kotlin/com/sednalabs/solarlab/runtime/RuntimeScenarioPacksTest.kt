package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeScenarioPacksTest {
    @Test
    fun catalog_containsUniqueScenarioIdsAndDefault() {
        val ids = RuntimeScenarioPacks.all.map { it.scenarioId }

        assertEquals(ids.toSet().size, ids.size)
        assertNotNull(RuntimeScenarioPacks.byId(RuntimeScenarioPacks.DEFAULT_SCENARIO_ID))
    }

    @Test
    fun showcasePacksCarryVisualIterationDefaults() {
        val jupiter = RuntimeScenarioPacks.requireKnown("showcase.jupiter-system")
        val earthMoon = RuntimeScenarioPacks.requireKnown("showcase.earth-moon")

        assertEquals("jupiter", jupiter.defaultFocusBodyId)
        assertEquals(RuntimeObserverMode.FollowSelected, jupiter.defaultObserverMode)
        assertEquals("moon", earthMoon.defaultFocusBodyId)
        assertTrue(earthMoon.startPaused)
    }

    @Test
    fun s25TileSwarmPackCarriesSchedulerStressDefaults() {
        val pack = RuntimeScenarioPacks.requireKnown("stress.s25-tile-swarm")

        assertEquals("sun", pack.defaultFocusBodyId)
        assertEquals(RuntimeObserverMode.SystemFrame, pack.defaultObserverMode)
        assertTrue(pack.tags.contains("arm64"))
        assertTrue(pack.tags.contains("tiles"))
        assertEquals(172_800.0, pack.simSecondsPerRealSecond, 0.0)
    }
}
