package com.sednalabs.solarlab.render.vulkan

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.render.core.CameraGesturePointer
import com.graciousgazelles.solarlab.render.core.CameraGestureStateMachine
import com.graciousgazelles.solarlab.render.core.CameraGestureUpdate
import com.graciousgazelles.solarlab.render.core.CameraNavigation
import com.graciousgazelles.solarlab.render.core.CameraScaleBand
import com.graciousgazelles.solarlab.render.core.CameraState
import com.graciousgazelles.solarlab.render.core.MultiscaleOrbitCameraController
import com.graciousgazelles.solarlab.render.core.NativeScenePacket
import com.graciousgazelles.solarlab.render.core.ObserverCameraResolver
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderBackend
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus
import com.graciousgazelles.solarlab.render.core.RenderLayerOptions
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame
import com.graciousgazelles.solarlab.render.core.SceneInteractionMath
import com.graciousgazelles.solarlab.render.core.ScenePacketBuildPolicy
import com.graciousgazelles.solarlab.render.core.withLayerOptions
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class SolarSystemVulkanSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val statusCallback: (RenderBackendStatus) -> Unit,
    private val fatalInitCallback: (String) -> Unit,
) : SurfaceView(context, attrs), SurfaceHolder.Callback2, SolarRenderSurface {

    private val capabilities = RenderDeviceCapabilities.query(context)
    private var surfaceReady: Boolean = false
    private var surfaceGenerationCounter: Long = 0L
    private var activeSurfaceGeneration: Long = 0L
    private var released: Boolean = false
    private var latestScene: RenderSceneFrame = emptyScene()
    private var latestPacket: NativeScenePacket? = null
    private var packetDirty: Boolean = true
    private var cameraState: CameraState = CameraState()
    private var interactionListener: RenderInteractionListener? = null
    private var interactionMode: SceneInteractionMode = SceneInteractionMode.NAVIGATE_AND_SELECT
    private var processingMode: RenderProcessingMode = RenderProcessingMode.DEFAULT
    private var selectedBodyId: String? = null
    private var observerMode: ObserverMode = ObserverMode.FREE
    private var renderLayerOptions: RenderLayerOptions = RenderLayerOptions()
    private var placementStartScreen: Pair<Float, Float>? = null
    private var placementPlaneZ: Double = 0.0
    private var placementGestureCancelledByTransform: Boolean = false
    private var runtimeSessionHandle: Long = 0L
    private var deferredRenderDepth: Int = 0
    private var renderDeferred: Boolean = false
    private var cameraTransition: ValueAnimator? = null
    private var cameraScaleChangedListener: ((CameraScaleBand) -> Unit)? = null
    private var lastReportedCameraScaleBand: CameraScaleBand? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serializedRenderer = SerializedVulkanRenderer(
        backend = object : SerializedVulkanRenderer.LifecycleBackend {
            override fun createRenderer(): Long = SolarLabVulkanBridge.createRenderer(context.assets)

            override fun destroyRenderer(handle: Long) {
                SolarLabVulkanBridge.destroyRenderer(handle)
            }

            override fun releaseNativeWindow(nativeWindowHandle: Long) {
                SolarLabVulkanBridge.releaseNativeWindow(nativeWindowHandle)
            }

            override fun onSurfaceCreated(
                handle: Long,
                nativeWindowHandle: Long,
                width: Int,
                height: Int,
            ): Boolean = SolarLabVulkanBridge.onSurfaceCreated(
                handle = handle,
                nativeWindowHandle = nativeWindowHandle,
                width = width,
                height = height,
            )

            override fun onSurfaceChanged(
                handle: Long,
                nativeWindowHandle: Long,
                width: Int,
                height: Int,
            ): Boolean = SolarLabVulkanBridge.onSurfaceChanged(
                handle = handle,
                nativeWindowHandle = nativeWindowHandle,
                width = width,
                height = height,
            )

            override fun onSurfaceDestroyed(handle: Long) {
                SolarLabVulkanBridge.onSurfaceDestroyed(handle)
            }

            override fun lastError(handle: Long): String = SolarLabVulkanBridge.lastError(handle)
        },
        dispatchResult = { action -> mainHandler.post { action() } },
    )

    private var scenePacketPolicy = defaultScenePacketPolicy()
    private val minViewRadiusM: Double = 0.001 * PhysicalConstants.ASTRONOMICAL_UNIT_M
    private val maxViewRadiusM: Double = 150_000.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M
    private val cameraGestures = CameraGestureStateMachine(
        touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (interactionMode != SceneInteractionMode.NAVIGATE_AND_SELECT) return false
                pickBodyId(e.x, e.y) { bodyId ->
                    if (!released) {
                        val retainedSelection = retainSelectionOnEmptyPick(selectedBodyId, bodyId)
                        if (retainedSelection != selectedBodyId) {
                            selectedBodyId = retainedSelection
                            interactionListener?.onBodySelectionChanged(retainedSelection)
                        }
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (interactionMode != SceneInteractionMode.NAVIGATE_AND_SELECT) return false
                pickBodyId(e.x, e.y) pickResult@{ bodyId ->
                    if (released) return@pickResult
                    bodyId ?: return@pickResult
                    selectedBodyId = bodyId
                    observerMode = ObserverMode.FOLLOW_SELECTED
                    interactionListener?.onBodySelectionChanged(bodyId)
                    interactionListener?.onCameraNavigationModeChanged(ObserverMode.FOLLOW_SELECTED)
                    focusAndFrameBody(bodyId, ObserverMode.FOLLOW_SELECTED)
                }
                return true
            }
        },
    )

    init {
        holder.addCallback(this)
        isFocusable = true
        isClickable = true
        reportStatus(
            message = if (canAttemptVulkan()) {
                "Preparing Vulkan renderer."
            } else {
                "Vulkan runtime not available on this device/build."
            },
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released || !canAttemptVulkan()) {
            fatalInitCallback("Vulkan runtime or native library is unavailable.")
            return
        }
        val nativeWindowHandle = SolarLabVulkanBridge.acquireNativeWindow(holder.surface)
        if (nativeWindowHandle == 0L) {
            fatalInitCallback("Unable to acquire the Android native window for Vulkan.")
            return
        }
        val generation = ++surfaceGenerationCounter
        activeSurfaceGeneration = generation
        surfaceReady = false
        serializedRenderer.surfaceCreated(
            generation = generation,
            nativeWindowHandle = nativeWindowHandle,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            onResult = ::onSurfaceReadyResult,
        )
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val generation = activeSurfaceGeneration
        if (released || generation == 0L) return
        val nativeWindowHandle = SolarLabVulkanBridge.acquireNativeWindow(holder.surface)
        if (nativeWindowHandle == 0L) {
            fatalInitCallback("Unable to reacquire the Android native window after a surface change.")
            return
        }
        surfaceReady = false
        serializedRenderer.surfaceChanged(
            generation = generation,
            nativeWindowHandle = nativeWindowHandle,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            onResult = ::onSurfaceReadyResult,
        )
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val generation = activeSurfaceGeneration
        surfaceReady = false
        activeSurfaceGeneration = 0L
        if (generation != 0L) {
            serializedRenderer.surfaceDestroyed(generation)
        }
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        renderLatestScene()
    }

    override fun submitScene(frame: RenderSceneFrame) {
        latestScene = frame
        if (!isRuntimeBound()) {
            applyObserverTargetIfNeeded(frame)
            packetDirty = true
        }
        renderLatestScene()
    }

    override fun deferRendering(block: () -> Unit) {
        deferredRenderDepth += 1
        try {
            block()
        } finally {
            deferredRenderDepth -= 1
            if (deferredRenderDepth == 0 && renderDeferred) {
                renderDeferred = false
                renderLatestScene()
            }
        }
    }

    override fun resetCamera() {
        switchToFreeCameraForManualNavigation()
        if (isRuntimeBound()) {
            serializedRenderer.query(
                action = SolarLabVulkanBridge::resolveRuntimeHomeCamera,
                onResult = cameraResult@{ target ->
                    if (released || !isRuntimeBound()) return@cameraResult
                    if (target != null) {
                        animateCameraTo(target)
                    } else {
                        serializedRenderer.execute { handle ->
                            SolarLabVulkanBridge.resetRuntimeCamera(handle)
                        }
                        renderLatestScene()
                    }
                },
            )
            return
        }
        val target = CameraNavigation.scenarioFit(
            frame = latestScene,
            currentCameraState = cameraState,
            minViewRadiusM = minViewRadiusM,
            maxViewRadiusM = maxViewRadiusM,
        )
        if (target != null) {
            animateCameraTo(target)
        } else {
            animateCameraTo(CameraState())
        }
    }

    override fun zoomBy(scaleFactor: Float) {
        if (isRuntimeBound()) {
            val viewportWidth = width.coerceAtLeast(1)
            val viewportHeight = height.coerceAtLeast(1)
            serializedRenderer.execute { handle ->
                SolarLabVulkanBridge.zoomRuntimeCamera(
                    handle = handle,
                    scaleFactor = scaleFactor,
                    focusXPx = viewportWidth * 0.5f,
                    focusYPx = viewportHeight * 0.5f,
                    viewportWidthPx = viewportWidth,
                    viewportHeightPx = viewportHeight,
                )
            }
            renderLatestScene()
            return
        }
        val current = currentCameraState()
        val target = MultiscaleOrbitCameraController.zoomAroundViewportPoint(
            cameraState = current,
            scaleFactor = scaleFactor,
            focusXPx = width.coerceAtLeast(1) * 0.5f,
            focusYPx = height.coerceAtLeast(1) * 0.5f,
            viewportWidthPx = width.coerceAtLeast(1),
            viewportHeightPx = height.coerceAtLeast(1),
            minViewRadiusM = minViewRadiusM,
            maxViewRadiusM = maxViewRadiusM,
        )
        animateCameraTo(target)
    }

    override fun bindRuntimeSessionHandle(sessionHandle: Long) {
        if (runtimeSessionHandle == sessionHandle) return
        runtimeSessionHandle = sessionHandle
        latestPacket = null
        packetDirty = true
        syncRuntimeBinding()
        renderLatestScene()
    }

    override fun setProcessingMode(mode: RenderProcessingMode) {
        if (processingMode == mode) return
        processingMode = mode
        scenePacketPolicy = packetPolicyForMode(mode)
        if (isRuntimeBound()) {
            serializedRenderer.execute { handle ->
                SolarLabVulkanBridge.setRuntimeProcessingMode(handle, mode)
            }
            renderLatestScene()
            return
        }
        packetDirty = true
        renderLatestScene()
    }

    override fun setInteractionListener(listener: RenderInteractionListener?) {
        interactionListener = listener
    }

    override fun setInteractionMode(mode: SceneInteractionMode) {
        interactionMode = mode
        placementStartScreen = null
        placementGestureCancelledByTransform = false
        cameraGestures.onCancel()
    }

    override fun focusAndFrameBody(bodyId: String?, observerMode: ObserverMode) {
        selectedBodyId = bodyId
        this.observerMode = observerMode
        if (isRuntimeBound()) {
            serializedRenderer.execute { handle ->
                SolarLabVulkanBridge.setRuntimeSelectedBodyId(handle, bodyId)
                SolarLabVulkanBridge.setRuntimeObserverMode(handle, observerMode)
            }
            if (bodyId == null) {
                renderLatestScene()
                return
            }
            resolveBodyFrame(bodyId) frameResult@{ target ->
                if (released) return@frameResult
                if (target != null && selectedBodyId == bodyId) {
                    animateCameraTo(target)
                } else {
                    renderLatestScene()
                }
            }
            return
        }
        val target = bodyId?.let {
            CameraNavigation.frameBody(
                frame = latestScene,
                bodyId = it,
                currentCameraState = cameraState,
                minViewRadiusM = minViewRadiusM,
                maxViewRadiusM = maxViewRadiusM,
            )
        }
        if (target != null) {
            animateCameraTo(target)
        } else {
            applyObserverTargetIfNeeded(latestScene, snapToSuggestedRadius = observerMode != ObserverMode.FREE)
            onCameraChanged()
        }
    }

    override fun frameBody(bodyId: String) {
        selectedBodyId = bodyId
        if (isRuntimeBound()) {
            serializedRenderer.execute { handle ->
                SolarLabVulkanBridge.setRuntimeSelectedBodyId(handle, bodyId)
            }
        }
        resolveBodyFrame(bodyId) frameResult@{ target ->
            if (released) return@frameResult
            target?.let(::animateCameraTo)
        }
    }

    override fun setCameraScaleBand(scaleBand: CameraScaleBand) {
        val target = CameraNavigation.scalePreset(
            currentCameraState = currentCameraState(),
            scaleBand = scaleBand,
            minViewRadiusM = minViewRadiusM,
            maxViewRadiusM = maxViewRadiusM,
        )
        animateCameraTo(target)
    }

    override fun currentCameraScaleBand(): CameraScaleBand = currentCameraState().scaleBand()

    override fun setOnCameraScaleChangedListener(listener: ((CameraScaleBand) -> Unit)?) {
        cameraScaleChangedListener = listener
        lastReportedCameraScaleBand = null
        reportCameraScaleBand()
    }

    override fun setSelectedBodyId(bodyId: String?) {
        if (selectedBodyId == bodyId) return
        selectedBodyId = bodyId
        if (isRuntimeBound()) {
            serializedRenderer.execute { handle ->
                SolarLabVulkanBridge.setRuntimeSelectedBodyId(handle, bodyId)
            }
            renderLatestScene()
            return
        }
        applyObserverTargetIfNeeded(latestScene, snapToSuggestedRadius = observerMode != ObserverMode.FREE)
        packetDirty = true
        renderLatestScene()
    }

    override fun setObserverMode(mode: ObserverMode) {
        if (observerMode == mode) return
        observerMode = mode
        if (isRuntimeBound()) {
            serializedRenderer.execute { handle ->
                SolarLabVulkanBridge.setRuntimeObserverMode(handle, mode)
            }
            renderLatestScene()
            return
        }
        applyObserverTargetIfNeeded(latestScene, snapToSuggestedRadius = mode != ObserverMode.FREE)
        packetDirty = true
        renderLatestScene()
    }

    override fun setPlacementPlaneZ(worldZ: Double) {
        placementPlaneZ = worldZ
    }

    override fun setRenderLayerOptions(options: RenderLayerOptions) {
        if (renderLayerOptions == options) return
        renderLayerOptions = options
        if (isRuntimeBound()) {
            serializedRenderer.execute { handle ->
                SolarLabVulkanBridge.setRuntimeTraceLayerMode(handle, options.traceLayerMode)
            }
            renderLatestScene()
            return
        }
        packetDirty = true
        renderLatestScene()
    }

    override fun release() {
        if (released) return
        released = true
        cancelCameraTransition()
        surfaceReady = false
        activeSurfaceGeneration = 0L
        latestPacket = null
        packetDirty = true
        val wasRuntimeBound = runtimeSessionHandle != 0L
        serializedRenderer.release { handle ->
            if (wasRuntimeBound) {
                SolarLabVulkanBridge.unbindRuntimeSession(handle)
            }
        }
    }

    override fun onHostResume() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val refreshRateHz = display?.mode?.refreshRate?.takeIf { it >= 60f }
                ?: display?.refreshRate?.takeIf { it >= 60f }
                ?: 120f
            holder.surface?.takeIf(Surface::isValid)?.setFrameRate(
                refreshRateHz,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
            )
        }
    }

    override fun onHostPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface?.takeIf(Surface::isValid)?.setFrameRate(
                0f,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelCameraTransition()
                parent?.requestDisallowInterceptTouchEvent(true)
                placementGestureCancelledByTransform = false
                cameraGestures.onDown(event.pointerAt(0))
                return if (interactionMode == SceneInteractionMode.PLACE_BODY) {
                    handlePlacementTouch(event)
                } else {
                    gestureDetector.onTouchEvent(event)
                    true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelTapRecognition(event)
                if (cameraGestures.onPointerDown(event.pointers())) {
                    cancelPlacementForTransform(event)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (
                    interactionMode == SceneInteractionMode.PLACE_BODY &&
                    !placementGestureCancelledByTransform &&
                    !cameraGestures.isTransforming &&
                    event.pointerCount == 1
                ) {
                    return handlePlacementTouch(event)
                }
                if (
                    interactionMode == SceneInteractionMode.NAVIGATE_AND_SELECT &&
                    cameraGestures.acceptsTap &&
                    event.pointerCount == 1
                ) {
                    gestureDetector.onTouchEvent(event)
                }
                cameraGestures.onMove(
                    pointers = event.pointers(),
                    followActive = observerMode != ObserverMode.FREE,
                )?.let(::applyCameraGesture)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                cameraGestures.onPointerUp()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (
                    interactionMode == SceneInteractionMode.PLACE_BODY &&
                    !placementGestureCancelledByTransform
                ) {
                    handlePlacementTouch(event)
                } else if (
                    interactionMode == SceneInteractionMode.NAVIGATE_AND_SELECT &&
                    cameraGestures.acceptsTap
                ) {
                    gestureDetector.onTouchEvent(event)
                }
                cameraGestures.onUp()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (
                    interactionMode == SceneInteractionMode.PLACE_BODY &&
                    !placementGestureCancelledByTransform
                ) {
                    handlePlacementTouch(event)
                }
                cancelTapRecognition(event)
                cameraGestures.onCancel()
                placementStartScreen = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun MotionEvent.pointerAt(index: Int): CameraGesturePointer = CameraGesturePointer(
        id = getPointerId(index),
        xPx = getX(index),
        yPx = getY(index),
    )

    private fun MotionEvent.pointers(): List<CameraGesturePointer> =
        List(pointerCount) { index -> pointerAt(index) }

    private fun cancelTapRecognition(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source)
        cancel.action = MotionEvent.ACTION_CANCEL
        gestureDetector.onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun cancelPlacementForTransform(event: MotionEvent) {
        if (interactionMode != SceneInteractionMode.PLACE_BODY || placementGestureCancelledByTransform) {
            return
        }
        placementStartScreen?.let { activeStart ->
            dispatchPlacementUpdate(
                phase = PlacementGesturePhase.Cancelled,
                startScreen = activeStart,
                endScreen = event.x to event.y,
            )
        }
        placementStartScreen = null
        placementGestureCancelledByTransform = true
    }

    private fun handlePlacementTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                placementStartScreen = event.x to event.y
                dispatchPlacementUpdate(
                    phase = PlacementGesturePhase.Started,
                    startScreen = placementStartScreen ?: (event.x to event.y),
                    endScreen = event.x to event.y,
                )
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                dispatchPlacementUpdate(
                    phase = PlacementGesturePhase.Changed,
                    startScreen = placementStartScreen ?: (event.x to event.y),
                    endScreen = event.x to event.y,
                )
                return true
            }

            MotionEvent.ACTION_UP -> {
                dispatchPlacementUpdate(
                    phase = PlacementGesturePhase.Ended,
                    startScreen = placementStartScreen ?: (event.x to event.y),
                    endScreen = event.x to event.y,
                )
                placementStartScreen = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dispatchPlacementUpdate(
                    phase = PlacementGesturePhase.Cancelled,
                    startScreen = placementStartScreen ?: (event.x to event.y),
                    endScreen = event.x to event.y,
                )
                placementStartScreen = null
                return true
            }
        }
        return true
    }

    private fun dispatchPlacementUpdate(
        phase: PlacementGesturePhase,
        startScreen: Pair<Float, Float>,
        endScreen: Pair<Float, Float>,
    ) {
        val startWorld = screenToWorld(startScreen)
        val endWorld = screenToWorld(endScreen)
        val dx = endScreen.first - startScreen.first
        val dy = endScreen.second - startScreen.second
        interactionListener?.onPlacementGestureUpdate(
            PlacementGestureUpdate(
                phase = phase,
                startWorldPositionM = startWorld,
                endWorldPositionM = endWorld,
                gestureDistancePx = sqrt((dx * dx) + (dy * dy)),
            ),
        )
    }

    private fun applyCameraGesture(update: CameraGestureUpdate) {
        when (update) {
            is CameraGestureUpdate.Orbit -> {
                val focus = ObserverCameraResolver.resolveGestureFocus(
                    requestedXPx = update.focusXPx,
                    requestedYPx = update.focusYPx,
                    viewportWidthPx = width,
                    viewportHeightPx = height,
                    observerMode = observerMode,
                )
                if (isRuntimeBound()) {
                    val viewportWidth = width.coerceAtLeast(1)
                    val viewportHeight = height.coerceAtLeast(1)
                    serializedRenderer.execute { handle ->
                        SolarLabVulkanBridge.orbitRuntimeCamera(
                            handle = handle,
                            deltaXPx = update.deltaXPx,
                            deltaYPx = update.deltaYPx,
                            focusXPx = focus.xPx,
                            focusYPx = focus.yPx,
                            viewportWidthPx = viewportWidth,
                            viewportHeightPx = viewportHeight,
                        )
                    }
                    renderLatestScene()
                } else {
                    cameraState = MultiscaleOrbitCameraController.orbitAroundViewportPoint(
                        cameraState = cameraState,
                        deltaXPx = update.deltaXPx,
                        deltaYPx = update.deltaYPx,
                        focusXPx = focus.xPx,
                        focusYPx = focus.yPx,
                        viewportWidthPx = width.coerceAtLeast(1),
                        viewportHeightPx = height.coerceAtLeast(1),
                    )
                    onCameraChanged()
                }
            }

            is CameraGestureUpdate.PanAndZoom -> {
                if (update.detachFollow) {
                    switchToFreeCameraForManualNavigation()
                }
                val focus = ObserverCameraResolver.resolveGestureFocus(
                    requestedXPx = update.focusXPx,
                    requestedYPx = update.focusYPx,
                    viewportWidthPx = width,
                    viewportHeightPx = height,
                    observerMode = observerMode,
                )
                if (isRuntimeBound()) {
                    val viewportWidth = width.coerceAtLeast(1)
                    val viewportHeight = height.coerceAtLeast(1)
                    serializedRenderer.execute { handle ->
                        SolarLabVulkanBridge.panAndZoomRuntimeCamera(
                            handle = handle,
                            distanceXPx = update.distanceXPx,
                            distanceYPx = update.distanceYPx,
                            scaleFactor = update.scaleFactor,
                            focusXPx = focus.xPx,
                            focusYPx = focus.yPx,
                            viewportWidthPx = viewportWidth,
                            viewportHeightPx = viewportHeight,
                        )
                    }
                    renderLatestScene()
                } else {
                    var transformedCamera = cameraState
                    if (update.distanceXPx != 0f || update.distanceYPx != 0f) {
                        transformedCamera = MultiscaleOrbitCameraController.panByScreenDelta(
                            cameraState = transformedCamera,
                            distanceXPx = update.distanceXPx,
                            distanceYPx = update.distanceYPx,
                            viewportWidthPx = width.coerceAtLeast(1),
                            viewportHeightPx = height.coerceAtLeast(1),
                        )
                    }
                    if (update.scaleFactor != 1f) {
                        transformedCamera = MultiscaleOrbitCameraController.zoomAroundViewportPoint(
                            cameraState = transformedCamera,
                            scaleFactor = update.scaleFactor,
                            focusXPx = focus.xPx,
                            focusYPx = focus.yPx,
                            viewportWidthPx = width.coerceAtLeast(1),
                            viewportHeightPx = height.coerceAtLeast(1),
                            minViewRadiusM = minViewRadiusM,
                            maxViewRadiusM = maxViewRadiusM,
                        )
                    }
                    cameraState = transformedCamera
                    onCameraChanged()
                }
            }
        }
    }

    private fun pickBodyId(
        screenXPx: Float,
        screenYPx: Float,
        onResult: (String?) -> Unit,
    ) {
        if (!isRuntimeBound()) {
            onResult(
                SceneInteractionMath.pickBodyIdAtScreenPoint(
                    frame = latestScene,
                    cameraState = cameraState,
                    viewportWidthPx = width.coerceAtLeast(1),
                    viewportHeightPx = height.coerceAtLeast(1),
                    screenXPx = screenXPx,
                    screenYPx = screenYPx,
                ),
            )
            return
        }
        val viewportWidth = width.coerceAtLeast(1)
        val viewportHeight = height.coerceAtLeast(1)
        serializedRenderer.query(
            requireSurface = true,
            action = { handle ->
                SolarLabVulkanBridge.pickRuntimeBodyId(
                    handle = handle,
                    screenXPx = screenXPx,
                    screenYPx = screenYPx,
                    viewportWidthPx = viewportWidth,
                    viewportHeightPx = viewportHeight,
                )
            },
            onResult = onResult,
        )
    }

    private fun resolveBodyFrame(
        bodyId: String,
        onResult: (CameraState?) -> Unit,
    ) {
        if (isRuntimeBound()) {
            serializedRenderer.query(
                action = { handle -> SolarLabVulkanBridge.resolveRuntimeBodyFrame(handle, bodyId) },
                onResult = onResult,
            )
            return
        }
        onResult(
            CameraNavigation.frameBody(
                frame = latestScene,
                bodyId = bodyId,
                currentCameraState = cameraState,
                minViewRadiusM = minViewRadiusM,
                maxViewRadiusM = maxViewRadiusM,
            ),
        )
    }

    private fun switchToFreeCameraForManualNavigation() {
        if (observerMode == ObserverMode.FREE) return
        observerMode = ObserverMode.FREE
        interactionListener?.onCameraNavigationModeChanged(ObserverMode.FREE)
        if (isRuntimeBound()) {
            serializedRenderer.execute { handle ->
                SolarLabVulkanBridge.setRuntimeObserverMode(handle, ObserverMode.FREE)
            }
        }
    }

    private fun currentCameraState(): CameraState = cameraState

    private fun animateCameraTo(target: CameraState) {
        cancelCameraTransition()
        val startCamera = currentCameraState()
        cameraTransition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CAMERA_TRANSITION_DURATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animator ->
                applyCameraState(
                    CameraNavigation.interpolate(
                        start = startCamera,
                        target = target,
                        fraction = animator.animatedValue as Float,
                    ),
                )
            }
            start()
        }
    }

    private fun applyCameraState(nextCameraState: CameraState) {
        cameraState = nextCameraState.sanitized()
        if (isRuntimeBound()) {
            val nextCamera = cameraState
            serializedRenderer.execute { handle ->
                SolarLabVulkanBridge.setCamera(
                    handle = handle,
                    centerX = nextCamera.centerM.x,
                    centerY = nextCamera.centerM.y,
                    centerZ = nextCamera.centerM.z,
                    viewRadiusM = nextCamera.viewRadiusM,
                    yawRadians = nextCamera.yawRadians,
                    pitchRadians = nextCamera.pitchRadians,
                )
            }
            renderLatestScene()
        } else {
            onCameraChanged()
        }
    }

    private fun cancelCameraTransition() {
        cameraTransition?.cancel()
        cameraTransition = null
    }

    private fun reportCameraScaleBand() {
        val scaleBand = currentCameraState().scaleBand()
        if (lastReportedCameraScaleBand == scaleBand) return
        lastReportedCameraScaleBand = scaleBand
        cameraScaleChangedListener?.invoke(scaleBand)
    }

    private fun applyObserverTargetIfNeeded(
        frame: RenderSceneFrame,
        snapToSuggestedRadius: Boolean = false,
    ) {
        val target = ObserverCameraResolver.resolveCameraTarget(
            frame = frame,
            selectedBodyId = selectedBodyId,
            observerMode = observerMode,
        ) ?: return
        cameraState = MultiscaleOrbitCameraController.retarget(
            currentCameraState = cameraState,
            targetCenterM = target.centerM,
            suggestedViewRadiusM = target.suggestedViewRadiusM,
            minViewRadiusM = minViewRadiusM,
            maxViewRadiusM = maxViewRadiusM,
            snapToSuggestedRadius = snapToSuggestedRadius,
        )
    }

    private fun syncRuntimeBinding() {
        if (released) return
        val sessionHandle = runtimeSessionHandle
        val nextProcessingMode = processingMode
        val nextObserverMode = observerMode
        val nextSelectedBodyId = selectedBodyId
        val nextTraceLayerMode = renderLayerOptions.traceLayerMode
        val nextCamera = cameraState
        serializedRenderer.execute { handle ->
            if (sessionHandle != 0L) {
                SolarLabVulkanBridge.bindRuntimeSession(handle, sessionHandle)
                SolarLabVulkanBridge.setRuntimeProcessingMode(handle, nextProcessingMode)
                SolarLabVulkanBridge.setRuntimeObserverMode(handle, nextObserverMode)
                SolarLabVulkanBridge.setRuntimeSelectedBodyId(handle, nextSelectedBodyId)
                SolarLabVulkanBridge.setRuntimeTraceLayerMode(handle, nextTraceLayerMode)
            } else {
                SolarLabVulkanBridge.unbindRuntimeSession(handle)
                SolarLabVulkanBridge.setCamera(
                    handle = handle,
                    centerX = nextCamera.centerM.x,
                    centerY = nextCamera.centerM.y,
                    centerZ = nextCamera.centerM.z,
                    viewRadiusM = nextCamera.viewRadiusM,
                    yawRadians = nextCamera.yawRadians,
                    pitchRadians = nextCamera.pitchRadians,
                )
            }
        }
    }

    private fun canAttemptVulkan(): Boolean = capabilities.supportsVulkan && SolarLabVulkanBridge.isRuntimeAvailable()

    private fun isRuntimeBound(): Boolean = runtimeSessionHandle != 0L

    private fun onCameraChanged() {
        reportCameraScaleBand()
        if (isRuntimeBound()) {
            renderLatestScene()
            return
        }
        packetDirty = true
        pushCamera()
        renderLatestScene()
    }

    private fun pushCamera() {
        if (!surfaceReady || isRuntimeBound() || released) return
        val nextCamera = cameraState
        serializedRenderer.execute { handle ->
            SolarLabVulkanBridge.setCamera(
                handle = handle,
                centerX = nextCamera.centerM.x,
                centerY = nextCamera.centerM.y,
                centerZ = nextCamera.centerM.z,
                viewRadiusM = nextCamera.viewRadiusM,
                yawRadians = nextCamera.yawRadians,
                pitchRadians = nextCamera.pitchRadians,
            )
        }
    }

    private fun renderLatestScene() {
        if (!surfaceReady || released) return
        if (deferredRenderDepth > 0) {
            renderDeferred = true
            return
        }
        val generation = activeSurfaceGeneration
        if (isRuntimeBound()) {
            serializedRenderer.query(
                requireSurface = true,
                action = { handle -> renderNativeFrame(handle) },
                onResult = { result -> onNativeFrameRendered(generation, result, cameraLabel = null) },
            )
            return
        }

        pushCamera()
        if (packetDirty || latestPacket == null) {
            latestPacket = NativeScenePacket.fromScene(
                frame = latestScene.withLayerOptions(renderLayerOptions),
                selectedBodyId = selectedBodyId,
                cameraState = cameraState,
                viewportWidthPx = width.coerceAtLeast(1),
                viewportHeightPx = height.coerceAtLeast(1),
                policy = effectiveScenePacketPolicy(),
            )
            packetDirty = false
        }
        val packet = latestPacket ?: return
        val cameraLabel = cameraTelemetryLabel()
        serializedRenderer.query(
            requireSurface = true,
            action = { handle ->
                SolarLabVulkanBridge.submitScene(handle, packet)
                renderNativeFrame(handle)
            },
            onResult = { result -> onNativeFrameRendered(generation, result, cameraLabel) },
        )
    }

    private fun renderNativeFrame(handle: Long): NativeRenderResult {
        val rendered = SolarLabVulkanBridge.render(handle)
        return NativeRenderResult(
            rendered = rendered,
            error = if (rendered) null else SolarLabVulkanBridge.lastError(handle),
            backendLabel = SolarLabVulkanBridge.backendLabel(handle),
            sceneSummary = SolarLabVulkanBridge.sceneSummary(handle),
            cameraState = SolarLabVulkanBridge.cameraState(handle),
        )
    }

    private fun onNativeFrameRendered(
        generation: Long,
        result: NativeRenderResult?,
        cameraLabel: String?,
    ) {
        if (released || generation == 0L || generation != activeSurfaceGeneration || result == null) return
        if (!result.rendered) {
            fatalInitCallback(result.error ?: "Native Vulkan rendering failed.")
            return
        }
        result.cameraState?.let { cameraState = it }
        val detail = cameraLabel?.let { "$it · ${result.sceneSummary}" } ?: result.sceneSummary
        reportStatus("${result.backendLabel} active. $detail")
        reportCameraScaleBand()
    }

    private fun onSurfaceReadyResult(result: SerializedVulkanRenderer.SurfaceResult) {
        if (released || result.generation != activeSurfaceGeneration) return
        surfaceReady = result.isReady
        if (!result.isReady) {
            fatalInitCallback(result.error ?: "Native Vulkan surface initialisation failed.")
            return
        }
        packetDirty = true
        syncRuntimeBinding()
        serializedRenderer.query(
            action = SolarLabVulkanBridge::backendLabel,
            onResult = { backendLabel ->
                if (!released && result.generation == activeSurfaceGeneration && backendLabel != null) {
                    reportStatus("$backendLabel active.")
                }
            },
        )
        renderLatestScene()
    }

    private fun screenToWorld(screen: Pair<Float, Float>): Vector3d = SceneInteractionMath.screenToWorldPoint(
        screenXPx = screen.first,
        screenYPx = screen.second,
        cameraState = cameraState,
        viewportWidthPx = width.coerceAtLeast(1),
        viewportHeightPx = height.coerceAtLeast(1),
        worldZ = placementPlaneZ,
    )

    private fun cameraTelemetryLabel(): String {
        val safeCamera = cameraState.sanitized()
        val pitchDegrees = Math.toDegrees(safeCamera.pitchRadians).roundToInt()
        val yawDegrees = Math.toDegrees(safeCamera.yawRadians).roundToInt()
        return "${safeCamera.scaleBand().label} orbit ${pitchDegrees}° / yaw ${yawDegrees}°"
    }

    private fun effectiveScenePacketPolicy(): ScenePacketBuildPolicy {
        val base = scenePacketPolicy
        return when (cameraState.sanitized().scaleBand()) {
            CameraScaleBand.CLOSE -> base.copy(
                nearTracerExtentFactor = 1.75,
                mediumTracerExtentFactor = 4.5,
                farTracerExtentFactor = 12.0,
                nearTracerBudget = base.nearTracerBudget * 2,
                mediumTracerBudget = (base.mediumTracerBudget / 2).coerceAtLeast(2_048),
                farTracerBudget = (base.farTracerBudget / 4).coerceAtLeast(2_048),
                trailSimplificationTolerancePx = (base.trailSimplificationTolerancePx * 0.75).coerceAtLeast(1.0),
                maxTrailVerticesPerTrail = (base.maxTrailVerticesPerTrail * 2).coerceAtMost(512),
            )

            CameraScaleBand.LOCAL -> base.copy(
                nearTracerExtentFactor = 1.6,
                mediumTracerExtentFactor = 5.5,
                farTracerExtentFactor = 18.0,
                nearTracerBudget = (base.nearTracerBudget * 3) / 2,
                mediumTracerBudget = base.mediumTracerBudget,
                farTracerBudget = (base.farTracerBudget / 2).coerceAtLeast(4_096),
            )

            CameraScaleBand.SYSTEM -> base

            CameraScaleBand.WIDE -> base.copy(
                nearTracerExtentFactor = 1.25,
                mediumTracerExtentFactor = 7.5,
                farTracerExtentFactor = 36.0,
                nearTracerBudget = (base.nearTracerBudget / 2).coerceAtLeast(2_048),
                mediumTracerBudget = base.mediumTracerBudget,
                farTracerBudget = base.farTracerBudget * 2,
                trailSimplificationTolerancePx = base.trailSimplificationTolerancePx * 1.5,
                maxTrailVerticesPerTrail = (base.maxTrailVerticesPerTrail / 2).coerceAtLeast(96),
            )

            CameraScaleBand.DEEP -> base.copy(
                nearTracerExtentFactor = 1.0,
                mediumTracerExtentFactor = 8.0,
                farTracerExtentFactor = 48.0,
                nearTracerBudget = (base.nearTracerBudget / 4).coerceAtLeast(1_024),
                mediumTracerBudget = (base.mediumTracerBudget / 2).coerceAtLeast(4_096),
                farTracerBudget = base.farTracerBudget * 3,
                trailSimplificationTolerancePx = base.trailSimplificationTolerancePx * 2.0,
                maxTrailVerticesPerTrail = (base.maxTrailVerticesPerTrail / 3).coerceAtLeast(64),
            )
        }
    }

    private fun reportStatus(message: String) {
        statusCallback(
            RenderBackendStatus(
                requested = RenderBackend.VULKAN,
                active = RenderBackend.VULKAN,
                isHardwareAccelerated = true,
                message = message,
            ),
        )
    }

    private fun emptyScene(): RenderSceneFrame = RenderSceneFrame(
        epochSeconds = 0.0,
        authoritativeBodies = emptyList(),
        tracerBodies = emptyList(),
        trails = emptyList(),
    )

    private fun defaultScenePacketPolicy(): ScenePacketBuildPolicy = ScenePacketBuildPolicy()

    private fun packetPolicyForMode(mode: RenderProcessingMode): ScenePacketBuildPolicy = when (mode) {
        RenderProcessingMode.DEFAULT -> defaultScenePacketPolicy()
        RenderProcessingMode.LOW -> ScenePacketBuildPolicy(
            nearTracerBudget = 2_048,
            mediumTracerBudget = 4_096,
            farTracerBudget = 6_144,
            trailSimplificationTolerancePx = 6.0,
            maxTrailVerticesPerTrail = 96,
        )
    }

    private companion object {
        private const val CAMERA_TRANSITION_DURATION_MS = 240L
    }

    private data class NativeRenderResult(
        val rendered: Boolean,
        val error: String?,
        val backendLabel: String,
        val sceneSummary: String,
        val cameraState: CameraState?,
    )
}

internal fun retainSelectionOnEmptyPick(currentBodyId: String?, pickedBodyId: String?): String? =
    pickedBodyId ?: currentBodyId
