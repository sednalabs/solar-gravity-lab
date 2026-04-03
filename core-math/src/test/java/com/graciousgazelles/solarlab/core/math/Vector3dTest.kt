package com.graciousgazelles.solarlab.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Vector3dTest {

    @Test
    fun `cross product follows right hand rule`() {
        val x = Vector3d(1.0, 0.0, 0.0)
        val y = Vector3d(0.0, 1.0, 0.0)

        val z = x.cross(y)

        assertEquals(0.0, z.x, 1e-12)
        assertEquals(0.0, z.y, 1e-12)
        assertEquals(1.0, z.z, 1e-12)
    }

    @Test
    fun `normalization preserves direction`() {
        val vector = Vector3d(3.0, 4.0, 0.0)
        val normalized = vector.normalized()

        assertEquals(1.0, normalized.magnitude(), 1e-12)
        assertTrue(normalized.x > 0.0)
        assertTrue(normalized.y > 0.0)
    }
}
