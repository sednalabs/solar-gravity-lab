package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class JplApproximateSeedCatalogTest {

    @Test
    fun `major body starter states sit near expected solar distances`() {
        val config = SimulationConfig()
        val sunMassKg = 1.98847e30

        val mercury = JplApproximateSeedCatalog.stateVectorForPlanet(
            planetId = "mercury",
            primaryMassKg = sunMassKg,
            bodyMassKg = 3.3011e23,
            gravitationalConstant = config.gravitationalConstant,
        )
        val earth = JplApproximateSeedCatalog.stateVectorForPlanet(
            planetId = "earth",
            primaryMassKg = sunMassKg,
            bodyMassKg = 5.97237e24,
            gravitationalConstant = config.gravitationalConstant,
        )
        val neptune = JplApproximateSeedCatalog.stateVectorForPlanet(
            planetId = "neptune",
            primaryMassKg = sunMassKg,
            bodyMassKg = 1.02413e26,
            gravitationalConstant = config.gravitationalConstant,
        )

        val mercuryRadiusAu = mercury.positionM.magnitude() / PhysicalConstants.ASTRONOMICAL_UNIT_M
        val earthRadiusAu = earth.positionM.magnitude() / PhysicalConstants.ASTRONOMICAL_UNIT_M
        val neptuneRadiusAu = neptune.positionM.magnitude() / PhysicalConstants.ASTRONOMICAL_UNIT_M

        assertTrue("Mercury starter radius was $mercuryRadiusAu AU", mercuryRadiusAu in 0.30..0.50)
        assertTrue("Earth starter radius was $earthRadiusAu AU", earthRadiusAu in 0.95..1.05)
        assertTrue("Neptune starter radius was $neptuneRadiusAu AU", neptuneRadiusAu in 29.0..31.5)
    }

    @Test
    fun `major body scenario barycenter is recentered near origin`() {
        val snapshot = SolarSystemScenarios.majorBodiesWithDwarfs()
        val massiveBodies = snapshot.bodies.filter { it.sourceMassKg > 0.0 }
        val totalMass = massiveBodies.sumOf { it.sourceMassKg }

        val barycenter = massiveBodies.fold(Vector3d.ZERO) { acc, body ->
            acc + (body.positionM * body.sourceMassKg)
        } / totalMass

        val baryVelocity = massiveBodies.fold(Vector3d.ZERO) { acc, body ->
            acc + (body.velocityMps * body.sourceMassKg)
        } / totalMass

        assertTrue("Barycenter magnitude was ${barycenter.magnitude()} m", barycenter.magnitude() < 1e-3)
        assertTrue("Barycenter velocity magnitude was ${baryVelocity.magnitude()} m/s", baryVelocity.magnitude() < 1e-9)
    }
}
