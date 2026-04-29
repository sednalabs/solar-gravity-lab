package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitCameraMathTest {

    @Test
    fun focusPlanePointReturnsCameraCenterAtViewportCenter() {
        val camera = CameraState(
            centerM = Vector3d(12.0, -30.0, 4.0),
            viewRadiusM = 200.0,
        )

        val point = OrbitCameraMath.focusPlanePoint(
            screenXPx = 500f,
            screenYPx = 400f,
            cameraState = camera,
            viewportWidthPx = 1000,
            viewportHeightPx = 800,
        )

        assertVectorEquals(camera.centerM, point)
    }

    @Test
    fun anchorCorrectionKeepsOffCenterWorldPointUnderPinchFocus() {
        val viewportWidth = 1000
        val viewportHeight = 800
        val focusX = 720f
        val focusY = 330f
        val before = CameraState(
            centerM = Vector3d(1_000.0, 2_000.0, 0.0),
            viewRadiusM = 500.0,
        )
        val anchor = OrbitCameraMath.focusPlanePoint(
            screenXPx = focusX,
            screenYPx = focusY,
            cameraState = before,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        val zoomed = before.copy(viewRadiusM = before.viewRadiusM / 1.8)
        val anchorAfterZoom = OrbitCameraMath.focusPlanePoint(
            screenXPx = focusX,
            screenYPx = focusY,
            cameraState = zoomed,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        val corrected = zoomed.copy(centerM = zoomed.centerM + (anchor - anchorAfterZoom))

        val correctedAnchor = OrbitCameraMath.focusPlanePoint(
            screenXPx = focusX,
            screenYPx = focusY,
            cameraState = corrected,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )

        assertVectorEquals(anchor, correctedAnchor)
    }

    private fun assertVectorEquals(
        expected: Vector3d,
        actual: Vector3d,
        tolerance: Double = 1e-9,
    ) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }
}
