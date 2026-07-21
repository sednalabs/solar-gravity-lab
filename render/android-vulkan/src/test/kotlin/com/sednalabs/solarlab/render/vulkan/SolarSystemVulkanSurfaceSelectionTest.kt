package com.sednalabs.solarlab.render.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolarSystemVulkanSurfaceSelectionTest {
    @Test
    fun `an empty stage pick preserves the current selection`() {
        assertEquals("saturn", retainSelectionOnEmptyPick("saturn", null))
    }

    @Test
    fun `a body pick replaces the current selection`() {
        assertEquals("moon", retainSelectionOnEmptyPick("saturn", "moon"))
    }

    @Test
    fun `an empty pick without an existing selection remains empty`() {
        assertNull(retainSelectionOnEmptyPick(null, null))
    }
}
