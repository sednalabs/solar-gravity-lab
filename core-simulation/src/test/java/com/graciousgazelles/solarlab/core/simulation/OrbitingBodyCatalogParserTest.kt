package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitingBodyCatalogParserTest {

    @Test
    fun `parses moon catalog row with host and orbital elements`() {
        val text = """
            # comment line
            body_id	name	category	role	host_body_id	mass_kg	radius_m	color_argb	epoch_jd_tdb	a_m	e	i_deg	node_deg	peri_deg	mean_deg	enabled_by_default	notes
            moon-test	Test Moon	moon	massive	earth	7.35e22	1.7e6	0xFFCCCCCC	2451545.0	384400000.0	0.0549	5.145	125.08	318.15	135.27	true	unit test
        """.trimIndent()

        val definitions = OrbitingBodyCatalogParser.parseTsv(text)
        val moon = definitions.single()

        assertEquals("moon-test", moon.id)
        assertEquals(BodyCategory.MOON, moon.category)
        assertEquals(GravitationalRole.MASSIVE, moon.gravitationalRole)
        assertEquals("earth", moon.hostBodyId)
        assertEquals(2451545.0, moon.orbit.epochJdTdb, 0.0)
        assertEquals(384_400_000.0, moon.orbit.semiMajorAxisM, 1e-6)
        assertTrue(moon.enabledByDefault)
        assertEquals("unit test", moon.notes)
    }
}
