package com.graciousgazelles.solarlab.feature.lab.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.feature.lab.render.RenderInteractionListener
import com.graciousgazelles.solarlab.feature.lab.render.SceneInteractionMode
import com.graciousgazelles.solarlab.feature.lab.render.SolarRenderSurface
import com.graciousgazelles.solarlab.render.core.ObserverCameraResolver
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame
import com.graciousgazelles.solarlab.render.core.SceneInteractionMath
import kotlin.math.sqrt

class SolarSystemGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), SolarRenderSurface {

    private val solarRenderer = SolarSystemRenderer()

    private var interactionListener: RenderInteractionListener? = null
    private var interactionMode: SceneInteractionMode = SceneInteractionMode.NAVIGATE_AND_SELECT
    private var latestScene: RenderSceneFrame = emptyScene()
    private var selectedBodyId: String? = null
    private var observerMode: ObserverMode = ObserverMode.FREE
    private var placementStartScreen: Pair<Float, Float>? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (interactionMode == SceneInteractionMode.PLACE_BODY) {
                    return false
                }
                solarRenderer.zoomByScale(detector.scaleFactor)
                requestRender()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (interactionMode != SceneInteractionMode.NAVIGATE_AND_SELECT) return false
                val camera = solarRenderer.cameraState()
                val bodyId = SceneInteractionMath.pickBodyIdAtScreenPoint(
                    frame = latestScene,
                    cameraState = camera,
                    viewportWidthPx = width.coerceAtLeast(1),
                    viewportHeightPx = height.coerceAtLeast(1),
                    screenXPx = e.x,
                    screenYPx = e.y,
                )
                interactionListener?.onBodySelectionChanged(bodyId)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (interactionMode != SceneInteractionMode.NAVIGATE_AND_SELECT) return false
                if (ObserverCameraResolver.isCameraLocked(latestScene, selectedBodyId, observerMode)) return false
                solarRenderer.panByPixels(distanceX = distanceX, distanceY = distanceY)
                requestRender()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (interactionMode != SceneInteractionMode.NAVIGATE_AND_SELECT) return false
                solarRenderer.resetCamera()
                requestRender()
                return true
            }
        },
    )

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(solarRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun submitScene(frame: RenderSceneFrame) {
        latestScene = frame
        solarRenderer.submitScene(frame)
        requestRender()
    }

    override fun resetCamera() {
        solarRenderer.resetCamera()
        requestRender()
    }

    override fun setInteractionListener(listener: RenderInteractionListener?) {
        interactionListener = listener
    }

    override fun setInteractionMode(mode: SceneInteractionMode) {
        interactionMode = mode
        placementStartScreen = null
    }

    override fun setSelectedBodyId(bodyId: String?) {
        selectedBodyId = bodyId
        solarRenderer.setSelectedBodyId(bodyId)
        requestRender()
    }

    override fun setObserverMode(mode: ObserverMode) {
        observerMode = mode
        solarRenderer.setObserverMode(mode)
        requestRender()
    }

    override fun onHostResume() {
        onResume()
    }

    override fun onHostPause() {
        onPause()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (interactionMode == SceneInteractionMode.PLACE_BODY) {
            return handlePlacementTouch(event)
        }

        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        return scaled || gestured || super.onTouchEvent(event)
    }

    private fun handlePlacementTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                placementStartScreen = event.x to event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val start = placementStartScreen ?: (event.x to event.y)
                placementStartScreen = null
                val camera = solarRenderer.cameraState()
                val startWorld = screenToWorld(start, camera)
                val endWorld = screenToWorld(event.x to event.y, camera)
                val dx = event.x - start.first
                val dy = event.y - start.second
                interactionListener?.onPlacementGesture(startWorld, endWorld, sqrt((dx * dx) + (dy * dy)))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                placementStartScreen = null
                return true
            }
        }
        return true
    }

    private fun screenToWorld(screen: Pair<Float, Float>, cameraState: com.graciousgazelles.solarlab.render.core.CameraState): Vector3d =
        SceneInteractionMath.screenToWorldPoint(
            screenXPx = screen.first,
            screenYPx = screen.second,
            cameraState = cameraState,
            viewportWidthPx = width.coerceAtLeast(1),
            viewportHeightPx = height.coerceAtLeast(1),
            worldZ = 0.0,
        )

    private fun emptyScene(): RenderSceneFrame = RenderSceneFrame(
        epochSeconds = 0.0,
        authoritativeBodies = emptyList(),
        tracerBodies = emptyList(),
        trails = emptyList(),
    )
}
