package com.graciousgazelles.solarlab.render.core

import kotlin.math.abs
import kotlin.math.hypot

data class CameraGesturePointer(
    val id: Int,
    val xPx: Float,
    val yPx: Float,
)

sealed interface CameraGestureUpdate {
    data class Orbit(
        val deltaXPx: Float,
        val deltaYPx: Float,
        val focusXPx: Float,
        val focusYPx: Float,
    ) : CameraGestureUpdate

    data class PanAndZoom(
        val distanceXPx: Float,
        val distanceYPx: Float,
        val scaleFactor: Float,
        val focusXPx: Float,
        val focusYPx: Float,
        val detachFollow: Boolean,
    ) : CameraGestureUpdate
}

/**
 * Owns stage gesture arbitration independently of Android recognizer timing.
 *
 * A one-pointer stream can only orbit. Once a second pointer arrives, the
 * stream can only pan and zoom until every pointer is lifted. That suppression
 * prevents a lifted pinch finger from turning into an accidental orbit.
 */
class CameraGestureStateMachine(
    private val touchSlopPx: Float,
) {
    private sealed interface State {
        data object Idle : State

        data class OnePointer(
            val pointerId: Int,
            val downXPx: Float,
            val downYPx: Float,
            val lastXPx: Float,
            val lastYPx: Float,
            val orbitActive: Boolean,
        ) : State

        data class TwoPointers(
            val firstPointerId: Int,
            val secondPointerId: Int,
            val initialCentroidXPx: Float,
            val initialCentroidYPx: Float,
            val lastCentroidXPx: Float,
            val lastCentroidYPx: Float,
            val lastSpanPx: Float,
            val panActive: Boolean,
            val followDetached: Boolean,
        ) : State

        data object SuppressedUntilAllPointersUp : State
    }

    private var state: State = State.Idle

    val isTransforming: Boolean
        get() = state is State.TwoPointers

    val acceptsTap: Boolean
        get() = state is State.OnePointer

    fun onDown(pointer: CameraGesturePointer) {
        state = State.OnePointer(
            pointerId = pointer.id,
            downXPx = pointer.xPx,
            downYPx = pointer.yPx,
            lastXPx = pointer.xPx,
            lastYPx = pointer.yPx,
            orbitActive = false,
        )
    }

    /** Returns true when the second pointer atomically takes gesture ownership. */
    fun onPointerDown(pointers: List<CameraGesturePointer>): Boolean {
        if (pointers.size < 2) return false
        val first = pointers[0]
        val second = pointers[1]
        val centroidX = (first.xPx + second.xPx) * 0.5f
        val centroidY = (first.yPx + second.yPx) * 0.5f
        state = State.TwoPointers(
            firstPointerId = first.id,
            secondPointerId = second.id,
            initialCentroidXPx = centroidX,
            initialCentroidYPx = centroidY,
            lastCentroidXPx = centroidX,
            lastCentroidYPx = centroidY,
            lastSpanPx = pointerSpan(first, second),
            panActive = false,
            followDetached = false,
        )
        return true
    }

    fun onMove(
        pointers: List<CameraGesturePointer>,
        followActive: Boolean,
    ): CameraGestureUpdate? = when (val current = state) {
        State.Idle,
        State.SuppressedUntilAllPointersUp,
        -> null

        is State.OnePointer -> moveOnePointer(current, pointers)
        is State.TwoPointers -> moveTwoPointers(current, pointers, followActive)
    }

    fun onPointerUp() {
        state = State.SuppressedUntilAllPointersUp
    }

    fun onUp() {
        state = State.Idle
    }

    fun onCancel() {
        state = State.Idle
    }

    private fun moveOnePointer(
        current: State.OnePointer,
        pointers: List<CameraGesturePointer>,
    ): CameraGestureUpdate? {
        val pointer = pointers.firstOrNull { it.id == current.pointerId } ?: return null
        val deltaX = pointer.xPx - current.lastXPx
        val deltaY = pointer.yPx - current.lastYPx
        val orbitActive = current.orbitActive || hypot(
            pointer.xPx - current.downXPx,
            pointer.yPx - current.downYPx,
        ) >= touchSlopPx
        state = current.copy(
            lastXPx = pointer.xPx,
            lastYPx = pointer.yPx,
            orbitActive = orbitActive,
        )
        if (!orbitActive || (abs(deltaX) < MOTION_EPSILON_PX && abs(deltaY) < MOTION_EPSILON_PX)) {
            return null
        }
        return CameraGestureUpdate.Orbit(
            deltaXPx = deltaX,
            deltaYPx = deltaY,
            focusXPx = pointer.xPx,
            focusYPx = pointer.yPx,
        )
    }

    private fun moveTwoPointers(
        current: State.TwoPointers,
        pointers: List<CameraGesturePointer>,
        followActive: Boolean,
    ): CameraGestureUpdate? {
        val first = pointers.firstOrNull { it.id == current.firstPointerId }
        val second = pointers.firstOrNull { it.id == current.secondPointerId }
        if (first == null || second == null) {
            state = State.SuppressedUntilAllPointersUp
            return null
        }

        val centroidX = (first.xPx + second.xPx) * 0.5f
        val centroidY = (first.yPx + second.yPx) * 0.5f
        val span = pointerSpan(first, second)
        val cumulativePan = hypot(
            centroidX - current.initialCentroidXPx,
            centroidY - current.initialCentroidYPx,
        )
        val panActive = current.panActive || cumulativePan >= touchSlopPx
        val detachFollow = followActive && panActive && !current.followDetached
        val followDetached = current.followDetached || detachFollow
        val scaleFactor = if (current.lastSpanPx > MOTION_EPSILON_PX) {
            (span / current.lastSpanPx).takeIf { it.isFinite() } ?: 1f
        } else {
            1f
        }
        val distanceX = if (panActive) current.lastCentroidXPx - centroidX else 0f
        val distanceY = if (panActive) current.lastCentroidYPx - centroidY else 0f
        val effectiveScaleFactor = if (abs(scaleFactor - 1f) >= SCALE_EPSILON) scaleFactor else 1f
        state = current.copy(
            lastCentroidXPx = centroidX,
            lastCentroidYPx = centroidY,
            lastSpanPx = span,
            panActive = panActive,
            followDetached = followDetached,
        )
        if (
            abs(distanceX) < MOTION_EPSILON_PX &&
            abs(distanceY) < MOTION_EPSILON_PX &&
            effectiveScaleFactor == 1f
        ) {
            return null
        }
        return CameraGestureUpdate.PanAndZoom(
            distanceXPx = distanceX,
            distanceYPx = distanceY,
            scaleFactor = effectiveScaleFactor,
            focusXPx = centroidX,
            focusYPx = centroidY,
            detachFollow = detachFollow,
        )
    }

    private fun pointerSpan(
        first: CameraGesturePointer,
        second: CameraGesturePointer,
    ): Float = hypot(second.xPx - first.xPx, second.yPx - first.yPx)

    private companion object {
        private const val MOTION_EPSILON_PX = 0.1f
        private const val SCALE_EPSILON = 0.001f
    }
}
