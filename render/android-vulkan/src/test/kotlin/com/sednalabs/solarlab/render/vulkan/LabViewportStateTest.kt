package com.sednalabs.solarlab.render.vulkan

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.render.core.CameraState
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LabViewportStateTest {
    @Test
    fun nativeProjectionFixturesMatchInPortraitAndLandscape() {
        // These known coordinates are also asserted by SolarLabPresentationTest.cpp.
        val point = Vector3d(200.0, -100.0, 400.0)
        val portrait = viewport(600, 1200, CameraState(viewRadiusM = 400.0,
            yawRadians = 0.0, pitchRadians = PI / 2.0))
        val projected = portrait.project(point)
        assertEquals(450f, projected.xPx, 0.0001f)
        assertEquals(675f, projected.yPx, 0.0001f)
        assertEquals(-400.0, projected.depthM, 1e-8)
        val landscape = viewport(1200, 600, portrait.camera).project(point)
        assertEquals(750f, landscape.xPx, 0.0001f)
        assertEquals(375f, landscape.yPx, 0.0001f)

        val inclined = viewport(600, 1200, portrait.camera.copy(yawRadians = PI / 2.0,
            pitchRadians = PI / 6.0)).project(point)
        assertEquals(225f, inclined.xPx, 0.0001f)
        assertEquals(415.19238f, inclined.yPx, 0.0001f)
        assertEquals(-373.2050807568877, inclined.depthM, 1e-8)
    }

    @Test
    fun declaredInclinedPlaneRoundTripsAtBarycentricEarthCoordinates() {
        val origin = Vector3d(-26e9, 132e9, 57e9)
        val normal = Vector3d(1.0, 2.0, 3.0).normalized()
        val tangent = Vector3d(2.0, -1.0, 0.0).normalized()
        val plane = LabPlane(origin, normal)
        val camera = CameraState(centerM = origin + tangent * 1000.0, viewRadiusM = 8e6,
            yawRadians = atan2(normal.x, -normal.y), pitchRadians = asin(normal.z))
        for ((width, height) in listOf(1080 to 1800, 1800 to 1080)) {
            val view = viewport(width, height, camera)
            for ((x, y) in listOf(100f to 200f, 539.5f to 719.5f, 920f to 950f)) {
                val world = view.unprojectToPlane(x, y, plane)
                assertNotNull(world)
                assertEquals(0.0, (world!! - origin).dot(normal), 0.001)
                val projected = view.project(world)
                assertEquals(x, projected.xPx, 0.001f)
                assertEquals(y, projected.yPx, 0.001f)
            }
        }
    }

    @Test
    fun ambiguousOrInvalidPlacementDoesNotInventAWorldPoint() {
        val view = viewport(600, 1200, CameraState(viewRadiusM = 400.0,
            yawRadians = 0.0, pitchRadians = PI / 2.0))
        assertNull(view.unprojectToPlane(300f, 600f, LabPlane(Vector3d.ZERO, Vector3d(1.0, 0.0, 0.0))))
        assertNull(view.unprojectToPlane(300f, 600f, LabPlane(Vector3d.ZERO, Vector3d.ZERO)))
        assertNull(view.unprojectToPlane(Float.NaN, 600f, LabPlane.XY))
    }

    private fun viewport(width: Int, height: Int, camera: CameraState) = LabViewportState(
        widthPx = width, heightPx = height, camera = camera,
        metresPerPixel = 2.0 * camera.viewRadiusM / minOf(width, height), bodies = emptyList(),
    )
}
