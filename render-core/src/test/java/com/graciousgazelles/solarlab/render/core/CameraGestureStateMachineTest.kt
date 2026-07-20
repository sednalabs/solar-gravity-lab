package com.graciousgazelles.solarlab.render.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraGestureStateMachineTest {
    @Test
    fun onePointerCanOnlyOrbitAfterTouchSlop() {
        val gestures = CameraGestureStateMachine(touchSlopPx = 8f)
        gestures.onDown(pointer(0, 100f, 100f))

        assertNull(gestures.onMove(listOf(pointer(0, 105f, 102f)), followActive = true))
        val update = gestures.onMove(
            listOf(pointer(0, 114f, 106f)),
            followActive = true,
        ) as CameraGestureUpdate.Orbit

        assertEquals(9f, update.deltaXPx)
        assertEquals(4f, update.deltaYPx)
        assertEquals(114f, update.focusXPx)
        assertFalse(gestures.isTransforming)
    }

    @Test
    fun secondPointerAtomicallyTransfersOwnershipToPanAndZoom() {
        val gestures = CameraGestureStateMachine(touchSlopPx = 4f)
        gestures.onDown(pointer(0, 100f, 100f))
        assertTrue(
            gestures.onPointerDown(
                listOf(
                    pointer(0, 100f, 100f),
                    pointer(1, 200f, 100f),
                ),
            ),
        )

        val update = gestures.onMove(
            pointers = listOf(
                pointer(0, 90f, 110f),
                pointer(1, 230f, 110f),
            ),
            followActive = false,
        ) as CameraGestureUpdate.PanAndZoom

        assertEquals(-10f, update.distanceXPx)
        assertEquals(-10f, update.distanceYPx)
        assertEquals(1.4f, update.scaleFactor, 0.0001f)
        assertFalse(update.detachFollow)
        assertTrue(gestures.isTransforming)
    }

    @Test
    fun pinchRetainsFollowWhilePanDetachesOnlyAfterSlop() {
        val gestures = CameraGestureStateMachine(touchSlopPx = 8f)
        gestures.onDown(pointer(0, 100f, 100f))
        gestures.onPointerDown(
            listOf(
                pointer(0, 100f, 100f),
                pointer(1, 200f, 100f),
            ),
        )

        val pinch = gestures.onMove(
            pointers = listOf(
                pointer(0, 95f, 100f),
                pointer(1, 205f, 100f),
            ),
            followActive = true,
        ) as CameraGestureUpdate.PanAndZoom
        assertEquals(0f, pinch.distanceXPx)
        assertEquals(1.1f, pinch.scaleFactor, 0.0001f)
        assertFalse(pinch.detachFollow)

        val smallPan = gestures.onMove(
            pointers = listOf(
                pointer(0, 99f, 103f),
                pointer(1, 209f, 103f),
            ),
            followActive = true,
        )
        assertNull(smallPan)

        val detachingPan = gestures.onMove(
            pointers = listOf(
                pointer(0, 108f, 110f),
                pointer(1, 218f, 110f),
            ),
            followActive = true,
        ) as CameraGestureUpdate.PanAndZoom
        assertTrue(detachingPan.detachFollow)
    }

    @Test
    fun liftingOneTransformPointerSuppressesOrbitUntilAllPointersAreUp() {
        val gestures = CameraGestureStateMachine(touchSlopPx = 4f)
        gestures.onDown(pointer(0, 10f, 10f))
        gestures.onPointerDown(listOf(pointer(0, 10f, 10f), pointer(1, 30f, 10f)))
        gestures.onPointerUp()

        assertNull(
            gestures.onMove(
                pointers = listOf(pointer(0, 50f, 50f)),
                followActive = false,
            ),
        )
        gestures.onUp()
        gestures.onDown(pointer(0, 50f, 50f))
        assertFalse(gestures.isTransforming)
    }

    private fun pointer(id: Int, x: Float, y: Float): CameraGesturePointer =
        CameraGesturePointer(id = id, xPx = x, yPx = y)
}
