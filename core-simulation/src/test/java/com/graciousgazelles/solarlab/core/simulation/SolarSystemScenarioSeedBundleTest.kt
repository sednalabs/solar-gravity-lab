package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `synthetic tracers use caller simulation config`() {
        val base = SimulationSnapshot(
            epochSeconds = 0.0,
            bodies = listOf(
                BodyState(
                    id = "sun",
                    name = "Sun",
                    category = BodyCategory.STAR,
                    gravitationalRole = GravitationalRole.MASSIVE,
                    massKg = 1.98847e30,
                    radiusM = 6.9634e8,
                    densityKgPerM3 = 1408.0,
                    positionM = Vector3d.ZERO,
                    velocityMps = Vector3d.ZERO,
                    colorArgb = 0xFFFFD166.toInt(),
                ),
            ),
        )

        val lowGravity = SimulationConfig(gravitationalConstant = 1.0)
        val highGravity = SimulationConfig(gravitationalConstant = 4.0)

        val lowSnapshot = SolarSystemScenarios.withSyntheticAsteroidBelt(
            base = base,
            count = 1,
            config = lowGravity,
            seed = 99L,
        )
        val highSnapshot = SolarSystemScenarios.withSyntheticAsteroidBelt(
            base = base,
            count = 1,
            config = highGravity,
            seed = 99L,
        )

        val lowTracerSpeed = lowSnapshot.bodies.first { it.id == "belt-0" }.velocityMps.magnitude()
        val highTracerSpeed = highSnapshot.bodies.first { it.id == "belt-0" }.velocityMps.magnitude()

        assertNotEquals(lowTracerSpeed, highTracerSpeed, 1e-12)
    }
}
