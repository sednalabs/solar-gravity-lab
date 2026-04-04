package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyFactory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.DensityPreset
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SimulationEngineTest {

    @Test
    fun `earth-like orbit remains roughly stable for one year`() {
        val snapshot = simpleSunEarthScenario()
        val engine = SimulationEngine(snapshot, SimulationConfig(collisionMode = CollisionMode.NONE))

        repeat((PhysicalConstants.JULIAN_YEAR_SECONDS / (6.0 * 3600.0)).toInt()) {
            engine.step(6.0 * 3600.0)
        }

        val earth = engine.snapshot().bodies.first { it.id == "earth" }
        val distanceAu = earth.positionM.magnitude() / PhysicalConstants.ASTRONOMICAL_UNIT_M

        assertTrue("Expected Earth to remain near 1 AU, was $distanceAu AU", distanceAu in 0.97..1.03)
    }

    @Test
    fun `energy drift stays small over one year two body run`() {
        val snapshot = simpleSunEarthScenario()
        val engine = SimulationEngine(snapshot, SimulationConfig(collisionMode = CollisionMode.NONE))

        val startingEnergy = engine.diagnostics().totalEnergyJ

        repeat((PhysicalConstants.JULIAN_YEAR_SECONDS / (3.0 * 3600.0)).toInt()) {
            engine.step(3.0 * 3600.0)
        }

        val endingEnergy = engine.diagnostics().totalEnergyJ
        val drift = abs((endingEnergy - startingEnergy) / startingEnergy)

        assertTrue("Energy drift was $drift", drift < 5e-3)
    }

    @Test
    fun `tracer bodies do not perturb massive bodies`() {
        val sun = BodyFactory.sphericalBody(
            id = "sun",
            name = "Sun",
            category = BodyCategory.STAR,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.98847e30,
            densityKgPerM3 = DensityPreset.GAS_GIANT_KG_PER_M3,
            positionM = Vector3d.ZERO,
            velocityMps = Vector3d.ZERO,
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val tracer = BodyFactory.sphericalBody(
            id = "tracer",
            name = "Huge passive tracer",
            category = BodyCategory.PROBE,
            gravitationalRole = GravitationalRole.TRACER,
            massKg = 1.0e28,
            densityKgPerM3 = DensityPreset.METALLIC_KG_PER_M3,
            positionM = Vector3d(PhysicalConstants.ASTRONOMICAL_UNIT_M, 0.0, 0.0),
            velocityMps = Vector3d.ZERO,
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        val engine = SimulationEngine(
            SimulationSnapshot(epochSeconds = 0.0, bodies = listOf(sun, tracer)),
            SimulationConfig(collisionMode = CollisionMode.NONE),
        )

        repeat(10) {
            engine.step(3600.0)
        }

        val currentSun = engine.snapshot().bodies.first { it.id == "sun" }
        assertEquals(0.0, currentSun.positionM.x, 1e-6)
        assertEquals(0.0, currentSun.positionM.y, 1e-6)
        assertEquals(0.0, currentSun.positionM.z, 1e-6)
    }

    @Test
    fun `colliding bodies merge with conserved mass`() {
        val a = BodyFactory.sphericalBody(
            id = "a",
            name = "A",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 5.0e12,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d.ZERO,
            velocityMps = Vector3d(1.0, 0.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val b = BodyFactory.sphericalBody(
            id = "b",
            name = "B",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 7.0e12,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(a.radiusM * 0.5, 0.0, 0.0),
            velocityMps = Vector3d(-1.0, 0.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        val engine = SimulationEngine(SimulationSnapshot(epochSeconds = 0.0, bodies = listOf(a, b)))
        val result = engine.step(1.0)

        assertEquals(1, result.snapshot.bodies.size)
        assertEquals(1.2e13, result.snapshot.bodies.first().massKg, 1.0)
        assertEquals(1, result.collisions.size)
        assertEquals(CollisionMode.MERGE, result.collisions.first().collisionMode)
    }

    @Test
    fun `colliding bodies can bounce elastically without tunnelling`() {
        val radiusM = 10.0
        val density = BodyFactory.densityFromMassAndRadius(1_000.0, radiusM)
        val a = BodyState(
            id = "a",
            name = "A",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1_000.0,
            radiusM = radiusM,
            densityKgPerM3 = density,
            positionM = Vector3d(-100.0, 0.0, 0.0),
            velocityMps = Vector3d(10.0, 0.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val b = BodyState(
            id = "b",
            name = "B",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1_000.0,
            radiusM = radiusM,
            densityKgPerM3 = density,
            positionM = Vector3d(100.0, 0.0, 0.0),
            velocityMps = Vector3d(-10.0, 0.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        val engine = SimulationEngine(
            initialSnapshot = SimulationSnapshot(epochSeconds = 0.0, bodies = listOf(a, b)),
            config = SimulationConfig(
                gravitationalConstant = 0.0,
                collisionMode = CollisionMode.ELASTIC,
            ),
        )
        val result = engine.step(12.0)

        assertEquals(2, result.snapshot.bodies.size)
        assertEquals(1, result.collisions.size)
        assertEquals(CollisionMode.ELASTIC, result.collisions.first().collisionMode)
        val resultA = result.snapshot.bodies.first { it.id == "a" }
        val resultB = result.snapshot.bodies.first { it.id == "b" }
        assertTrue(resultA.velocityMps.x < 0.0)
        assertTrue(resultB.velocityMps.x > 0.0)
    }

    @Test
    fun `supermassive low speed impacts accrete instead of elastic bouncing`() {
        val supermassive = BodyFactory.sphericalBody(
            id = "supermassive",
            name = "Supermassive",
            category = BodyCategory.STAR,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.0e24,
            densityKgPerM3 = DensityPreset.GAS_GIANT_KG_PER_M3,
            positionM = Vector3d.ZERO,
            velocityMps = Vector3d.ZERO,
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val impactor = BodyFactory.sphericalBody(
            id = "impactor",
            name = "Impactor",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.0e16,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(supermassive.radiusM + 25.0, 0.0, 0.0),
            velocityMps = Vector3d(-1.0, 0.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        val engine = SimulationEngine(
            initialSnapshot = SimulationSnapshot(epochSeconds = 0.0, bodies = listOf(supermassive, impactor)),
            config = SimulationConfig(
                gravitationalConstant = 0.0,
                collisionMode = CollisionMode.ELASTIC,
            ),
        )

        val result = engine.step(30.0)

        assertEquals(1, result.snapshot.bodies.size)
        assertEquals(1, result.collisions.size)
        assertEquals(CollisionMode.MERGE, result.collisions.first().collisionMode)
        assertEquals(1.0e24 + 1.0e16, result.snapshot.bodies.first().massKg, 1e9)
    }

    @Test
    fun `supermassive low speed impacts accrete on the escape velocity path`() {
        val supermassive = BodyFactory.sphericalBody(
            id = "supermassive",
            name = "Supermassive",
            category = BodyCategory.STAR,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.0e24,
            densityKgPerM3 = DensityPreset.GAS_GIANT_KG_PER_M3,
            positionM = Vector3d.ZERO,
            velocityMps = Vector3d.ZERO,
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val impactor = BodyFactory.sphericalBody(
            id = "impactor",
            name = "Impactor",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.0e16,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(supermassive.radiusM + 250.0, 0.0, 0.0),
            velocityMps = Vector3d(-50.0, 0.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        val engine = SimulationEngine(
            initialSnapshot = SimulationSnapshot(epochSeconds = 0.0, bodies = listOf(supermassive, impactor)),
            config = SimulationConfig(collisionMode = CollisionMode.ELASTIC),
        )

        val result = engine.step(10.0)

        assertEquals(1, result.snapshot.bodies.size)
        assertEquals(1, result.collisions.size)
        assertEquals(CollisionMode.MERGE, result.collisions.first().collisionMode)
    }

    @Test
    fun `supermassive fast impacts remain elastic in elastic mode`() {
        val supermassive = BodyFactory.sphericalBody(
            id = "supermassive",
            name = "Supermassive",
            category = BodyCategory.STAR,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.0e24,
            densityKgPerM3 = DensityPreset.GAS_GIANT_KG_PER_M3,
            positionM = Vector3d.ZERO,
            velocityMps = Vector3d.ZERO,
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val impactor = BodyFactory.sphericalBody(
            id = "impactor",
            name = "Impactor",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.0e16,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(supermassive.radiusM + 500.0, 0.0, 0.0),
            velocityMps = Vector3d(-20_000.0, 0.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        val engine = SimulationEngine(
            initialSnapshot = SimulationSnapshot(epochSeconds = 0.0, bodies = listOf(supermassive, impactor)),
            config = SimulationConfig(collisionMode = CollisionMode.ELASTIC),
        )

        val result = engine.step(1.0)

        assertEquals(2, result.snapshot.bodies.size)
        assertEquals(1, result.collisions.size)
        assertEquals(CollisionMode.ELASTIC, result.collisions.first().collisionMode)
    }

    @Test
    fun `colliding bodies fragment with conserved mass and momentum`() {
        val a = BodyState(
            id = "a",
            name = "A",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 10.0,
            radiusM = 5.0,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(-4.0, 0.0, 0.0),
            velocityMps = Vector3d(1.0, 0.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val b = BodyState(
            id = "b",
            name = "B",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 20.0,
            radiusM = 5.0,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(4.0, 0.0, 0.0),
            velocityMps = Vector3d(-1.0, 0.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        val initialMomentumX = (a.massKg * a.velocityMps.x) + (b.massKg * b.velocityMps.x)
        val initialMass = a.massKg + b.massKg
        val engine = SimulationEngine(
            initialSnapshot = SimulationSnapshot(epochSeconds = 0.0, bodies = listOf(a, b)),
            config = SimulationConfig(collisionMode = CollisionMode.FRAGMENTATION, gravitationalConstant = 0.0),
        )

        val result = engine.step(1.0)

        assertEquals(CollisionMode.FRAGMENTATION, result.collisions.first().collisionMode)
        assertEquals(1, result.collisions.size)
        assertEquals(2, result.snapshot.bodies.size)
        assertEquals(2, result.collisions.first().resultBodyIds.size)
        assertEquals(initialMass, result.snapshot.bodies.sumOf { it.massKg }, 1e-6)

        val finalMomentumX = result.snapshot.bodies.sumOf { it.massKg * it.velocityMps.x }
        assertEquals(initialMomentumX, finalMomentumX, 1e-6)
    }

    @Test
    fun `bodies can be added updated and removed`() {
        val engine = SimulationEngine(SimulationSnapshot(epochSeconds = 0.0, bodies = emptyList()))
        val body = BodyFactory.sphericalBody(
            id = "custom",
            name = "Custom",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.0e5,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(1.0, 2.0, 3.0),
            velocityMps = Vector3d(4.0, 5.0, 6.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        engine.addBody(body)
        assertEquals(1, engine.snapshot().bodies.size)

        val updated = body.copy(velocityMps = Vector3d(7.0, 8.0, 9.0))
        assertTrue(engine.updateBody(updated))
        assertEquals(9.0, engine.body("custom")?.velocityMps?.z ?: Double.NaN, 0.0)

        assertTrue(engine.removeBody("custom"))
        assertEquals(0, engine.snapshot().bodies.size)
    }

    private fun simpleSunEarthScenario(): SimulationSnapshot {
        val sun = BodyFactory.sphericalBody(
            id = "sun",
            name = "Sun",
            category = BodyCategory.STAR,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.98847e30,
            densityKgPerM3 = DensityPreset.GAS_GIANT_KG_PER_M3,
            positionM = Vector3d.ZERO,
            velocityMps = Vector3d.ZERO,
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        val earthOrbitalRadius = PhysicalConstants.ASTRONOMICAL_UNIT_M
        val earthSpeed = OrbitalMechanics.circularOrbitalSpeed(
            primaryMassKg = sun.massKg,
            orbitalRadiusM = earthOrbitalRadius,
            gravitationalConstant = PhysicalConstants.GRAVITATIONAL_CONSTANT_M3_PER_KG_S2,
        )
        val earth = BodyFactory.sphericalBody(
            id = "earth",
            name = "Earth",
            category = BodyCategory.PLANET,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 5.97237e24,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(earthOrbitalRadius, 0.0, 0.0),
            velocityMps = Vector3d(0.0, earthSpeed, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )

        return SimulationSnapshot(
            epochSeconds = 0.0,
            bodies = OrbitalMechanics.recenterToBarycenter(listOf(sun, earth)),
        )
    }
}
