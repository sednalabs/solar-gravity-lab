package com.graciousgazelles.solarlab.core.simulation

import org.junit.Assert.assertEquals
import org.junit.Test

class SolarSystemScenarioSeedBundleTest {

    @Test
    fun `bundle records drive relative major body geometry`() {
        val bundle = CartesianSeedBundle(
            metadata = CartesianSeedBundleMetadata(
                bundleVersion = "1",
                datasetName = "Unit Test Bundle",
                source = "Synthetic cartesian records",
                epochJdTdb = 2451545.0,
                centerId = "500@0",
                frame = "ICRF",
                timeScale = "TDB",
                positionUnits = "m",
                velocityUnits = "m/s",
            ),
            recordsByBodyId = mapOf(
                "sun" to CartesianSeedRecord(
                    bodyId = "sun",
                    displayName = "Sun",
                    targetSpecifier = "10",
                    centerId = "500@0",
                    frame = "ICRF",
                    epochJdTdb = 2451545.0,
                    positionM = com.graciousgazelles.solarlab.core.math.Vector3d(100.0, 200.0, 300.0),
                    velocityMps = com.graciousgazelles.solarlab.core.math.Vector3d(1.0, 2.0, 3.0),
                    source = "test",
                ),
                "mercury" to CartesianSeedRecord(
                    bodyId = "mercury",
                    displayName = "Mercury",
                    targetSpecifier = "199",
                    centerId = "500@0",
                    frame = "ICRF",
                    epochJdTdb = 2451545.0,
                    positionM = com.graciousgazelles.solarlab.core.math.Vector3d(400.0, 800.0, 1200.0),
                    velocityMps = com.graciousgazelles.solarlab.core.math.Vector3d(4.0, 5.0, 6.0),
                    source = "test",
                ),
            ),
        )

        val snapshot = SolarSystemScenarios.majorBodiesWithDwarfs(seedBundle = bundle)
        val sun = snapshot.bodies.first { it.id == "sun" }
        val mercury = snapshot.bodies.first { it.id == "mercury" }

        val relativePosition = mercury.positionM - sun.positionM
        val relativeVelocity = mercury.velocityMps - sun.velocityMps

        assertEquals(300.0, relativePosition.x, 1e-9)
        assertEquals(600.0, relativePosition.y, 1e-9)
        assertEquals(900.0, relativePosition.z, 1e-9)
        assertEquals(3.0, relativeVelocity.x, 1e-9)
        assertEquals(3.0, relativeVelocity.y, 1e-9)
        assertEquals(3.0, relativeVelocity.z, 1e-9)
    }
}
