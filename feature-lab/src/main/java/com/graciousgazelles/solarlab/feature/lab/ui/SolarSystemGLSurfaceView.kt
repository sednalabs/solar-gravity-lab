package com.graciousgazelles.solarlab.feature.lab.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.graciousgazelles.solarlab.feature.lab.render.SolarRenderSurface
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame

class SolarSystemGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), SolarRenderSurface {

    private val solarRenderer = SolarSystemRenderer()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
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

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                solarRenderer.panByPixels(distanceX = distanceX, distanceY = distanceY)
                requestRender()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
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
        solarRenderer.submitScene(frame)
        requestRender()
    }

    override fun resetCamera() {
        solarRenderer.resetCamera()
        requestRender()
    }

    override fun onHostResume() {
        onResume()
    }

    override fun onHostPause() {
        onPause()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        return scaled || gestured || super.onTouchEvent(event)
    }
}
