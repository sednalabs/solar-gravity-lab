package com.sednalabs.solarlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.sednalabs.solarlab.runtime.RenderBody
import com.sednalabs.solarlab.runtime.RenderFrame
import com.sednalabs.solarlab.runtime.RenderTrail
import com.sednalabs.solarlab.runtime.RenderTracer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rendering host for decoded packets.
 *
 * This view is Android-only host glue: it paints decoded `RenderFrame` models into a
 * SurfaceView and has no ownership of simulation state.
 */
class VulkanPacketRenderSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback2 {
    companion object {
        private const val TAG = "SolarLabRenderHost"
        private const val MAX_TRACER_POINTS_FOR_EXTENT = 512
        private const val MAX_TRAIL_POINTS_FOR_EXTENT = 1_024
        private const val STARFIELD_POINT_COUNT = 84
        private const val PRIMARY_BODY_EXTENT_CAP = 14
        private const val AUXILIARY_SPAN_TO_BODY_SPAN_CAP = 1.45f
        private const val MIN_USER_SCALE_MULTIPLIER = 0.6f
        private const val MAX_USER_SCALE_MULTIPLIER = 24f
        private const val MIN_TAP_SELECTION_RADIUS_PX = 18f
        private val TEACHING_FRAME_BODY_IDS = setOf(
            "sun",
            "mercury",
            "venus",
            "earth",
            "moon",
            "mars",
        )
    }

    // Frame reference is replaced atomically from Compose callbacks and read on draw.
    private var latestFrame: RenderFrame? = null
    private var highlightedTrailSourceBodyIds: List<String> = emptyList()
    private var surfaceReady: Boolean = false
    private var onBodyTapped: ((String) -> Unit)? = null
    private var activeViewportState: ViewportState? = null
    private var userScaleMultiplier: Float = 1f
    private var userPanX: Float = 0f
    private var userPanY: Float = 0f
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(7, 11, 19)
        style = Paint.Style.FILL
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 110, 168, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bodyGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val trailGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor)
                return true
            }
        },
    )
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val bodyId = activeViewportState
                    ?.nearestBodyHit(e.x, e.y)
                    ?.bodyId
                    ?.takeIf(String::isNotBlank)
                    ?: return false
                onBodyTapped?.invoke(bodyId)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetViewTransform()
                return true
            }
        },
    )

    init {
        holder.addCallback(this)
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    /**
     * Called by Compose on UI updates. The frame is a value snapshot; drawing is then
     * driven by the Surface lifecycle to avoid stale direct canvas calls.
     */
    fun submitFrame(
        frame: RenderFrame?,
        highlightedTrailSourceBodyIds: List<String> = emptyList(),
    ) {
        latestFrame = frame
        if (frame == null) {
            activeViewportState = null
        }
        this.highlightedTrailSourceBodyIds = highlightedTrailSourceBodyIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        drawNow()
    }

    fun setOnBodyTapped(listener: ((String) -> Unit)?) {
        onBodyTapped = listener
    }

    fun resetViewTransform() {
        userScaleMultiplier = 1f
        userPanX = 0f
        userPanY = 0f
        drawNow()
    }

    fun zoomBy(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) {
            return
        }
        userScaleMultiplier = (userScaleMultiplier * scaleFactor)
            .coerceIn(MIN_USER_SCALE_MULTIPLIER, MAX_USER_SCALE_MULTIPLIER)
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
        activeViewportState = null
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        drawNow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleHandled = scaleGestureDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (dx != 0f || dy != 0f) {
                        userPanX += dx
                        userPanY += dy
                        drawNow()
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return scaleHandled || gestureHandled || true
    }

    /**
     * Draw flow is conservative and SurfaceView-owned:
     * - only render while surface is ready
     * - lock/unlock a single canvas per attempt
     * - always draw background first to keep deterministic frame content.
     */
    private fun drawNow() {
        if (!surfaceReady) return
        val canvas = holder.lockCanvas() ?: return
        try {
            drawBackground(canvas)
            val frame = latestFrame ?: return
            drawFrame(canvas, frame)
        } catch (error: Throwable) {
            Log.e(TAG, "Render host draw failed", error)
            latestFrame = null
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawBackground(canvas: Canvas) {
        val viewportWidth = width.toFloat().coerceAtLeast(1f)
        val viewportHeight = height.toFloat().coerceAtLeast(1f)
        backgroundPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            viewportHeight,
            Color.rgb(6, 10, 18),
            Color.rgb(14, 27, 43),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, viewportWidth, viewportHeight, backgroundPaint)

        glowPaint.shader = RadialGradient(
            viewportWidth * 0.5f,
            viewportHeight * 0.5f,
            min(viewportWidth, viewportHeight) * 0.68f,
            intArrayOf(
                Color.argb(84, 73, 132, 214),
                Color.argb(26, 24, 60, 110),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, viewportWidth, viewportHeight, glowPaint)

        val centerX = viewportWidth * 0.5f
        val centerY = viewportHeight * 0.5f

        for (index in 0 until STARFIELD_POINT_COUNT) {
            val normalizedX = pseudoRandomUnit(index * 37 + 11)
            val normalizedY = pseudoRandomUnit(index * 53 + 19)
            val starX = normalizedX * viewportWidth
            val starY = normalizedY * viewportHeight
            val alpha = (44 + (pseudoRandomUnit(index * 97 + 7) * 130f)).toInt().coerceIn(32, 188)
            val radius = (0.65f + pseudoRandomUnit(index * 73 + 3) * 1.55f)
            glowPaint.shader = null
            glowPaint.color = Color.argb(alpha, 222, 234, 255)
            canvas.drawCircle(starX, starY, radius, glowPaint)
        }

        guidePaint.color = Color.argb(30, 147, 183, 255)
        guidePaint.strokeWidth = 1.4f
        repeat(3) { ring ->
            val radiusScale = 0.2f + ring * 0.14f
            canvas.drawCircle(
                centerX,
                centerY,
                min(viewportWidth, viewportHeight) * radiusScale,
                guidePaint,
            )
        }

        glowPaint.shader = RadialGradient(
            centerX,
            centerY,
            min(viewportWidth, viewportHeight) * 0.08f,
            intArrayOf(
                Color.argb(210, 255, 215, 115),
                Color.argb(110, 255, 157, 67),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centerX, centerY, min(viewportWidth, viewportHeight) * 0.09f, glowPaint)

        backgroundPaint.shader = null
        glowPaint.shader = null
    }

    private fun pseudoRandomUnit(seed: Int): Float {
        val hashed = seed * 1_103_515_245 + 12_345
        val value = (hashed ushr 8) and 0x00FF_FFFF
        return value / 0x00FF_FFFF.toFloat()
    }

    // Decode step: convert shared world-space coordinates into screen space, apply safe
    // fallback scale, then draw bodies, tracers, and trail polylines in that order.
    private fun drawFrame(canvas: Canvas, frame: RenderFrame) {
        val viewportWidth = width.toFloat().coerceAtLeast(1f)
        val viewportHeight = height.toFloat().coerceAtLeast(1f)
        val projectionPlane = selectOverheadProjectionPlane(frame)
        val extent = computeExtent(frame, projectionPlane)
        val halfWorldSpan = extent.halfWorldSpan.coerceAtLeast(1f)
        val scale = (0.46f * min(viewportWidth, viewportHeight) / halfWorldSpan) * userScaleMultiplier
        val trailHighlightRanks = highlightedTrailSourceBodyIds
            .withIndex()
            .associate { (index, sourceBodyId) -> sourceBodyId to index }
        val bodyHits = ArrayList<BodyHitTarget>(frame.bodies.size)

        frame.trails.forEach { trail ->
            drawTrail(
                canvas = canvas,
                trail = trail,
                centerX = extent.centerX,
                centerY = extent.centerY,
                projectionPlane = projectionPlane,
                scale = scale,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                highlightRank = trailHighlightRanks[trail.sourceBodyId],
            )
        }
        frame.tracers.forEach { tracer ->
            drawTracer(
                canvas = canvas,
                tracer = tracer,
                centerX = extent.centerX,
                centerY = extent.centerY,
                projectionPlane = projectionPlane,
                scale = scale,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            )
        }
        frame.bodies.forEach { body ->
            drawBody(
                canvas = canvas,
                body = body,
                centerX = extent.centerX,
                centerY = extent.centerY,
                projectionPlane = projectionPlane,
                scale = scale,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                halfWorldSpan = halfWorldSpan,
                bodyHits = bodyHits,
            )
        }

        activeViewportState = ViewportState(
            bodyHits = bodyHits,
        )
    }

    private fun drawBody(
        canvas: Canvas,
        body: RenderBody,
        centerX: Float,
        centerY: Float,
        projectionPlane: ProjectionPlane,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        halfWorldSpan: Float,
        bodyHits: MutableList<BodyHitTarget>,
    ) {
        val projectedY = projectY(body.y, body.z, projectionPlane)
        if (!body.x.isFinite() || !projectedY.isFinite() || !body.radiusM.isFinite()) {
            return
        }
        val sx = screenX(body.x, centerX, scale, viewportWidth)
        val sy = screenY(projectedY, centerY, scale, viewportHeight)
        if (!sx.isFinite() || !sy.isFinite()) {
            return
        }
        val radiusPx = (body.radiusM / halfWorldSpan * min(viewportWidth, viewportHeight) * 0.5f)
            .coerceIn(2.5f, 20f)
        bodyPaint.color = Color.argb(
            (body.colorA.coerceIn(0f, 1f) * 255f).toInt(),
            (body.colorR.coerceIn(0f, 1f) * 255f).toInt(),
            (body.colorG.coerceIn(0f, 1f) * 255f).toInt(),
            (body.colorB.coerceIn(0f, 1f) * 255f).toInt(),
        )
        val glowAlpha = (body.colorA.coerceIn(0f, 1f) * 0.34f * 255f).toInt().coerceIn(0, 255)
        bodyGlowPaint.color = Color.argb(
            glowAlpha,
            (body.colorR.coerceIn(0f, 1f) * 255f).toInt(),
            (body.colorG.coerceIn(0f, 1f) * 255f).toInt(),
            (body.colorB.coerceIn(0f, 1f) * 255f).toInt(),
        )
        canvas.drawCircle(sx, sy, radiusPx * 2.15f, bodyGlowPaint)
        canvas.drawCircle(sx, sy, radiusPx, bodyPaint)
        if (body.selected) {
            canvas.drawCircle(sx, sy, radiusPx + 2.5f, selectedBodyStroke)
        }
        bodyHits += BodyHitTarget(
            bodyId = body.bodyId,
            x = sx,
            y = sy,
            selectionRadiusPx = max(radiusPx * 2.4f, MIN_TAP_SELECTION_RADIUS_PX),
        )
    }

    private fun drawTracer(
        canvas: Canvas,
        tracer: RenderTracer,
        centerX: Float,
        centerY: Float,
        projectionPlane: ProjectionPlane,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        val projectedY = projectY(tracer.y, tracer.z, projectionPlane)
        if (!tracer.x.isFinite() || !projectedY.isFinite() || !tracer.sizePx.isFinite()) {
            return
        }
        val sx = screenX(tracer.x, centerX, scale, viewportWidth)
        val sy = screenY(projectedY, centerY, scale, viewportHeight)
        if (!sx.isFinite() || !sy.isFinite()) {
            return
        }
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
        projectionPlane: ProjectionPlane,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        highlightRank: Int?,
    ) {
        if (trail.points.size < 2) return
        val path = Path()
        var plottedPointCount = 0
        trail.points.forEachIndexed { index, point ->
            val projectedY = projectY(point.y, point.z, projectionPlane)
            if (!point.x.isFinite() || !projectedY.isFinite()) {
                return@forEachIndexed
            }
            val sx = screenX(point.x, centerX, scale, viewportWidth)
            val sy = screenY(projectedY, centerY, scale, viewportHeight)
            if (!sx.isFinite() || !sy.isFinite()) {
                return@forEachIndexed
            }
            if (plottedPointCount == 0) {
                path.moveTo(sx, sy)
            } else {
                path.lineTo(sx, sy)
            }
            plottedPointCount++
        }
        if (plottedPointCount < 2) return
        val rankEmphasis = highlightRank?.let { rank ->
            when (rank) {
                0 -> 1f
                1 -> 0.92f
                2 -> 0.84f
                3 -> 0.78f
                else -> 0.72f
            }
        }
        val alphaScale = when {
            rankEmphasis != null -> rankEmphasis
            trail.headHighlighted -> 0.92f
            else -> 0.55f
        }
        trailPaint.color = Color.argb(
            (trail.colorA.coerceIn(0f, 1f) * alphaScale * 255f).toInt(),
            (trail.colorR.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorG.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorB.coerceIn(0f, 1f) * 255f).toInt(),
        )
        trailPaint.strokeWidth = when {
            highlightRank == 0 -> 3.4f
            highlightRank != null -> 2.6f
            trail.headHighlighted -> 2.1f
            else -> 1.4f
        }
        trailGlowPaint.color = Color.argb(
            (trail.colorA.coerceIn(0f, 1f) * alphaScale * 0.42f * 255f).toInt(),
            (trail.colorR.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorG.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorB.coerceIn(0f, 1f) * 255f).toInt(),
        )
        trailGlowPaint.strokeWidth = trailPaint.strokeWidth * 2.1f
        canvas.drawPath(path, trailGlowPaint)
        canvas.drawPath(path, trailPaint)
    }

    private fun computeExtent(
        frame: RenderFrame,
        projectionPlane: ProjectionPlane,
    ): Extent {
        val projectedBodyPoints = frame.bodies
            .asSequence()
            .mapNotNull { body ->
                projectedPoint(
                    x = body.x,
                    y = projectY(body.y, body.z, projectionPlane),
                )
            }
            .toList()
        val primaryBodyPoints = frame.bodies
            .asSequence()
            .filter { body ->
                body.x.isFinite() &&
                    projectY(body.y, body.z, projectionPlane).isFinite() &&
                    body.radiusM.isFinite()
            }
            .sortedByDescending { body -> body.radiusM }
            .take(PRIMARY_BODY_EXTENT_CAP)
            .mapNotNull { body ->
                projectedPoint(
                    x = body.x,
                    y = projectY(body.y, body.z, projectionPlane),
                )
            }
            .toList()
        val teachingBodyPoints = frame.bodies
            .asSequence()
            .filter { body ->
                body.bodyId.lowercase() in TEACHING_FRAME_BODY_IDS &&
                    body.x.isFinite() &&
                    projectY(body.y, body.z, projectionPlane).isFinite()
            }
            .mapNotNull { body ->
                projectedPoint(
                    x = body.x,
                    y = projectY(body.y, body.z, projectionPlane),
                )
            }
            .toList()
        val projectedTracerPoints = frame.tracers
            .asSequence()
            .mapNotNull { tracer ->
                projectedPoint(
                    x = tracer.x,
                    y = projectY(tracer.y, tracer.z, projectionPlane),
                )
            }
            .toList()
            .sampleUpTo(MAX_TRACER_POINTS_FOR_EXTENT)
        val highlightedSourceBodyIds = highlightedTrailSourceBodyIds.toSet()
        val highlightedTrailPoints = frame.trails
            .asSequence()
            .filter { trail ->
                trail.headHighlighted || highlightedSourceBodyIds.contains(trail.sourceBodyId)
            }
            .flatMap { trail ->
                trail.points.asSequence().mapNotNull { point ->
                    projectedPoint(
                        x = point.x,
                        y = projectY(point.y, point.z, projectionPlane),
                    )
                }
            }
            .toList()
            .sampleUpTo(MAX_TRAIL_POINTS_FOR_EXTENT / 2)
        val projectedTrailPoints = frame.trails
            .asSequence()
            .flatMap { trail ->
                trail.points.asSequence().mapNotNull { point ->
                    projectedPoint(
                        x = point.x,
                        y = projectY(point.y, point.z, projectionPlane),
                    )
                }
            }
            .toList()
            .sampleUpTo(MAX_TRAIL_POINTS_FOR_EXTENT)
        val points = buildList {
            addAll(if (primaryBodyPoints.isNotEmpty()) primaryBodyPoints else projectedBodyPoints)
            addAll(projectedTracerPoints)
            addAll(projectedTrailPoints)
            addAll(highlightedTrailPoints)
        }
        if (points.isEmpty()) {
            return Extent(centerX = 0f, centerY = 0f, halfWorldSpan = 1f)
        }

        val framingBodyPoints = when {
            teachingBodyPoints.size >= 4 -> teachingBodyPoints
            primaryBodyPoints.isNotEmpty() -> primaryBodyPoints
            else -> projectedBodyPoints
        }

        val anchorBody = frame.bodies.firstOrNull { body ->
            body.selected && body.x.isFinite() && projectY(body.y, body.z, projectionPlane).isFinite()
        } ?: frame.bodies.firstOrNull { body ->
            body.bodyId.equals("sun", ignoreCase = true) &&
                body.x.isFinite() &&
                projectY(body.y, body.z, projectionPlane).isFinite()
        } ?: frame.bodies.maxByOrNull { body ->
            if (body.radiusM.isFinite()) body.radiusM else Float.NEGATIVE_INFINITY
        }

        val centerX = anchorBody?.x
            ?: medianOf((if (framingBodyPoints.isNotEmpty()) framingBodyPoints else points).map { it.x })
        val centerY = anchorBody?.let { body -> projectY(body.y, body.z, projectionPlane) }
            ?: medianOf((if (framingBodyPoints.isNotEmpty()) framingBodyPoints else points).map { it.y })

        val bodySortedDistances = framingBodyPoints
            .asSequence()
            .map { xyDistance(it.x, it.y, centerX, centerY) }
            .filter { it.isFinite() }
            .sorted()
            .toList()
        val auxiliarySortedDistances = buildList {
            addAll(projectedTracerPoints)
            addAll(projectedTrailPoints)
            addAll(highlightedTrailPoints)
        }
            .asSequence()
            .map { xyDistance(it.x, it.y, centerX, centerY) }
            .filter { it.isFinite() }
            .sorted()
            .toList()
        val allSortedDistances = points
            .asSequence()
            .map { xyDistance(it.x, it.y, centerX, centerY) }
            .filter { it.isFinite() }
            .sorted()
            .toList()
        if (allSortedDistances.isEmpty()) {
            return Extent(centerX = centerX, centerY = centerY, halfWorldSpan = 1f)
        }

        val bodySpan = if (bodySortedDistances.isNotEmpty()) {
            val bodyPercentile = if (bodySortedDistances.size >= 12) 0.72f else 0.88f
            max(1f, percentile(bodySortedDistances, bodyPercentile) * 1.28f)
        } else {
            max(1f, percentile(allSortedDistances, 0.78f) * 1.22f)
        }
        val auxiliarySpan = if (auxiliarySortedDistances.isNotEmpty()) {
            max(1f, percentile(auxiliarySortedDistances, 0.64f) * 1.14f)
        } else {
            bodySpan
        }
        val halfWorldSpan = auxiliarySpan.coerceIn(
            minimumValue = bodySpan,
            maximumValue = bodySpan * AUXILIARY_SPAN_TO_BODY_SPAN_CAP,
        )
        return Extent(
            centerX = centerX,
            centerY = centerY,
            halfWorldSpan = if (abs(halfWorldSpan) < 1f) 1f else halfWorldSpan,
        )
    }

    private fun selectOverheadProjectionPlane(frame: RenderFrame): ProjectionPlane {
        var ySpread = 0f
        var zSpread = 0f
        var sampleCount = 0
        frame.bodies.forEach { body ->
            if (!body.x.isFinite()) {
                return@forEach
            }
            if (body.y.isFinite()) {
                ySpread += abs(body.y)
            }
            if (body.z.isFinite()) {
                zSpread += abs(body.z)
            }
            sampleCount++
        }
        if (sampleCount == 0) {
            return ProjectionPlane.XZ
        }
        val meanYSpread = ySpread / sampleCount.toFloat()
        val meanZSpread = zSpread / sampleCount.toFloat()
        return if (meanZSpread > meanYSpread * 1.5f) {
            ProjectionPlane.XZ
        } else {
            ProjectionPlane.XY
        }
    }

    private fun projectY(
        y: Float,
        z: Float,
        projectionPlane: ProjectionPlane,
    ): Float {
        return when (projectionPlane) {
            ProjectionPlane.XY -> y
            ProjectionPlane.XZ -> z
        }
    }

    private fun projectedPoint(x: Float, y: Float): ProjectedPoint? {
        if (!x.isFinite() || !y.isFinite()) {
            return null
        }
        return ProjectedPoint(x = x, y = y)
    }

    private fun List<ProjectedPoint>.sampleUpTo(maxCount: Int): List<ProjectedPoint> {
        if (size <= maxCount) {
            return this
        }
        val stride = ceil(size / maxCount.toFloat()).toInt().coerceAtLeast(1)
        return filterIndexed { index, _ -> index % stride == 0 }
            .take(maxCount)
    }

    private fun medianOf(values: List<Float>): Float {
        if (values.isEmpty()) {
            return 0f
        }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) * 0.5f
        } else {
            sorted[mid]
        }
    }

    private fun percentile(sortedValues: List<Float>, percentile: Float): Float {
        if (sortedValues.isEmpty()) {
            return 1f
        }
        val clamped = percentile.coerceIn(0f, 1f)
        val index = (clamped * (sortedValues.lastIndex.toFloat())).toInt()
            .coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    private fun xyDistance(x: Float, y: Float, cx: Float, cy: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return sqrt(dx * dx + dy * dy)
    }

    private fun screenX(worldX: Float, centerX: Float, scale: Float, viewportWidth: Float): Float {
        return viewportWidth * 0.5f + ((worldX - centerX) * scale) + userPanX
    }

    private fun screenY(worldY: Float, centerY: Float, scale: Float, viewportHeight: Float): Float {
        return viewportHeight * 0.5f - ((worldY - centerY) * scale) + userPanY
    }

    private data class Extent(
        val centerX: Float,
        val centerY: Float,
        val halfWorldSpan: Float,
    )

    private data class ProjectedPoint(
        val x: Float,
        val y: Float,
    )

    private data class BodyHitTarget(
        val bodyId: String,
        val x: Float,
        val y: Float,
        val selectionRadiusPx: Float,
    )

    private data class ViewportState(
        val bodyHits: List<BodyHitTarget>,
    ) {
        fun nearestBodyHit(x: Float, y: Float): BodyHitTarget? {
            return bodyHits
                .asSequence()
                .mapNotNull { hit ->
                    val dx = hit.x - x
                    val dy = hit.y - y
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance <= hit.selectionRadiusPx) {
                        hit to distance
                    } else {
                        null
                    }
                }
                .minByOrNull { (_, distance) -> distance }
                ?.first
        }
    }

    private enum class ProjectionPlane {
        XY,
        XZ,
    }

}
