package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraNavigationTest {
    @Test
    fun scenarioFitCentersAndPadsTheAuthoritativeScene() {
        val astronomicalUnitM = PhysicalConstants.ASTRONOMICAL_UNIT_M
        val frame = RenderSceneFrame(
            epochSeconds = 0.0,
            authoritativeBodies = listOf(
                body("sun", Vector3d.ZERO, RenderBodyKind.STAR),
                body("outer", Vector3d(30.0 * astronomicalUnitM, 0.0, 0.0), RenderBodyKind.PLANET),
            ),
            tracerBodies = emptyList(),
            trails = emptyList(),
        )

        val fitted = CameraNavigation.scenarioFit(
            frame = frame,
            currentCameraState = CameraState(yawRadians = 1.4, pitchRadians = 0.4),
            minViewRadiusM = 0.001 * astronomicalUnitM,
            maxViewRadiusM = 150_000.0 * astronomicalUnitM,
        )

        assertEquals(15.0 * astronomicalUnitM, fitted.centerM.x, 1.0)
        assertTrue(fitted.viewRadiusM > 15.0 * astronomicalUnitM)
        assertEquals(CameraState.DEFAULT_YAW_RADIANS, fitted.yawRadians, 0.0)
        assertEquals(
            MultiscaleOrbitCameraController.preferredPitchRadiansFor(fitted.viewRadiusM),
            fitted.pitchRadians,
            0.0,
        )
    }

    @Test
    fun transitionUsesLogarithmicScaleAndShortestYawPath() {
        val start = CameraState(
            centerM = Vector3d.ZERO,
            viewRadiusM = 1_000.0,
            yawRadians = Math.toRadians(170.0),
            pitchRadians = 0.5,
        )
        val target = CameraState(
            centerM = Vector3d(10.0, 20.0, 30.0),
            viewRadiusM = 100_000.0,
            yawRadians = Math.toRadians(-170.0),
            pitchRadians = 1.0,
        )

        val midpoint = CameraNavigation.interpolate(start, target, 0.5f)

        assertEquals(Vector3d(5.0, 10.0, 15.0), midpoint.centerM)
        assertEquals(10_000.0, midpoint.viewRadiusM, 1.0e-9)
        assertEquals(Math.PI, kotlin.math.abs(midpoint.yawRadians), 1.0e-9)
        assertEquals(0.75, midpoint.pitchRadians, 1.0e-9)
    }

    private fun body(id: String, positionM: Vector3d, kind: RenderBodyKind): RenderBody =
        RenderBody(
            id = id,
            name = id,
            positionM = positionM,
            radiusM = 1_000.0,
            colorArgb = 0,
            kind = kind,
            isMassive = true,
        )
}
