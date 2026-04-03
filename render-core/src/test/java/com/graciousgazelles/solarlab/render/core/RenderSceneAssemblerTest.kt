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
