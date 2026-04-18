package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiscaleOrbitCameraControllerTest {

    @Test
    fun zoomAroundViewportPointKeepsAnchorProjectedUnderFinger() {
        val camera = CameraState(
            centerM = Vector3d(0.3 * PhysicalConstants.ASTRONOMICAL_UNIT_M, -0.1 * PhysicalConstants.ASTRONOMICAL_UNIT_M, 0.0),
            viewRadiusM = 3.2 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
        )
        val viewportWidthPx = 1920
        val viewportHeightPx = 1080
        val focusXPx = 1480f
        val focusYPx = 420f
        val anchor = OrbitCameraMath.focusPlanePoint(
            screenXPx = focusXPx,
            screenYPx = focusYPx,
            cameraState = camera,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )

        val zoomed = MultiscaleOrbitCameraController.zoomAroundViewportPoint(
            cameraState = camera,
            scaleFactor = 2.1f,
            focusXPx = focusXPx,
            focusYPx = focusYPx,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            minViewRadiusM = 0.001 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
            maxViewRadiusM = 150_000.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
        )

        val projected = requireNotNull(
            OrbitCameraMath.projectToViewport(
                positionM = anchor,
                cameraState = zoomed,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
            ),
        )
        assertEquals(focusXPx.toDouble(), projected.xPx, 1.0)
        assertEquals(focusYPx.toDouble(), projected.yPx, 1.0)
        assertTrue(zoomed.viewRadiusM < camera.viewRadiusM)
    }

    @Test
    fun orbitAroundViewportPointKeepsAnchorProjectedUnderFinger() {
        val camera = CameraState(
            centerM = Vector3d(0.0, 0.0, 0.2 * PhysicalConstants.ASTRONOMICAL_UNIT_M),
            viewRadiusM = 0.18 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
        )
        val viewportWidthPx = 1440
        val viewportHeightPx = 1440
        val focusXPx = 980f
        val focusYPx = 560f
        val anchor = OrbitCameraMath.focusPlanePoint(
            screenXPx = focusXPx,
            screenYPx = focusYPx,
            cameraState = camera,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )

        val orbited = MultiscaleOrbitCameraController.orbitAroundViewportPoint(
            cameraState = camera,
            deltaXPx = 48f,
            deltaYPx = -26f,
            focusXPx = focusXPx,
            focusYPx = focusYPx,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )

        val projected = requireNotNull(
            OrbitCameraMath.projectToViewport(
                positionM = anchor,
                cameraState = orbited,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
            ),
        )
        assertEquals(focusXPx.toDouble(), projected.xPx, 1.5)
        assertEquals(focusYPx.toDouble(), projected.yPx, 1.5)
    }

    @Test
    fun closeScaleBandUsesGentlerZoomResponseThanDeepScaleBand() {
        val closeCamera = CameraState(viewRadiusM = 0.010 * PhysicalConstants.ASTRONOMICAL_UNIT_M)
        val deepCamera = CameraState(viewRadiusM = 320.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M)

        val closeZoomed = MultiscaleOrbitCameraController.zoomAroundViewportPoint(
            cameraState = closeCamera,
            scaleFactor = 2.0f,
            focusXPx = 600f,
            focusYPx = 400f,
            viewportWidthPx = 1200,
            viewportHeightPx = 800,
            minViewRadiusM = 0.001 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
            maxViewRadiusM = 150_000.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
        )
        val deepZoomed = MultiscaleOrbitCameraController.zoomAroundViewportPoint(
            cameraState = deepCamera,
            scaleFactor = 2.0f,
            focusXPx = 600f,
            focusYPx = 400f,
            viewportWidthPx = 1200,
            viewportHeightPx = 800,
            minViewRadiusM = 0.001 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
            maxViewRadiusM = 150_000.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
        )

        val closeRatio = closeZoomed.viewRadiusM / closeCamera.viewRadiusM
        val deepRatio = deepZoomed.viewRadiusM / deepCamera.viewRadiusM
        assertTrue("close=$closeRatio deep=$deepRatio", closeRatio > deepRatio)
    }

    @Test
    fun retargetSnapAppliesBandAwarePitch() {
        val targetRadius = 0.010 * PhysicalConstants.ASTRONOMICAL_UNIT_M
        val retargeted = MultiscaleOrbitCameraController.retarget(
            currentCameraState = CameraState(),
            targetCenterM = Vector3d(1.0, 2.0, 3.0),
            suggestedViewRadiusM = targetRadius,
            minViewRadiusM = 0.001 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
            maxViewRadiusM = 150_000.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
            snapToSuggestedRadius = true,
        )

        assertEquals(targetRadius, retargeted.viewRadiusM, 0.0)
        assertEquals(Vector3d(1.0, 2.0, 3.0), retargeted.centerM)
        assertEquals(
            MultiscaleOrbitCameraController.preferredPitchRadiansFor(targetRadius),
            retargeted.pitchRadians,
            1.0e-9,
        )
    }
}
