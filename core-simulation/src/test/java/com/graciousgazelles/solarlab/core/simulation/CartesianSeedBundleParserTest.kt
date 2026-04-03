package com.graciousgazelles.solarlab.core.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CartesianSeedBundleParserTest {

    @Test
    fun `parses horizons seed bundle header and records`() {
        val bundle = CartesianSeedBundleParser.parse(sampleBundleText())

        assertEquals("1", bundle.metadata.bundleVersion)
        assertEquals("JPL Horizons VECTORS", bundle.metadata.source)
        assertEquals(2451545.0, bundle.metadata.epochJdTdb, 0.0)
        assertEquals("500@0", bundle.metadata.centerId)
        assertEquals("ICRF", bundle.metadata.frame)
        assertEquals(2, bundle.recordsByBodyId.size)

        val mercury = bundle.requireRecord("mercury")
        assertEquals("Mercury", mercury.displayName)
        assertEquals(1.0, mercury.positionM.x, 0.0)
        assertEquals(6.0, mercury.velocityMps.z, 0.0)
    }

    @Test
    fun `validator accepts complete consistent bundle`() {
        val bundle = CartesianSeedBundleParser.parse(completeBundleText())
        val validation = CartesianSeedBundleValidator.validate(bundle)

        assertTrue(validation.errors.isEmpty())
        assertTrue(validation.warnings.isEmpty())
    }

    private fun sampleBundleText(): String = """
        # SolarLab Horizons Seed Bundle v1
        bundle_version=1
        dataset_name=Sample
        source=JPL Horizons VECTORS
        epoch_jd_tdb=2451545.0
        center_id=500@0
        frame=ICRF
        time_scale=TDB
        position_units=m
        velocity_units=m/s
        ---
        body_id	name	target	center_id	frame	epoch_jd_tdb	x_m	y_m	z_m	vx_mps	vy_mps	vz_mps	source
        sun	Sun	10	500@0	ICRF	2451545.0	0	0	0	0	0	0	JPL Horizons VECTORS
        mercury	Mercury	199	500@0	ICRF	2451545.0	1	2	3	4	5	6	JPL Horizons VECTORS
    """.trimIndent()

    private fun completeBundleText(): String {
        val header = """
            # SolarLab Horizons Seed Bundle v1
            bundle_version=1
            dataset_name=Complete
            source=JPL Horizons VECTORS
            epoch_jd_tdb=2451545.0
            center_id=500@0
            frame=ICRF
            time_scale=TDB
            position_units=m
            velocity_units=m/s
            ---
            body_id	name	target	center_id	frame	epoch_jd_tdb	x_m	y_m	z_m	vx_mps	vy_mps	vz_mps	source
        """.trimIndent()

        val rows = CartesianSeedBundleValidator.requiredSunThroughDwarfPlanetIds.mapIndexed { index, bodyId ->
            listOf(
                bodyId,
                bodyId.replaceFirstChar { it.uppercase() },
                bodyId,
                "500@0",
                "ICRF",
                "2451545.0",
                index.toString(),
                "0",
                "0",
                "0",
                index.toString(),
                "0",
                "JPL Horizons VECTORS",
            ).joinToString("\t")
        }

        return (listOf(header) + rows).joinToString("\n")
    }
}
