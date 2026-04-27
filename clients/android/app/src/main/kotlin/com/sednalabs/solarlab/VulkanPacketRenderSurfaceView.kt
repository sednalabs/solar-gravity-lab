package com.sednalabs.solarlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
import com.sednalabs.solarlab.runtime.RenderTrailFamily
import com.sednalabs.solarlab.runtime.RenderTracer
import java.util.Locale
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
        private const val STARFIELD_POINT_COUNT = 118
        private const val ACCENT_STAR_COUNT = 28
        private const val PRIMARY_BODY_EXTENT_CAP = 14
        private const val AUXILIARY_SPAN_TO_BODY_SPAN_CAP = 1.45f
        private const val ASTRONOMICAL_UNIT_M = 149_597_870_700f
        private const val DEFAULT_VIEW_RADIUS_M = 24f * ASTRONOMICAL_UNIT_M
        private const val MIN_VIEW_RADIUS_M = 0.001f * ASTRONOMICAL_UNIT_M
        private const val MAX_VIEW_RADIUS_M = 150_000f * ASTRONOMICAL_UNIT_M
        private const val MIN_LOCKED_VIEW_RADIUS_M = 0.0002f * ASTRONOMICAL_UNIT_M
        private const val MAX_LOCKED_VIEW_RADIUS_M = 8f * ASTRONOMICAL_UNIT_M
        private const val HERO_MIN_VIEW_RADIUS_M = 0.18f * ASTRONOMICAL_UNIT_M
        private const val HERO_MAX_VIEW_RADIUS_M = 6f * ASTRONOMICAL_UNIT_M
        private const val OVERHEAD_MIN_VIEW_RADIUS_M = 0.25f * ASTRONOMICAL_UNIT_M
        private const val OVERHEAD_MAX_VIEW_RADIUS_M = 8f * ASTRONOMICAL_UNIT_M
        private const val MIN_CAMERA_DISTANCE_EPSILON_M = 1f
        private const val MIN_TAP_SELECTION_RADIUS_PX = 18f
        private const val MAX_SCENE_LABELS = 10
        private val TEACHING_FRAME_BODY_IDS = setOf(
            "sun",
            "mercury",
            "venus",
            "earth",
            "moon",
            "mars",
        )
        private val PROMINENT_BODY_IDS = setOf(
            "sun",
            "mercury",
            "venus",
            "earth",
            "moon",
            "mars",
            "jupiter",
            "saturn",
            "uranus",
            "neptune",
        )
        private val LABELLED_BODY_IDS = setOf(
            "sun",
            "mercury",
            "venus",
            "earth",
            "moon",
            "mars",
            "jupiter",
            "saturn",
        )
        private val FOCUS_COMPANION_CANDIDATES = mapOf(
            "earth" to listOf("moon"),
            "moon" to listOf("earth"),
            "jupiter" to listOf("io", "europa", "ganymede", "callisto"),
            "saturn" to listOf("titan", "enceladus", "rhea", "dione", "iapetus"),
        )
        private val BODY_DISPLAY_NAMES = SolarLabTeachingCatalog.entries.associate { entry ->
            entry.bodyId.lowercase(Locale.US) to entry.displayName
        }
    }

    // Frame reference is replaced atomically from Compose callbacks and read on draw.
    private var latestFrame: RenderFrame? = null
    private var historicalTrailSourceBodyIds: List<String> = emptyList()
    private var historicalTrailsEnabled: Boolean = true
    private var forecastTrailSourceBodyIds: Set<String> = emptySet()
    private var forecastOverlayEnabled: Boolean = true
    private var surfaceReady: Boolean = false
    private var onBodyTapped: ((String) -> Unit)? = null
    private var activeViewportState: ViewportState? = null
    private var activeCameraState: StageCameraState? = null
    private var userCameraOverrideActive: Boolean = false
    private var lastSelectedBodyId: String? = null
    private var cameraAnimationPosted: Boolean = false
    private var cameraTransitionBoostFrames: Int = 0
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var cameraPresentationMode: CameraPresentationMode = CameraPresentationMode.Cinematic

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(7, 11, 19)
        style = Paint.Style.FILL
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
        strokeWidth = 2.2f
    }
    private val highlightedBodyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    private val tracerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bodyCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val forecastPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.7f
    }
    private val forecastPathGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3.3f
    }
    private val referenceGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val referenceAxisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.4f
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
    }
    private val labelConnectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
        strokeCap = Paint.Cap.ROUND
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 243, 252)
        textSize = 12.5f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        historicalTrailSourceBodyIds: List<String> = emptyList(),
        showHistoricalTrails: Boolean = true,
        showForecastOverlay: Boolean = true,
        forecastTrailSourceBodyIds: List<String> = emptyList(),
    ) {
        latestFrame = frame
        if (frame == null) {
            activeViewportState = null
            activeCameraState = null
            userCameraOverrideActive = false
            lastSelectedBodyId = null
        }
        this.historicalTrailSourceBodyIds = historicalTrailSourceBodyIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        historicalTrailsEnabled = showHistoricalTrails
        forecastOverlayEnabled = showForecastOverlay
        this.forecastTrailSourceBodyIds = forecastTrailSourceBodyIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.lowercase(Locale.US) }
            .toSet()
        val selectedBodyId = frame?.bodies?.firstOrNull { it.selected }?.bodyId?.lowercase(Locale.US)
        if (selectedBodyId != lastSelectedBodyId) {
            userCameraOverrideActive = false
            lastSelectedBodyId = selectedBodyId
        }
        drawNow()
    }

    fun setOnBodyTapped(listener: ((String) -> Unit)?) {
        onBodyTapped = listener
    }

    fun setCameraPresentationMode(mode: CameraPresentationMode) {
        if (cameraPresentationMode == mode) {
            return
        }
        cameraPresentationMode = mode
        cameraTransitionBoostFrames = 10
        userCameraOverrideActive = false
        drawNow()
    }

    fun debugBodyScreenPoint(bodyId: String): Pair<Float, Float>? {
        val bodyHit = activeViewportState
            ?.bodyHits
            ?.firstOrNull { hit ->
                hit.bodyId.equals(bodyId, ignoreCase = true)
            }
            ?: return null
        return bodyHit.x to bodyHit.y
    }

    fun debugActiveCameraModeLabel(): String = cameraPresentationMode.name

    fun resetViewTransform() {
        userCameraOverrideActive = false
        drawNow()
    }

    fun zoomBy(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) {
            return
        }
        val current = activeCameraState ?: inferBootstrapCameraState(latestFrame)
        if (current != null) {
            userCameraOverrideActive = true
            activeCameraState = current.copy(
                viewRadiusM = (current.viewRadiusM / scaleFactor)
                    .coerceIn(MIN_VIEW_RADIUS_M, MAX_VIEW_RADIUS_M),
                mode = StageCameraMode.FreeTouch,
            )
        }
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
                        panByPixels(dx = dx, dy = dy)
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
        val minDimension = min(viewportWidth, viewportHeight)
        backgroundPaint.shader = LinearGradient(
            0f,
            0f,
            viewportWidth,
            viewportHeight,
            Color.rgb(2, 5, 12),
            Color.rgb(8, 19, 34),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, viewportWidth, viewportHeight, backgroundPaint)

        glowPaint.shader = RadialGradient(
            viewportWidth * 0.34f,
            viewportHeight * 0.24f,
            minDimension * 0.72f,
            intArrayOf(
                Color.argb(82, 28, 69, 146),
                Color.argb(24, 8, 35, 54),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, viewportWidth, viewportHeight, glowPaint)

        glowPaint.shader = LinearGradient(
            0f,
            viewportHeight * 0.16f,
            viewportWidth,
            viewportHeight * 0.82f,
            Color.argb(26, 42, 157, 172),
            Color.TRANSPARENT,
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
            val warmMix = pseudoRandomUnit(index * 67 + 5)
            val alpha = (26 + (pseudoRandomUnit(index * 97 + 7) * 116f)).toInt().coerceIn(20, 164)
            val radius = (0.45f + pseudoRandomUnit(index * 73 + 3) * 1.45f)
            glowPaint.shader = null
            glowPaint.color = when {
                warmMix > 0.84f -> Color.argb(alpha, 255, 212, 158)
                warmMix < 0.18f -> Color.argb(alpha, 188, 214, 255)
                else -> Color.argb(alpha, 224, 235, 255)
            }
            canvas.drawCircle(starX, starY, radius, glowPaint)
        }

        for (index in 0 until ACCENT_STAR_COUNT) {
            val normalizedX = pseudoRandomUnit(index * 89 + 13)
            val normalizedY = pseudoRandomUnit(index * 41 + 29)
            val starX = normalizedX * viewportWidth
            val starY = normalizedY * viewportHeight
            val radius = 1.35f + pseudoRandomUnit(index * 71 + 17) * 1.8f
            val alpha = (92 + pseudoRandomUnit(index * 131 + 17) * 110f).toInt().coerceIn(80, 192)
            glowPaint.shader = RadialGradient(
                starX,
                starY,
                radius * 4.6f,
                intArrayOf(
                    Color.argb(alpha, 255, 247, 220),
                    Color.argb((alpha * 0.34f).toInt(), 125, 170, 255),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(starX, starY, radius * 4.6f, glowPaint)
            glowPaint.shader = null
            glowPaint.color = Color.argb(alpha, 255, 245, 230)
            canvas.drawCircle(starX, starY, radius, glowPaint)
        }

        glowPaint.shader = RadialGradient(
            viewportWidth * 0.42f,
            viewportHeight * 0.36f,
            minDimension * 0.38f,
            intArrayOf(
                Color.argb(10, 58, 96, 164),
                Color.argb(4, 22, 39, 76),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(viewportWidth * 0.42f, viewportHeight * 0.36f, minDimension * 0.38f, glowPaint)

        glowPaint.shader = RadialGradient(
            centerX,
            centerY,
            minDimension * 0.92f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(18, 2, 5, 12),
                Color.argb(146, 1, 3, 8),
            ),
            floatArrayOf(0.58f, 0.84f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, viewportWidth, viewportHeight, glowPaint)

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
        val targetCamera = resolveTargetCameraState(
            frame = frame,
            extent = extent,
            projectionPlane = projectionPlane,
        )
        val effectiveCamera = resolveEffectiveCameraState(targetCamera)
        val halfWorldSpan = effectiveCamera.viewRadiusM.coerceAtLeast(MIN_CAMERA_DISTANCE_EPSILON_M)
        val scale = (0.46f * min(viewportWidth, viewportHeight) / halfWorldSpan)
        val highlightedBodyIds = historicalTrailSourceBodyIds
            .asSequence()
            .map { it.lowercase(Locale.US) }
            .toSet()
        val trailHighlightRanks = historicalTrailSourceBodyIds
            .withIndex()
            .associate { (index, sourceBodyId) -> sourceBodyId.lowercase(Locale.US) to index }
        val bodyHits = ArrayList<BodyHitTarget>(frame.bodies.size)
        val labelAnchors = ArrayList<BodyLabelAnchor>(min(frame.bodies.size, MAX_SCENE_LABELS + 2))
        drawSolarKeyLight(
            canvas = canvas,
            frame = frame,
            projectionPlane = projectionPlane,
            centerX = effectiveCamera.centerX,
            centerY = effectiveCamera.centerY,
            scale = scale,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        drawReferencePlane(
            canvas = canvas,
            centerX = effectiveCamera.centerX,
            centerY = effectiveCamera.centerY,
            scale = scale,
            halfWorldSpan = halfWorldSpan,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )

        frame.trails.forEach { trail ->
            val normalizedSourceBodyId = trail.sourceBodyId.lowercase(Locale.US)
            val highlightRank = trailHighlightRanks[normalizedSourceBodyId]
            val shouldDrawHistoricalTrail = trail.family != RenderTrailFamily.Prediction &&
                historicalTrailsEnabled && (
                historicalTrailSourceBodyIds.isEmpty() ||
                    highlightRank != null ||
                    trail.headHighlighted
                )
            if (shouldDrawHistoricalTrail) {
                drawTrail(
                    canvas = canvas,
                    trail = trail,
                    centerX = effectiveCamera.centerX,
                    centerY = effectiveCamera.centerY,
                    projectionPlane = projectionPlane,
                    scale = scale,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    highlightRank = highlightRank,
                )
            }
            if (
                trail.family == RenderTrailFamily.Prediction &&
                forecastOverlayEnabled &&
                (
                    trail.headHighlighted ||
                        forecastTrailSourceBodyIds.contains(normalizedSourceBodyId)
                    )
            ) {
                drawForecastPathFromTrail(
                    canvas = canvas,
                    trail = trail,
                    centerX = effectiveCamera.centerX,
                    centerY = effectiveCamera.centerY,
                    projectionPlane = projectionPlane,
                    scale = scale,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                )
            }
        }
        frame.tracers.forEach { tracer ->
            drawTracer(
                canvas = canvas,
                tracer = tracer,
                centerX = effectiveCamera.centerX,
                centerY = effectiveCamera.centerY,
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
                centerX = effectiveCamera.centerX,
                centerY = effectiveCamera.centerY,
                projectionPlane = projectionPlane,
                scale = scale,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                halfWorldSpan = halfWorldSpan,
                highlighted = highlightedBodyIds.contains(body.bodyId.lowercase(Locale.US)),
                bodyHits = bodyHits,
                labelAnchors = labelAnchors,
            )
        }

        drawBodyLabels(
            canvas = canvas,
            labelAnchors = labelAnchors,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )

        activeViewportState = ViewportState(
            bodyHits = bodyHits,
        )

        val cameraSettled = activeCameraState?.isCloseTo(targetCamera) ?: true
        if (!cameraSettled && !cameraAnimationPosted) {
            cameraAnimationPosted = true
            postOnAnimation {
                cameraAnimationPosted = false
                drawNow()
            }
        }
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
        highlighted: Boolean,
        bodyHits: MutableList<BodyHitTarget>,
        labelAnchors: MutableList<BodyLabelAnchor>,
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
        val normalizedBodyId = body.bodyId.lowercase(Locale.US)
        val isSun = normalizedBodyId == "sun"
        val isProminent = normalizedBodyId in PROMINENT_BODY_IDS
        val minDimension = min(viewportWidth, viewportHeight)
        val linearRadiusPx = (body.radiusM / halfWorldSpan * minDimension * 0.5f).coerceAtLeast(0f)
        val boostedRadiusPx = sqrt(linearRadiusPx + 0.18f) * when {
            isSun -> 2.95f
            body.selected -> 2.4f
            highlighted -> 2.1f
            isProminent -> 2.3f
            else -> 1.32f
        }
        val radiusPx = when {
            isSun -> boostedRadiusPx.coerceIn(10f, 28f)
            body.selected -> boostedRadiusPx.coerceIn(4.6f, 15.5f)
            highlighted -> boostedRadiusPx.coerceIn(3.9f, 12.4f)
            isProminent -> boostedRadiusPx.coerceIn(3.8f, 12.8f)
            else -> boostedRadiusPx.coerceIn(1.1f, 5.0f)
        }
        val baseAlpha = (body.colorA.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        val colorAlpha = when {
            isSun -> baseAlpha.coerceAtLeast(190)
            isProminent -> baseAlpha.coerceAtLeast(150)
            else -> baseAlpha
        }
        val colorRed = (body.colorR.coerceIn(0f, 1f) * 255f).toInt()
        val colorGreen = (body.colorG.coerceIn(0f, 1f) * 255f).toInt()
        val colorBlue = (body.colorB.coerceIn(0f, 1f) * 255f).toInt()
        bodyPaint.color = Color.argb(colorAlpha, colorRed, colorGreen, colorBlue)
        val glowAlpha = (body.colorA.coerceIn(0f, 1f) * when {
            isSun -> 0.48f
            body.selected -> 0.4f
            highlighted -> 0.34f
            isProminent -> 0.28f
            else -> 0.2f
        } * 255f).toInt().coerceIn(0, 255)
        bodyGlowPaint.color = Color.argb(glowAlpha, colorRed, colorGreen, colorBlue)
        if (isSun) {
            bodyGlowPaint.color = Color.argb(62, 255, 206, 122)
            canvas.drawCircle(sx, sy, radiusPx * 4.8f, bodyGlowPaint)
            bodyGlowPaint.color = Color.argb(124, 255, 184, 102)
            canvas.drawCircle(sx, sy, radiusPx * 2.8f, bodyGlowPaint)
        }
        canvas.drawCircle(
            sx,
            sy,
            radiusPx * when {
                body.selected -> 2.7f
                highlighted -> 2.35f
                isProminent -> 2.1f
                else -> 1.8f
            },
            bodyGlowPaint,
        )
        canvas.drawCircle(sx, sy, radiusPx, bodyPaint)
        if (body.selected || highlighted || isProminent) {
            bodyCorePaint.color = Color.argb(
                if (isSun) 218 else if (body.selected) 160 else 110,
                255,
                249,
                242,
            )
            canvas.drawCircle(
                sx - radiusPx * 0.18f,
                sy - radiusPx * 0.18f,
                max(1.1f, radiusPx * 0.28f),
                bodyCorePaint,
            )
        }
        if (highlighted && !body.selected) {
            highlightedBodyStroke.color = Color.argb(128, colorRed, colorGreen, colorBlue)
            canvas.drawCircle(sx, sy, radiusPx + 2.4f, highlightedBodyStroke)
        }
        if (body.selected) {
            selectedBodyStroke.color = Color.argb(228, 255, 239, 128)
            canvas.drawCircle(sx, sy, radiusPx + 2.5f, selectedBodyStroke)
            highlightedBodyStroke.color = Color.argb(92, 255, 239, 128)
            canvas.drawCircle(sx, sy, radiusPx + 6.2f, highlightedBodyStroke)
        }
        if (body.selected || normalizedBodyId in LABELLED_BODY_IDS) {
            labelAnchors += BodyLabelAnchor(
                displayName = BODY_DISPLAY_NAMES[normalizedBodyId] ?: body.bodyId,
                x = sx,
                y = sy,
                radiusPx = radiusPx,
                accentColor = Color.argb(220, colorRed, colorGreen, colorBlue),
                priority = when {
                    body.selected -> 0
                    isSun -> 1
                    normalizedBodyId == "earth" -> 2
                    normalizedBodyId == "moon" -> 3
                    normalizedBodyId == "mars" -> 4
                    else -> 5
                },
            )
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
        if (radiusPx >= 1.4f) {
            glowPaint.shader = null
            glowPaint.color = Color.argb(
                (tracer.colorA.coerceIn(0f, 1f) * 0.18f * 255f).toInt(),
                (tracer.colorR.coerceIn(0f, 1f) * 255f).toInt(),
                (tracer.colorG.coerceIn(0f, 1f) * 255f).toInt(),
                (tracer.colorB.coerceIn(0f, 1f) * 255f).toInt(),
            )
            canvas.drawCircle(sx, sy, radiusPx * 2.1f, glowPaint)
        }
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
        var previousScreenPoint: ScreenPoint? = null
        var lastScreenPoint: ScreenPoint? = null
        trail.points.forEach { point ->
            val projectedY = projectY(point.y, point.z, projectionPlane)
            if (!point.x.isFinite() || !projectedY.isFinite()) {
                return@forEach
            }
            val sx = screenX(point.x, centerX, scale, viewportWidth)
            val sy = screenY(projectedY, centerY, scale, viewportHeight)
            if (!sx.isFinite() || !sy.isFinite()) {
                return@forEach
            }
            if (plottedPointCount == 0) {
                path.moveTo(sx, sy)
            } else {
                path.lineTo(sx, sy)
            }
            plottedPointCount++
            previousScreenPoint = lastScreenPoint
            lastScreenPoint = ScreenPoint(x = sx, y = sy)
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
        lastScreenPoint?.let { head ->
            val headRadius = when {
                highlightRank == 0 -> 4.8f
                highlightRank != null -> 3.9f
                trail.headHighlighted -> 3.2f
                else -> 2.2f
            }
            glowPaint.shader = null
            glowPaint.color = Color.argb(
                (trail.colorA.coerceIn(0f, 1f) * alphaScale * 0.26f * 255f).toInt(),
                (trail.colorR.coerceIn(0f, 1f) * 255f).toInt(),
                (trail.colorG.coerceIn(0f, 1f) * 255f).toInt(),
                (trail.colorB.coerceIn(0f, 1f) * 255f).toInt(),
            )
            canvas.drawCircle(head.x, head.y, headRadius * 2.5f, glowPaint)
            tracerPaint.color = Color.argb(
                (trail.colorA.coerceIn(0f, 1f) * alphaScale * 255f).toInt(),
                (trail.colorR.coerceIn(0f, 1f) * 255f).toInt(),
                (trail.colorG.coerceIn(0f, 1f) * 255f).toInt(),
                (trail.colorB.coerceIn(0f, 1f) * 255f).toInt(),
            )
            canvas.drawCircle(head.x, head.y, headRadius, tracerPaint)
            previousScreenPoint?.let { previous ->
                val dx = head.x - previous.x
                val dy = head.y - previous.y
                val length = sqrt(dx * dx + dy * dy)
                if (length > 0.6f) {
                    val tickLength = min(12f, length * 0.38f + headRadius)
                    val tickStartX = head.x - dx / length * tickLength
                    val tickStartY = head.y - dy / length * tickLength
                    canvas.drawLine(tickStartX, tickStartY, head.x, head.y, trailPaint)
                }
            }
        }
    }

    private fun drawForecastPathFromTrail(
        canvas: Canvas,
        trail: RenderTrail,
        centerX: Float,
        centerY: Float,
        projectionPlane: ProjectionPlane,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        if (trail.points.size < 2) return
        val projectedPoints = trail.points
            .asSequence()
            .mapNotNull { point ->
                val projectedY = projectY(point.y, point.z, projectionPlane)
                if (!point.x.isFinite() || !projectedY.isFinite()) {
                    null
                } else {
                    ProjectedPoint(
                        x = screenX(point.x, centerX, scale, viewportWidth),
                        y = screenY(projectedY, centerY, scale, viewportHeight),
                    )
                }
            }
            .toList()
        if (projectedPoints.size < 2) return
        val head = projectedPoints.last()
        val previous = projectedPoints[projectedPoints.lastIndex - 1]
        val dx = head.x - previous.x
        val dy = head.y - previous.y
        val directionLength = sqrt(dx * dx + dy * dy)
        if (!directionLength.isFinite() || directionLength < 1.5f) return

        val extensionScale = when (cameraPresentationMode) {
            CameraPresentationMode.Cinematic -> 2.4f
            CameraPresentationMode.Overhead -> 1.8f
            CameraPresentationMode.Follow -> 2.9f
        }
        val extensionLength = (directionLength * extensionScale)
            .coerceIn(18f, min(viewportWidth, viewportHeight) * 0.24f)
        val endX = head.x + (dx / directionLength) * extensionLength
        val endY = head.y + (dy / directionLength) * extensionLength

        forecastPathGlowPaint.color = Color.argb(
            (trail.colorA.coerceIn(0f, 1f) * 0.3f * 255f).toInt().coerceIn(22, 112),
            (trail.colorR.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorG.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorB.coerceIn(0f, 1f) * 255f).toInt(),
        )
        forecastPathPaint.color = Color.argb(
            (trail.colorA.coerceIn(0f, 1f) * 0.78f * 255f).toInt().coerceIn(92, 228),
            (trail.colorR.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorG.coerceIn(0f, 1f) * 255f).toInt(),
            (trail.colorB.coerceIn(0f, 1f) * 255f).toInt(),
        )
        canvas.drawLine(head.x, head.y, endX, endY, forecastPathGlowPaint)
        canvas.drawLine(head.x, head.y, endX, endY, forecastPathPaint)
        canvas.drawCircle(endX, endY, 1.8f, forecastPathPaint)
    }

    private fun drawSolarKeyLight(
        canvas: Canvas,
        frame: RenderFrame,
        projectionPlane: ProjectionPlane,
        centerX: Float,
        centerY: Float,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        val sun = frame.bodies.firstOrNull { body ->
            body.bodyId.equals("sun", ignoreCase = true) &&
                body.x.isFinite() &&
                projectY(body.y, body.z, projectionPlane).isFinite()
        } ?: return
        val projectedY = projectY(sun.y, sun.z, projectionPlane)
        val sunScreenX = screenX(sun.x, centerX, scale, viewportWidth)
        val sunScreenY = screenY(projectedY, centerY, scale, viewportHeight)
        if (!sunScreenX.isFinite() || !sunScreenY.isFinite()) {
            return
        }
        val radiusPx = min(viewportWidth, viewportHeight) * 0.30f
        val primaryAlpha = when (cameraPresentationMode) {
            CameraPresentationMode.Cinematic -> 34
            CameraPresentationMode.Overhead -> 28
            CameraPresentationMode.Follow -> 24
        }
        glowPaint.shader = RadialGradient(
            sunScreenX,
            sunScreenY,
            radiusPx * 0.72f,
            intArrayOf(
                Color.argb(primaryAlpha, 255, 209, 122),
                Color.argb((primaryAlpha * 0.4f).toInt(), 255, 173, 108),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.34f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(sunScreenX, sunScreenY, radiusPx * 0.72f, glowPaint)
        glowPaint.shader = null
    }

    private fun drawReferencePlane(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        scale: Float,
        halfWorldSpan: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        if (!centerX.isFinite() || !centerY.isFinite() || !scale.isFinite() || scale <= 0f) {
            return
        }
        val minDimension = min(viewportWidth, viewportHeight)
        val gridSpacingPx = (minDimension / 8.4f).coerceIn(42f, 96f)
        val gridAlpha = when (cameraPresentationMode) {
            CameraPresentationMode.Cinematic -> 24
            CameraPresentationMode.Overhead -> 32
            CameraPresentationMode.Follow -> 20
        }
        referenceGridPaint.color = Color.argb(gridAlpha, 118, 247, 255)
        referenceGridPaint.strokeWidth = 1f

        val originX = screenX(0f, centerX, scale, viewportWidth)
        val originY = screenY(0f, centerY, scale, viewportHeight)

        var x = positiveModulo(originX, gridSpacingPx)
        while (x <= viewportWidth) {
            canvas.drawLine(x, 0f, x, viewportHeight, referenceGridPaint)
            x += gridSpacingPx
        }

        var y = positiveModulo(originY, gridSpacingPx)
        while (y <= viewportHeight) {
            canvas.drawLine(0f, y, viewportWidth, y, referenceGridPaint)
            y += gridSpacingPx
        }
        val margin = minDimension * 0.28f
        if (originX in -margin..(viewportWidth + margin)) {
            referenceAxisPaint.color = Color.argb(54, 118, 247, 255)
            referenceAxisPaint.strokeWidth = 1.2f
            canvas.drawLine(originX, 0f, originX, viewportHeight, referenceAxisPaint)
        }
        if (originY in -margin..(viewportHeight + margin)) {
            referenceAxisPaint.color = Color.argb(42, 255, 211, 107)
            referenceAxisPaint.strokeWidth = 1.2f
            canvas.drawLine(0f, originY, viewportWidth, originY, referenceAxisPaint)
        }

        if (originX in -margin..(viewportWidth + margin) && originY in -margin..(viewportHeight + margin)) {
            val referenceRadiusBase = (halfWorldSpan * scale).coerceAtLeast(minDimension * 0.16f)
            listOf(0.34f, 0.58f, 0.82f).forEachIndexed { index, radiusScale ->
                val radius = (referenceRadiusBase * radiusScale).coerceIn(minDimension * 0.10f, minDimension * 0.48f)
                referenceAxisPaint.color = Color.argb(18 + index * 6, 118, 247, 255)
                referenceAxisPaint.strokeWidth = 1f
                canvas.drawCircle(originX, originY, radius, referenceAxisPaint)
            }
        }
    }

    private fun positiveModulo(value: Float, modulus: Float): Float {
        if (!value.isFinite() || !modulus.isFinite() || modulus <= 0f) {
            return 0f
        }
        val remainder = value % modulus
        return if (remainder < 0f) remainder + modulus else remainder
    }

    private fun resolveEffectiveCameraState(target: StageCameraState): StageCameraState {
        val current = activeCameraState
        if (current == null) {
            activeCameraState = target
            return target
        }
        if (userCameraOverrideActive && current.mode == StageCameraMode.FreeTouch) {
            return current
        }
        val interpolation = when {
            cameraTransitionBoostFrames > 0 -> 0.36f
            current.mode != target.mode -> 0.28f
            else -> 0.18f
        }
        if (cameraTransitionBoostFrames > 0) {
            cameraTransitionBoostFrames--
        }
        val next = current.blendToward(target = target, amount = interpolation)
        activeCameraState = next
        return next
    }

    private fun resolveTargetCameraState(
        frame: RenderFrame,
        extent: Extent,
        projectionPlane: ProjectionPlane,
    ): StageCameraState {
        return when (cameraPresentationMode) {
            CameraPresentationMode.Overhead -> resolveOverheadCameraState(
                frame = frame,
                extent = extent,
                projectionPlane = projectionPlane,
            )

            CameraPresentationMode.Follow -> resolveFollowCameraState(
                frame = frame,
                projectionPlane = projectionPlane,
                forceFollow = true,
                cinematicBias = false,
            ) ?: resolveOverheadCameraState(
                frame = frame,
                extent = extent,
                projectionPlane = projectionPlane,
            )

            CameraPresentationMode.Cinematic -> {
                resolveFollowCameraState(
                    frame = frame,
                    projectionPlane = projectionPlane,
                    forceFollow = false,
                    cinematicBias = true,
                ) ?: resolveHeroCameraState(
                    frame = frame,
                    extent = extent,
                    projectionPlane = projectionPlane,
                )
            }
        }
    }

    private fun resolveOverheadCameraState(
        frame: RenderFrame,
        extent: Extent,
        projectionPlane: ProjectionPlane,
    ): StageCameraState {
        val sunBody = frame.bodies.firstOrNull { it.bodyId.equals("sun", ignoreCase = true) }
        val centerX = sunBody?.x ?: extent.centerX
        val centerY = sunBody?.let { body -> projectY(body.y, body.z, projectionPlane) } ?: extent.centerY
        return StageCameraState(
            centerX = centerX,
            centerY = centerY,
            viewRadiusM = extent.halfWorldSpan.coerceIn(OVERHEAD_MIN_VIEW_RADIUS_M, OVERHEAD_MAX_VIEW_RADIUS_M),
            mode = StageCameraMode.OverheadWide,
        )
    }

    private fun resolveHeroCameraState(
        frame: RenderFrame,
        extent: Extent,
        projectionPlane: ProjectionPlane,
    ): StageCameraState {
        val sunBody = frame.bodies.firstOrNull { it.bodyId.equals("sun", ignoreCase = true) }
        val heroCenterX = sunBody?.x ?: extent.centerX
        val heroCenterY = sunBody?.let { body ->
            projectY(body.y, body.z, projectionPlane)
        } ?: extent.centerY
        val heroRadius = extent.halfWorldSpan
            .coerceIn(HERO_MIN_VIEW_RADIUS_M, HERO_MAX_VIEW_RADIUS_M)
        return StageCameraState(
            centerX = heroCenterX,
            centerY = heroCenterY,
            viewRadiusM = heroRadius,
            mode = StageCameraMode.CinematicWide,
        )
    }

    private fun resolveFollowCameraState(
        frame: RenderFrame,
        projectionPlane: ProjectionPlane,
        forceFollow: Boolean,
        cinematicBias: Boolean,
    ): StageCameraState? {
        val focusBody = resolveFocusBodyCandidate(frame = frame, forceFollow = forceFollow) ?: return null
        val focusProjectedY = projectY(focusBody.y, focusBody.z, projectionPlane)
        val companion = findCompanionBody(
            frame = frame,
            focusBodyId = focusBody.bodyId,
        )
        val centerX = if (cinematicBias && companion != null) {
            focusBody.x * 0.86f + companion.x * 0.14f
        } else {
            focusBody.x
        }
        val centerY = if (cinematicBias && companion != null) {
            val companionProjectedY = projectY(companion.y, companion.z, projectionPlane)
            focusProjectedY * 0.86f + companionProjectedY * 0.14f
        } else {
            focusProjectedY
        }
        val suggestedRadius = resolveSuggestedFollowViewRadiusM(
            focusBody = focusBody,
            companionBody = companion,
            presentationMode = cameraPresentationMode,
        )
        return StageCameraState(
            centerX = centerX,
            centerY = centerY,
            viewRadiusM = suggestedRadius,
            mode = if (cinematicBias) StageCameraMode.CinematicFollow else StageCameraMode.FollowSelection,
        )
    }

    private fun resolveFocusBodyCandidate(
        frame: RenderFrame,
        forceFollow: Boolean,
    ): RenderBody? {
        val runtimeFollowMode = frame.observerModeCode == 1 || frame.observerModeCode == 2
        val shouldFollow = forceFollow ||
            runtimeFollowMode ||
            cameraPresentationMode == CameraPresentationMode.Cinematic
        val selectedBody = frame.bodies.firstOrNull { it.selected }
        return selectedBody
            ?: frame.bodies.firstOrNull {
                it.bodyId.equals(lastSelectedBodyId, ignoreCase = true)
            }
            ?: if (shouldFollow) {
                frame.bodies.firstOrNull {
                    it.bodyId.equals("earth", ignoreCase = true)
                }
            } else {
                null
            }
            ?: if (shouldFollow) {
                frame.bodies.firstOrNull {
                    it.bodyId.equals("sun", ignoreCase = true)
                } ?: frame.bodies.firstOrNull()
            } else {
                null
            }
    }

    private fun inferBootstrapCameraState(frame: RenderFrame?): StageCameraState? {
        val safeFrame = frame ?: latestFrame ?: return null
        val projectionPlane = selectOverheadProjectionPlane(safeFrame)
        val extent = computeExtent(safeFrame, projectionPlane)
        return resolveTargetCameraState(
            frame = safeFrame,
            extent = extent,
            projectionPlane = projectionPlane,
        )
    }

    private fun panByPixels(dx: Float, dy: Float) {
        val current = activeCameraState ?: inferBootstrapCameraState(latestFrame) ?: return
        val minDimensionPx = min(width.toFloat(), height.toFloat()).coerceAtLeast(1f)
        val metersPerPixel = (2f * current.viewRadiusM) / minDimensionPx
        userCameraOverrideActive = true
        activeCameraState = current.copy(
            centerX = current.centerX - (dx * metersPerPixel),
            centerY = current.centerY + (dy * metersPerPixel),
            mode = StageCameraMode.FreeTouch,
        )
    }

    private fun findCompanionBody(
        frame: RenderFrame,
        focusBodyId: String,
    ): RenderBody? {
        val normalizedFocusId = focusBodyId.lowercase(Locale.US)
        val candidates = FOCUS_COMPANION_CANDIDATES[normalizedFocusId].orEmpty()
        return candidates
            .asSequence()
            .mapNotNull { candidateId ->
                frame.bodies.firstOrNull { body ->
                    body.bodyId.equals(candidateId, ignoreCase = true)
                }
            }
            .firstOrNull()
    }

    private fun resolveSuggestedFollowViewRadiusM(
        focusBody: RenderBody,
        companionBody: RenderBody?,
        presentationMode: CameraPresentationMode,
    ): Float {
        val normalizedBodyId = focusBody.bodyId.lowercase(Locale.US)
        val baselineRadius = when {
            normalizedBodyId == "sun" -> 0.72f * ASTRONOMICAL_UNIT_M
            normalizedBodyId in setOf("jupiter", "saturn", "uranus", "neptune") -> 0.018f * ASTRONOMICAL_UNIT_M
            normalizedBodyId in setOf("earth", "venus", "mars", "mercury") -> 0.0065f * ASTRONOMICAL_UNIT_M
            normalizedBodyId == "moon" -> 0.0018f * ASTRONOMICAL_UNIT_M
            else -> 0.0035f * ASTRONOMICAL_UNIT_M
        }
        val baselineScale = when (presentationMode) {
            CameraPresentationMode.Cinematic -> 1f
            CameraPresentationMode.Overhead -> 1f
            CameraPresentationMode.Follow -> 0.72f
        }
        val companionScale = when (presentationMode) {
            CameraPresentationMode.Cinematic -> 1.9f
            CameraPresentationMode.Overhead -> 1.7f
            CameraPresentationMode.Follow -> 1.35f
        }
        val companionRadius = companionBody?.let { body ->
            val dx = focusBody.x - body.x
            val dy = focusBody.y - body.y
            val dz = focusBody.z - body.z
            val separationM = sqrt(dx * dx + dy * dy + dz * dz)
            (separationM * companionScale).coerceAtLeast(0f)
        } ?: 0f
        val maxRadius = when (presentationMode) {
            CameraPresentationMode.Cinematic -> MAX_LOCKED_VIEW_RADIUS_M
            CameraPresentationMode.Overhead -> OVERHEAD_MAX_VIEW_RADIUS_M
            CameraPresentationMode.Follow -> MAX_LOCKED_VIEW_RADIUS_M
        }
        return max(baselineRadius * baselineScale, companionRadius)
            .coerceIn(MIN_LOCKED_VIEW_RADIUS_M, maxRadius)
    }

    private fun drawBodyLabels(
        canvas: Canvas,
        labelAnchors: List<BodyLabelAnchor>,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        if (labelAnchors.isEmpty()) {
            return
        }
        val occupiedBounds = mutableListOf<RectF>()
        val textPaddingHorizontal = 12f
        val textPaddingVertical = 7f
        val fontMetrics = labelTextPaint.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val maxLabelTop = max(12f, viewportHeight - textHeight - textPaddingVertical * 2f - 12f)

        labelAnchors
            .sortedWith(
                compareBy<BodyLabelAnchor> { it.priority }
                    .thenByDescending { it.radiusPx }
            )
            .take(MAX_SCENE_LABELS + 2)
            .forEach { anchor ->
                val textWidth = labelTextPaint.measureText(anchor.displayName)
                val bubbleWidth = textWidth + textPaddingHorizontal * 2f
                val bubbleHeight = textHeight + textPaddingVertical * 2f
                val maxLabelLeft = max(12f, viewportWidth - bubbleWidth - 12f)
                val rightSideRect = RectF(
                    (anchor.x + anchor.radiusPx + 12f).coerceIn(12f, maxLabelLeft),
                    (anchor.y - anchor.radiusPx - bubbleHeight - 10f).coerceIn(12f, maxLabelTop),
                    0f,
                    0f,
                ).apply {
                    right = left + bubbleWidth
                    bottom = top + bubbleHeight
                }
                val leftSideRect = RectF(
                    (anchor.x - anchor.radiusPx - bubbleWidth - 12f).coerceAtLeast(12f),
                    (anchor.y - anchor.radiusPx - bubbleHeight - 10f).coerceIn(12f, maxLabelTop),
                    0f,
                    0f,
                ).apply {
                    right = left + bubbleWidth
                    bottom = top + bubbleHeight
                }
                val lowerRightRect = RectF(
                    (anchor.x + anchor.radiusPx + 12f).coerceIn(12f, maxLabelLeft),
                    (anchor.y + anchor.radiusPx + 10f).coerceIn(12f, maxLabelTop),
                    0f,
                    0f,
                ).apply {
                    right = left + bubbleWidth
                    bottom = top + bubbleHeight
                }

                val bubbleRect = listOf(rightSideRect, leftSideRect, lowerRightRect)
                    .firstOrNull { candidate ->
                        occupiedBounds.none { existing -> RectF.intersects(existing, candidate) }
                    }
                    ?: return@forEach

                occupiedBounds += bubbleRect
                labelBackgroundPaint.color = Color.argb(
                    if (anchor.priority == 0) 196 else 164,
                    6,
                    11,
                    20,
                )
                labelBorderPaint.color = withAlpha(anchor.accentColor, if (anchor.priority == 0) 184 else 126)
                labelConnectorPaint.color = withAlpha(anchor.accentColor, if (anchor.priority == 0) 176 else 118)
                val connectorEndX = if (bubbleRect.centerX() >= anchor.x) {
                    bubbleRect.left + 10f
                } else {
                    bubbleRect.right - 10f
                }
                val connectorEndY = bubbleRect.bottom - 9f
                val connectorStartX = if (bubbleRect.centerX() >= anchor.x) {
                    anchor.x + anchor.radiusPx * 0.78f
                } else {
                    anchor.x - anchor.radiusPx * 0.78f
                }
                val connectorStartY = anchor.y - anchor.radiusPx * 0.2f
                canvas.drawLine(connectorStartX, connectorStartY, connectorEndX, connectorEndY, labelConnectorPaint)
                canvas.drawRoundRect(bubbleRect, 18f, 18f, labelBackgroundPaint)
                canvas.drawRoundRect(bubbleRect, 18f, 18f, labelBorderPaint)
                labelTextPaint.color = if (anchor.priority == 0) {
                    Color.rgb(255, 249, 236)
                } else {
                    Color.rgb(232, 240, 250)
                }
                val textBaseline = bubbleRect.top + textPaddingVertical - fontMetrics.top
                canvas.drawText(anchor.displayName, bubbleRect.left + textPaddingHorizontal, textBaseline, labelTextPaint)
            }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
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
        val highlightedSourceBodyIds = historicalTrailSourceBodyIds
            .asSequence()
            .map { it.lowercase(Locale.US) }
            .toSet()
        val visibleTrailPoints = frame.trails
            .asSequence()
            .filter { trail ->
                val normalizedSourceId = trail.sourceBodyId.lowercase(Locale.US)
                trail.family != RenderTrailFamily.Prediction &&
                    historicalTrailsEnabled &&
                    (
                        historicalTrailSourceBodyIds.isEmpty() ||
                            trail.headHighlighted ||
                            highlightedSourceBodyIds.contains(normalizedSourceId)
                        )
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
        val forecastSourceBodyIds = forecastTrailSourceBodyIds
        val forecastTrailPoints = frame.trails
            .asSequence()
            .filter { trail ->
                trail.family == RenderTrailFamily.Prediction &&
                    (
                        trail.headHighlighted ||
                            forecastSourceBodyIds.contains(trail.sourceBodyId.lowercase(Locale.US))
                        )
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
            .sampleUpTo(MAX_TRAIL_POINTS_FOR_EXTENT)
        val points = buildList {
            addAll(if (primaryBodyPoints.isNotEmpty()) primaryBodyPoints else projectedBodyPoints)
            addAll(projectedTracerPoints)
            addAll(visibleTrailPoints)
            addAll(forecastTrailPoints)
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
            addAll(visibleTrailPoints)
            addAll(forecastTrailPoints)
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
        return viewportWidth * 0.5f + ((worldX - centerX) * scale)
    }

    private fun screenY(worldY: Float, centerY: Float, scale: Float, viewportHeight: Float): Float {
        return viewportHeight * 0.5f - ((worldY - centerY) * scale)
    }

    private data class Extent(
        val centerX: Float,
        val centerY: Float,
        val halfWorldSpan: Float,
    )

    private data class StageCameraState(
        val centerX: Float,
        val centerY: Float,
        val viewRadiusM: Float,
        val mode: StageCameraMode,
    ) {
        fun blendToward(target: StageCameraState, amount: Float): StageCameraState {
            val alpha = amount.coerceIn(0f, 1f)
            return StageCameraState(
                centerX = centerX + (target.centerX - centerX) * alpha,
                centerY = centerY + (target.centerY - centerY) * alpha,
                viewRadiusM = (viewRadiusM + (target.viewRadiusM - viewRadiusM) * alpha)
                    .coerceIn(MIN_VIEW_RADIUS_M, MAX_VIEW_RADIUS_M),
                mode = target.mode,
            )
        }

        fun isCloseTo(other: StageCameraState): Boolean {
            val centerDistance = sqrt(
                (centerX - other.centerX) * (centerX - other.centerX) +
                    (centerY - other.centerY) * (centerY - other.centerY),
            )
            val radiusDelta = abs(viewRadiusM - other.viewRadiusM)
            val centerTolerance = max(1_000_000f, other.viewRadiusM * 0.0025f)
            val radiusTolerance = max(500_000f, other.viewRadiusM * 0.0018f)
            return centerDistance <= centerTolerance && radiusDelta <= radiusTolerance
        }
    }

    private enum class StageCameraMode {
        CinematicWide,
        OverheadWide,
        CinematicFollow,
        FollowSelection,
        FreeTouch,
    }

    enum class CameraPresentationMode {
        Cinematic,
        Overhead,
        Follow,
    }

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

    private data class BodyLabelAnchor(
        val displayName: String,
        val x: Float,
        val y: Float,
        val radiusPx: Float,
        val accentColor: Int,
        val priority: Int,
    )

    private data class ScreenPoint(
        val x: Float,
        val y: Float,
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
