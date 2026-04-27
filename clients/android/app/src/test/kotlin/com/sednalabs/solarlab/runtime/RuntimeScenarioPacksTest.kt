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
}
