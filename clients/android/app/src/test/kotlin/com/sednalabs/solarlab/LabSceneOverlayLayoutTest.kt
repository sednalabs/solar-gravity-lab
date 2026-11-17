package com.sednalabs.solarlab

import androidx.compose.ui.geometry.Rect
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.render.core.CameraState
import com.sednalabs.solarlab.render.vulkan.LabBodyAnchor
import com.sednalabs.solarlab.render.vulkan.LabProjectedPoint
import com.sednalabs.solarlab.render.vulkan.LabViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabSceneOverlayLayoutTest {
    @Test
    fun closeLabelsStayOutsideTheGlobeAndClearOfZoomInBothOrientations() {
        for ((width, height, radius) in listOf(Triple(400, 600, 110f), Triple(900, 240, 90f))) {
            val earth = anchor("earth", width / 2f, height / 2f, radius)
            val state = viewport(width, height, listOf(earth))
            val label = labLabels(state, mapOf("earth" to "Earth"), "earth", 1f).single()
            assertTrue(label.bounds.width >= 48f && label.bounds.height >= 48f)
            assertTrue(label.bounds.left >= 0f && label.bounds.top >= 0f && label.bounds.bottom <= height)
            assertTrue("The label must not cover the permanent zoom rail", label.bounds.right <= width - 72f)
            val dx = earth.xPx - earth.xPx.coerceIn(label.bounds.left, label.bounds.right)
            val dy = earth.yPx - earth.yPx.coerceIn(label.bounds.top, label.bounds.bottom)
            assertTrue("A close planet remains visible beneath its identity", dx * dx + dy * dy >= radius * radius)
        }
    }

    @Test
    fun nearbySystemScaleBodiesHaveSeparateGenerousTargets() {
        val state = viewport(400, 600, listOf(
            anchor("earth", 190f, 220f, 3f),
            anchor("moon", 205f, 230f, 1f),
            anchor("probe-1", 200f, 227f, 0.1f),
        ))
        val labels = labLabels(state, mapOf("earth" to "Earth", "moon" to "Moon", "probe-1" to "Probe 1"), "probe-1", 1f)
        assertEquals(3, labels.size)
        for (label in labels) {
            assertTrue(label.bounds.width >= 48f && label.bounds.height >= 48f)
            assertTrue(labels.none { it !== label && it.bounds.overlaps(label.bounds) })
        }
    }

    @Test
    fun selectedOffscreenBodyKeepsAReachableLocator() {
        val state = viewport(400, 600, listOf(anchor("moon", 900f, -100f, 2f)))
        val label = labLabels(state, mapOf("moon" to "Moon"), "moon", 1f).single()
        assertTrue(label.locator)
        assertTrue(label.bounds.left >= 0 && label.bounds.top >= 0 && label.bounds.right <= 328f && label.bounds.bottom <= 600f)
    }

    @Test
    fun draftCaptionAvoidsThePredictedPathAndVelocityHandleWhenSpaceExists() {
        val state = viewport(400, 500, emptyList())
        val origin = LabProjectedPoint(200f, 200f, 0.0)
        val velocity = LabProjectedPoint(250f, 160f, 0.0)
        val path = (0..20).map { LabProjectedPoint(it * 20f, 240f, 0.0) }
        val caption = labDraftCaptionBounds(state, origin, velocity, path, 120f, 28f, 1f)
        assertTrue("The label should not cross the horizontal predicted orbit", caption.bottom < 240f || caption.top > 240f)
        assertFalse("The velocity drag area remains unobstructed", caption.overlaps(Rect(226f, 136f, 274f, 184f)))
        assertFalse("The draft marker remains unobstructed", caption.overlaps(Rect(181f, 181f, 219f, 219f)))
    }

    private fun anchor(id: String, x: Float, y: Float, radius: Float) =
        LabBodyAnchor(id, x, y, radius, 0.0, false, Vector3d.ZERO)

    private fun viewport(width: Int, height: Int, bodies: List<LabBodyAnchor>) =
        LabViewportState(width, height, CameraState(), 1.0, bodies)
}
