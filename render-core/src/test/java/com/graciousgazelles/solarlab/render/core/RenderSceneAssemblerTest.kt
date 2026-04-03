package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.DensityPreset
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSceneAssemblerTest {

    @Test
    fun assemblePartitionsMassiveAndTracerBodies() {
        val assembler = RenderSceneAssembler(maxTrailPointsPerBody = 4)
        val snapshot = SimulationSnapshot(
            epochSeconds = 1.0,
            bodies = listOf(
                body("sun", GravitationalRole.MASSIVE, BodyCategory.STAR),
                body("tracer", GravitationalRole.TRACER, BodyCategory.ASTEROID),
            ),
        )

        val frame = assembler.assemble(snapshot)
        assertEquals(1, frame.authoritativeBodies.size)
        assertEquals(1, frame.tracerBodies.size)
        assertTrue(frame.trails.isEmpty())
        assertTrue(frame.sourceRevision > 0)

        val secondFrame = assembler.assemble(snapshot.copy(epochSeconds = 2.0))
        assertTrue(secondFrame.trails.isNotEmpty())
    }

    @Test
    fun nativePacketPacksSceneDataAndTracerTiers() {
        val frame = RenderSceneFrame(
            epochSeconds = 0.0,
            authoritativeBodies = listOf(
                RenderBody(
                    id = "sun",
                    name = "Sun",
                    positionM = Vector3d(1.0, 2.0, 3.0),
                    radiusM = 5.0,
                    colorArgb = 0xFFFFFFFF.toInt(),
                    kind = RenderBodyKind.STAR,
                    isMassive = true,
                ),
            ),
            tracerBodies = listOf(
                RenderBody(
                    id = "asteroid-near",
                    name = "Asteroid Near",
                    positionM = Vector3d(0.5 * PhysicalConstants.ASTRONOMICAL_UNIT_M, 0.0, 0.0),
                    radiusM = 10.0,
                    colorArgb = 0xFF00FF00.toInt(),
                    kind = RenderBodyKind.ASTEROID,
                    isMassive = false,
                ),
                RenderBody(
                    id = "asteroid-far",
                    name = "Asteroid Far",
                    positionM = Vector3d(12.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M, 0.0, 0.0),
                    radiusM = 5.0,
                    colorArgb = 0xFF00AA00.toInt(),
                    kind = RenderBodyKind.ASTEROID,
                    isMassive = false,
                ),
            ),
            trails = listOf(
                RenderTrail(
                    bodyId = "sun",
                    colorArgb = 0xFFFFFFFF.toInt(),
                    alpha = 0.25f,
                    pointsM = List(50) { index ->
                        Vector3d(index.toDouble() * 1_000.0, 0.0, 0.0)
                    },
                ),
            ),
            sourceRevision = 7L,
        )

        val packet = NativeScenePacket.fromScene(
            frame = frame,
            cameraState = CameraState(
                centerM = Vector3d.ZERO,
                viewRadiusM = PhysicalConstants.ASTRONOMICAL_UNIT_M,
            ),
            viewportWidthPx = 1920,
            viewportHeightPx = 1080,
            policy = ScenePacketBuildPolicy(
                nearTracerBudget = 10,
                mediumTracerBudget = 10,
                farTracerBudget = 10,
                maxTrailVerticesPerTrail = 8,
                trailSimplificationTolerancePx = 5.0,
            ),
        )
        assertEquals(7L, packet.sourceRevision)
        assertEquals(3, packet.authoritativePositionsM.size)
        assertEquals(1.0, packet.authoritativePositionsM[0], 0.0)
        assertEquals(5.0f, packet.authoritativeRadiiM[0])
        assertEquals(1, packet.tracerNearCount)
        assertEquals(0, packet.tracerMediumCount)
        assertEquals(1, packet.tracerFarCount)
        assertTrue(packet.trailVertexCounts.first() <= 8)
    }

    @Test
    fun nativePacketDownsamplesTracerTiersDeterministically() {
        val tracers = (0 until 100).map { index ->
            RenderBody(
                id = "t-$index",
                name = "Tracer $index",
                positionM = Vector3d(0.1 * PhysicalConstants.ASTRONOMICAL_UNIT_M, index.toDouble() * 1_000.0, 0.0),
                radiusM = if (index == 0) 100.0 else 1.0,
                colorArgb = 0xFFFFFFFF.toInt(),
                kind = RenderBodyKind.ASTEROID,
                isMassive = false,
            )
        }
        val frame = RenderSceneFrame(
            epochSeconds = 0.0,
            authoritativeBodies = emptyList(),
            tracerBodies = tracers,
            trails = emptyList(),
            sourceRevision = 1L,
        )

        val packet = NativeScenePacket.fromScene(
            frame = frame,
            cameraState = CameraState(viewRadiusM = PhysicalConstants.ASTRONOMICAL_UNIT_M),
            viewportWidthPx = 1920,
            viewportHeightPx = 1080,
            policy = ScenePacketBuildPolicy(
                nearTracerBudget = 12,
                mediumTracerBudget = 0,
                farTracerBudget = 0,
            ),
        )

        assertEquals(12, packet.tracerNearCount)
        // Highest-priority body should survive downsampling.
        assertEquals(100.0f, packet.tracerNearRadiiM.maxOrNull() ?: 0.0f)
    }

    @Test
    fun clearRemovesTrailHistorySoFirstFrameAfterResetHasNoTrail() {
        val assembler = RenderSceneAssembler(maxTrailPointsPerBody = 8)
        val firstSnapshot = SimulationSnapshot(
            epochSeconds = 1.0,
            bodies = listOf(
                body("sun", GravitationalRole.MASSIVE, BodyCategory.STAR)
                    .copy(positionM = Vector3d(0.0, 0.0, 0.0)),
            ),
        )
        val secondSnapshot = firstSnapshot.copy(
            epochSeconds = 2.0,
            bodies = listOf(
                firstSnapshot.bodies.single().copy(positionM = Vector3d(1_000.0, 0.0, 0.0)),
            ),
        )

        val frameBeforeReset = assembler.assemble(firstSnapshot)
        assertTrue(frameBeforeReset.trails.isEmpty())
        val populatedFrame = assembler.assemble(secondSnapshot)
        assertEquals(1, populatedFrame.trails.size)

        assembler.clear()

        val firstFrameAfterReset = assembler.assemble(firstSnapshot.copy(epochSeconds = 3.0))
        assertTrue(firstFrameAfterReset.trails.isEmpty())
    }

    @Test
    fun assembleTracksMoonAndProbeTrailsToPreserveReadableMotion() {
        val assembler = RenderSceneAssembler(maxTrailPointsPerBody = 8)
        val firstSnapshot = SimulationSnapshot(
            epochSeconds = 1.0,
            bodies = listOf(
                body("earth", GravitationalRole.MASSIVE, BodyCategory.PLANET),
                body("moon", GravitationalRole.TRACER, BodyCategory.MOON).copy(
                    hostBodyId = "earth",
                    positionM = Vector3d(384_400_000.0, 0.0, 0.0),
                ),
                body("probe", GravitationalRole.TRACER, BodyCategory.PROBE).copy(
                    positionM = Vector3d(600_000_000.0, 0.0, 0.0),
                ),
            ),
        )
        val secondSnapshot = firstSnapshot.copy(
            epochSeconds = 2.0,
            bodies = listOf(
                firstSnapshot.bodies[0].copy(positionM = Vector3d(10_000.0, 0.0, 0.0)),
                firstSnapshot.bodies[1].copy(positionM = Vector3d(384_450_000.0, 20_000.0, 0.0)),
                firstSnapshot.bodies[2].copy(positionM = Vector3d(601_000_000.0, 80_000.0, 0.0)),
            ),
        )

        val firstFrame = assembler.assemble(firstSnapshot)
        assertTrue(firstFrame.trails.isEmpty())

        val secondFrame = assembler.assemble(secondSnapshot)
        val trailIds = secondFrame.trails.map { it.bodyId }.toSet()
        assertTrue("moon" in trailIds)
        assertTrue("probe" in trailIds)
    }

    private fun body(id: String, role: GravitationalRole, category: BodyCategory): BodyState = BodyState(
        id = id,
        name = id,
        category = category,
        gravitationalRole = role,
        massKg = if (role == GravitationalRole.MASSIVE) 1.0 else 0.0,
        radiusM = 1.0,
        densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
        positionM = Vector3d.ZERO,
        velocityMps = Vector3d.ZERO,
        colorArgb = 0xFFFFFFFF.toInt(),
    )
}
