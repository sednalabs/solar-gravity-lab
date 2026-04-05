package com.sednalabs.solarlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.sednalabs.solarlab.runtime.RenderBody
import com.sednalabs.solarlab.runtime.RenderFrame
import com.sednalabs.solarlab.runtime.RenderTrail
import com.sednalabs.solarlab.runtime.RenderTracer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class VulkanPacketRenderSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback2 {
    private var latestFrame: RenderFrame? = null
    private var surfaceReady: Boolean = false

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(10, 13, 20)
        style = Paint.Style.FILL
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val selectedBodyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 239, 128)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val tracerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        holder.addCallback(this)
        setWillNotDraw(false)
    }

    fun submitFrame(frame: RenderFrame?) {
        latestFrame = frame
        drawNow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        drawNow()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        drawNow()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        drawNow()
    }

    private fun drawNow() {
        if (!surfaceReady) return
        val canvas = holder.lockCanvas() ?: return
        try {
            drawBackground(canvas)
            val frame = latestFrame ?: return
            drawFrame(canvas, frame)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
    }

    private fun drawFrame(canvas: Canvas, frame: RenderFrame) {
        val viewportWidth = width.toFloat().coerceAtLeast(1f)
        val viewportHeight = height.toFloat().coerceAtLeast(1f)
        val extent = computeExtent(frame)
        val halfWorldSpan = extent.halfWorldSpan.coerceAtLeast(1f)
        val scale = 0.44f * min(viewportWidth, viewportHeight) / halfWorldSpan

        frame.trails.forEach { trail ->
            drawTrail(canvas, trail, extent.centerX, extent.centerY, scale, viewportWidth, viewportHeight)
        }
        frame.tracers.forEach { tracer ->
            drawTracer(canvas, tracer, extent.centerX, extent.centerY, scale, viewportWidth, viewportHeight)
        }
        frame.bodies.forEach { body ->
            drawBody(canvas, body, extent.centerX, extent.centerY, scale, viewportWidth, viewportHeight, halfWorldSpan)
        }
    }

    private fun drawBody(
        canvas: Canvas,
        body: RenderBody,
        centerX: Float,
        centerY: Float,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        halfWorldSpan: Float,
    ) {
        val sx = viewportWidth * 0.5f + ((body.x - centerX) * scale)
        val sy = viewportHeight * 0.5f - ((body.y - centerY) * scale)
        val radiusPx = (body.radiusM / halfWorldSpan * min(viewportWidth, viewportHeight) * 0.5f)
            .coerceIn(2.5f, 20f)
        bodyPaint.color = Color.argb(
            (body.colorA.coerceIn(0f, 1f) * 255f).toInt(),
            (body.colorR.coerceIn(0f, 1f) * 255f).toInt(),
            (body.colorG.coerceIn(0f, 1f) * 255f).toInt(),
            (body.colorB.coerceIn(0f, 1f) * 255f).toInt(),
        )
        canvas.drawCircle(sx, sy, radiusPx, bodyPaint)
        if (body.selected) {
            canvas.drawCircle(sx, sy, radiusPx + 2.5f, selectedBodyStroke)
        }
    }

    private fun drawTracer(
        canvas: Canvas,
        tracer: RenderTracer,
        centerX: Float,
        centerY: Float,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        val sx = viewportWidth * 0.5f + ((tracer.x - centerX) * scale)
        val sy = viewportHeight * 0.5f - ((tracer.y - centerY) * scale)
        val radiusPx = tracer.sizePx.coerceIn(0.8f, 3.5f)
        tracerPaint.color = Color.argb(
            (tracer.colorA.coerceIn(0f, 1f) * 255f).toInt(),
            (tracer.colorR.coerceIn(0f, 1f) * 255f).toInt(),
            (tracer.colorG.coerceIn(0f, 1f) * 255f).toInt(),
            (tracer.colorB.coerceIn(0f, 1f) * 255f).toInt(),
        )
        canvas.drawCircle(sx, sy, radiusPx, tracerPaint)
    }

    private fun drawTrail(
        canvas: Canvas,
        trail: RenderTrail,
        centerX: Float,
        centerY: Float,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        if (trail.points.size < 2) return
        val path = Path()
        trail.points.forEachIndexed { index, point ->
            val sx = viewportWidth * 0.5f + ((point.x - centerX) * scale)
            val sy = viewportHeight * 0.5f - ((point.y - centerY) * scale)
            if (index == 0) {
                path.moveTo(sx, sy)
            } else {
                path.lineTo(sx, sy)
            }
        }
        val alphaScale = if (trail.headHighlighted) 1f else 0.65f
        trailPaint.color = Color.argb(
            (trail.colorA.coerceIn(0f, 1f) * alphaScale * 255f).toInt(),
            (trail.colorR.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorG.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorB.coerceIn(0f, 1f) * 255f).toInt(),
        )
        trailPaint.strokeWidth = if (trail.headHighlighted) 2.2f else 1.4f
        canvas.drawPath(path, trailPaint)
    }

    private fun computeExtent(frame: RenderFrame): Extent {
        var centerX = 0f
        var centerY = 0f
        var count = 0

        frame.bodies.forEach { body ->
            centerX += body.x
            centerY += body.y
            count++
        }
        frame.tracers.forEach { tracer ->
            centerX += tracer.x
            centerY += tracer.y
            count++
        }
        if (count > 0) {
            centerX /= count.toFloat()
            centerY /= count.toFloat()
        }

        var maxDistance = 1f
        frame.bodies.forEach { body ->
            maxDistance = max(maxDistance, xyDistance(body.x, body.y, centerX, centerY))
        }
        frame.tracers.forEach { tracer ->
            maxDistance = max(maxDistance, xyDistance(tracer.x, tracer.y, centerX, centerY))
        }
        frame.trails.forEach { trail ->
            trail.points.forEach { point ->
                maxDistance = max(maxDistance, xyDistance(point.x, point.y, centerX, centerY))
            }
        }

        val padded = maxDistance * 1.2f
        return Extent(
            centerX = centerX,
            centerY = centerY,
            halfWorldSpan = if (abs(padded) < 1f) 1f else padded,
        )
    }

    private fun xyDistance(x: Float, y: Float, cx: Float, cy: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return sqrt(dx * dx + dy * dy)
    }

    private data class Extent(
        val centerX: Float,
        val centerY: Float,
        val halfWorldSpan: Float,
    )
}
