package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.DensityPreset
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverCameraResolverTest {

    @Test
    fun resolveCameraCenterReturnsNullInFreeMode() {
        val frame = scene(
            body(id = "planet", positionM = Vector3d(10.0, 20.0, 30.0)),
        )

        val center = ObserverCameraResolver.resolveCameraCenterM(
            frame = frame,
            selectedBodyId = "planet",
            observerMode = ObserverMode.FREE,
        )

        assertNull(center)
        assertFalse(ObserverCameraResolver.isCameraLocked(frame, "planet", ObserverMode.FREE))
    }

    @Test
    fun resolveCameraCenterTracksSelectedBody() {
        val selectedPosition = Vector3d(12.0, 5.0, 0.0)
        val frame = scene(
            body(id = "planet", positionM = selectedPosition),
            body(id = "moon", positionM = Vector3d(15.0, 5.0, 0.0), hostBodyId = "planet"),
        )

        val center = ObserverCameraResolver.resolveCameraCenterM(
            frame = frame,
            selectedBodyId = "planet",
            observerMode = ObserverMode.FOLLOW_SELECTED,
        )

        assertEquals(selectedPosition, center)
        assertTrue(ObserverCameraResolver.isCameraLocked(frame, "planet", ObserverMode.FOLLOW_SELECTED))
    }

    @Test
    fun resolveCameraCenterTracksSelectedBodyHost() {
        val hostPosition = Vector3d(100.0, 200.0, 0.0)
        val frame = scene(
            body(id = "planet", positionM = hostPosition),
            body(id = "moon", positionM = Vector3d(104.0, 200.0, 0.0), hostBodyId = "planet"),
        )

        val center = ObserverCameraResolver.resolveCameraCenterM(
            frame = frame,
            selectedBodyId = "moon",
            observerMode = ObserverMode.FOLLOW_SELECTED_HOST,
        )

        assertEquals(hostPosition, center)
    }

    @Test
    fun resolveCameraCenterReturnsNullWhenSelectedBodyHasNoHost() {
        val frame = scene(
            body(id = "planet", positionM = Vector3d(100.0, 200.0, 0.0)),
        )

        val center = ObserverCameraResolver.resolveCameraCenterM(
            frame = frame,
            selectedBodyId = "planet",
            observerMode = ObserverMode.FOLLOW_SELECTED_HOST,
        )

        assertNull(center)
        assertFalse(ObserverCameraResolver.isCameraLocked(frame, "planet", ObserverMode.FOLLOW_SELECTED_HOST))
    }

    @Test
    fun renderSceneAssemblerPreservesHostBodyIdForObserverResolution() {
        val assembler = RenderSceneAssembler()
        val snapshot = SimulationSnapshot(
            epochSeconds = 0.0,
            bodies = listOf(
                modelBody(id = "planet"),
                modelBody(id = "moon", hostBodyId = "planet"),
            ),
        )

        val frame = assembler.assemble(snapshot)
        val moon = (frame.authoritativeBodies + frame.tracerBodies).first { it.id == "moon" }
        assertEquals("planet", moon.hostBodyId)
    }

    private fun scene(vararg bodies: RenderBody): RenderSceneFrame = RenderSceneFrame(
        epochSeconds = 0.0,
        authoritativeBodies = bodies.toList(),
        tracerBodies = emptyList(),
        trails = emptyList(),
    )

    private fun body(
        id: String,
        positionM: Vector3d,
        hostBodyId: String? = null,
    ): RenderBody = RenderBody(
        id = id,
        name = id,
        positionM = positionM,
        radiusM = 1.0,
        colorArgb = 0xFFFFFFFF.toInt(),
        kind = RenderBodyKind.PLANET,
        isMassive = true,
        hostBodyId = hostBodyId,
    )

    private fun modelBody(
        id: String,
        hostBodyId: String? = null,
    ): BodyState = BodyState(
        id = id,
        name = id,
        category = BodyCategory.PLANET,
        gravitationalRole = GravitationalRole.MASSIVE,
        massKg = 1.0,
        radiusM = 1.0,
        densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
        positionM = Vector3d.ZERO,
        velocityMps = Vector3d.ZERO,
        colorArgb = 0xFFFFFFFF.toInt(),
        hostBodyId = hostBodyId,
    )
}
