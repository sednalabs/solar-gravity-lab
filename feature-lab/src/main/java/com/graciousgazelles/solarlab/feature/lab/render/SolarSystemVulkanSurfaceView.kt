package com.graciousgazelles.solarlab.feature.lab.render

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.render.core.CameraScaleBand
import com.graciousgazelles.solarlab.render.core.CameraState
import com.graciousgazelles.solarlab.render.core.NativeScenePacket
import com.graciousgazelles.solarlab.render.core.ObserverCameraResolver
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.OrbitCameraMath
import com.graciousgazelles.solarlab.render.core.RenderBackend
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus
import com.graciousgazelles.solarlab.render.core.RenderLayerOptions
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame
import com.graciousgazelles.solarlab.render.core.SceneInteractionMath
import com.graciousgazelles.solarlab.render.core.ScenePacketBuildPolicy
import com.graciousgazelles.solarlab.render.core.withLayerOptions
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class SolarSystemVulkanSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val statusCallback: (RenderBackendStatus) -> Unit,
    private val fatalInitCallback: (String) -> Unit,
) : SurfaceView(context, attrs), SurfaceHolder.Callback2, SolarRenderSurface {

    private data class OrbitGestureState(
        val centroidX: Float,
        val centroidY: Float,
    )

    private val capabilities = RenderDeviceCapabilities.query(context)
    private var rendererHandle: Long = 0L
    private var surfaceReady: Boolean = false
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
    private var orbitGestureState: OrbitGestureState? = null
    private var runtimeSessionHandle: Long = 0L

    private var scenePacketPolicy = defaultScenePacketPolicy()
    private val minViewRadiusM: Double = 0.001 * PhysicalConstants.ASTRONOMICAL_UNIT_M
    private val maxViewRadiusM: Double = 150_000.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                return zoomByInternal(detector.scaleFactor)
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (interactionMode != SceneInteractionMode.NAVIGATE_AND_SELECT) return false
                val bodyId = if (isRuntimeBound()) {
                    SolarLabVulkanBridge.pickRuntimeBodyId(
                        handle = rendererHandle,
                        screenXPx = e.x,
                        screenYPx = e.y,
                        viewportWidthPx = width.coerceAtLeast(1),
                        viewportHeightPx = height.coerceAtLeast(1),
                    )
                } else {
                    SceneInteractionMath.pickBodyIdAtScreenPoint(
                        frame = latestScene,
                        cameraState = cameraState,
                        viewportWidthPx = width.coerceAtLeast(1),
                        viewportHeightPx = height.coerceAtLeast(1),
                        screenXPx = e.x,
                        screenYPx = e.y,
                    )
                }
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
                if (e2.pointerCount > 1) return false
                if (isRuntimeBound()) {
                    if (observerMode != ObserverMode.FREE) return false
                    SolarLabVulkanBridge.panRuntimeCamera(
                        handle = rendererHandle,
                        distanceXPx = distanceX,
                        distanceYPx = distanceY,
                        viewportWidthPx = width.coerceAtLeast(1),
                        viewportHeightPx = height.coerceAtLeast(1),
                    )
                    renderLatestScene()
                    return true
                }
                if (ObserverCameraResolver.isCameraLocked(latestScene, selectedBodyId, observerMode)) return false
                val frame = OrbitCameraMath.frame(
                    cameraState = cameraState,
                    viewportWidthPx = width.coerceAtLeast(1),
                    viewportHeightPx = height.coerceAtLeast(1),
                )
                cameraState = cameraState.copy(
                    centerM = cameraState.centerM +
                        frame.rightM * (distanceX * frame.metersPerPixel) -
                        frame.upM * (distanceY * frame.metersPerPixel),
                ).sanitized()
                onCameraChanged()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (interactionMode != SceneInteractionMode.NAVIGATE_AND_SELECT) return false
                resetCamera()
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
        if (!ensureRenderer()) {
            return
        }
        surfaceReady = SolarLabVulkanBridge.onSurfaceCreated(rendererHandle, holder.surface, width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (!surfaceReady) {
            fatalInitCallback(SolarLabVulkanBridge.lastError(rendererHandle))
            return
        }
        syncRuntimeBinding()
        reportStatus("${SolarLabVulkanBridge.backendLabel(rendererHandle)} active.")
        renderLatestScene()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (rendererHandle == 0L) return
        surfaceReady = SolarLabVulkanBridge.onSurfaceChanged(rendererHandle, holder.surface, width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (!surfaceReady) {
            fatalInitCallback(SolarLabVulkanBridge.lastError(rendererHandle))
            return
        }
        packetDirty = true
        renderLatestScene()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        SolarLabVulkanBridge.onSurfaceDestroyed(rendererHandle)
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

    override fun resetCamera() {
        if (isRuntimeBound()) {
            SolarLabVulkanBridge.resetRuntimeCamera(rendererHandle)
            renderLatestScene()
            return
        }
        cameraState = CameraState()
        onCameraChanged()
    }

    override fun zoomBy(scaleFactor: Float) {
        zoomByInternal(scaleFactor)
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
            SolarLabVulkanBridge.setRuntimeProcessingMode(rendererHandle, mode)
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
        orbitGestureState = null
    }

    override fun focusAndFrameBody(bodyId: String?, observerMode: ObserverMode) {
        selectedBodyId = bodyId
        this.observerMode = observerMode
        if (isRuntimeBound()) {
            SolarLabVulkanBridge.setRuntimeSelectedBodyId(rendererHandle, bodyId)
            SolarLabVulkanBridge.setRuntimeObserverMode(rendererHandle, observerMode)
            renderLatestScene()
            return
        }
        latestScene?.let { frame ->
            applyObserverTargetIfNeeded(frame, snapToSuggestedRadius = observerMode != ObserverMode.FREE)
            packetDirty = true
            renderLatestScene()
        }
    }

    override fun setSelectedBodyId(bodyId: String?) {
        selectedBodyId = bodyId
        if (isRuntimeBound()) {
            SolarLabVulkanBridge.setRuntimeSelectedBodyId(rendererHandle, bodyId)
            renderLatestScene()
            return
        }
        applyObserverTargetIfNeeded(latestScene, snapToSuggestedRadius = observerMode != ObserverMode.FREE)
        packetDirty = true
        renderLatestScene()
    }

    override fun setObserverMode(mode: ObserverMode) {
        observerMode = mode
        if (isRuntimeBound()) {
            SolarLabVulkanBridge.setRuntimeObserverMode(rendererHandle, mode)
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
            SolarLabVulkanBridge.setRuntimeTraceLayerMode(rendererHandle, options.traceLayerMode)
            renderLatestScene()
            return
        }
        packetDirty = true
        renderLatestScene()
    }

    override fun release() {
        surfaceReady = false
        latestPacket = null
        packetDirty = true
        if (rendererHandle != 0L && runtimeSessionHandle != 0L) {
            SolarLabVulkanBridge.unbindRuntimeSession(rendererHandle)
        }
        SolarLabVulkanBridge.onSurfaceDestroyed(rendererHandle)
        SolarLabVulkanBridge.destroyRenderer(rendererHandle)
        rendererHandle = 0L
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
        if (interactionMode == SceneInteractionMode.PLACE_BODY) {
            val multiTouchActive = event.pointerCount >= 2 || orbitGestureState != null
            if (multiTouchActive) {
                placementStartScreen?.let { activeStart ->
                    dispatchPlacementUpdate(
                        phase = PlacementGesturePhase.Cancelled,
                        startScreen = activeStart,
                        endScreen = event.x to event.y,
                    )
                }
                placementStartScreen = null
                val orbitHandled = handleOrbitTouch(event)
                val scaled = scaleDetector.onTouchEvent(event)
                return orbitHandled || scaled || true
            }
            return handlePlacementTouch(event)
        }
        val multiTouchActive = event.pointerCount >= 2 || orbitGestureState != null
        val orbitHandled = if (multiTouchActive) handleOrbitTouch(event) else false
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = if (!multiTouchActive) {
            gestureDetector.onTouchEvent(event)
        } else {
            false
        }
        return orbitHandled || scaled || gestured || super.onTouchEvent(event)
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

    private fun handleOrbitTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount >= 2) {
                    orbitGestureState = OrbitGestureState(
                        centroidX = (event.getX(0) + event.getX(1)) * 0.5f,
                        centroidY = (event.getY(0) + event.getY(1)) * 0.5f,
                    )
                }
                return event.pointerCount >= 2
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount < 2) {
                    return false
                }
                val previous = orbitGestureState ?: OrbitGestureState(
                    centroidX = (event.getX(0) + event.getX(1)) * 0.5f,
                    centroidY = (event.getY(0) + event.getY(1)) * 0.5f,
                )
                val centroidX = (event.getX(0) + event.getX(1)) * 0.5f
                val centroidY = (event.getY(0) + event.getY(1)) * 0.5f
                orbitGestureState = OrbitGestureState(centroidX, centroidY)

                val deltaX = centroidX - previous.centroidX
                val deltaY = centroidY - previous.centroidY
                if (abs(deltaX) < 0.25f && abs(deltaY) < 0.25f) {
                    return false
                }
                if (isRuntimeBound()) {
                    if (observerMode != ObserverMode.FREE) return false
                    SolarLabVulkanBridge.orbitRuntimeCamera(rendererHandle, deltaX, deltaY)
                    renderLatestScene()
                    return true
                }
                cameraState = cameraState.copy(
                    yawRadians = cameraState.yawRadians - (deltaX * ORBIT_YAW_RADIANS_PER_PIXEL),
                    pitchRadians = cameraState.pitchRadians - (deltaY * ORBIT_PITCH_RADIANS_PER_PIXEL),
                ).sanitized()
                onCameraChanged()
                return true
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                orbitGestureState = null
                return false
            }
        }
        return false
    }

    private fun zoomByInternal(scaleFactor: Float): Boolean {
        if (scaleFactor <= 0f) {
            return false
        }
        if (isRuntimeBound()) {
            SolarLabVulkanBridge.zoomRuntimeCamera(rendererHandle, scaleFactor)
            renderLatestScene()
            return true
        }
        cameraState = cameraState.copy(
            viewRadiusM = (cameraState.viewRadiusM / scaleFactor.toDouble()).coerceIn(minViewRadiusM, maxViewRadiusM),
        ).sanitized()
        onCameraChanged()
        return true
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
        cameraState = cameraState.copy(
            centerM = target.centerM,
            viewRadiusM = if (snapToSuggestedRadius) {
                target.suggestedViewRadiusM.coerceIn(minViewRadiusM, maxViewRadiusM)
            } else {
                cameraState.viewRadiusM
            },
        ).sanitized()
    }

    private fun ensureRenderer(): Boolean {
        if (!canAttemptVulkan()) {
            fatalInitCallback("Vulkan runtime or native library is unavailable.")
            return false
        }
        if (rendererHandle != 0L) return true
        rendererHandle = SolarLabVulkanBridge.createRenderer(context.assets)
        if (rendererHandle == 0L) {
            fatalInitCallback("Failed to create native Vulkan renderer.")
            return false
        }
        syncRuntimeBinding()
        return true
    }

    private fun syncRuntimeBinding() {
        if (rendererHandle == 0L) return
        if (runtimeSessionHandle != 0L) {
            SolarLabVulkanBridge.bindRuntimeSession(rendererHandle, runtimeSessionHandle)
            SolarLabVulkanBridge.setRuntimeProcessingMode(rendererHandle, processingMode)
            SolarLabVulkanBridge.setRuntimeObserverMode(rendererHandle, observerMode)
            SolarLabVulkanBridge.setRuntimeSelectedBodyId(rendererHandle, selectedBodyId)
            SolarLabVulkanBridge.setRuntimeTraceLayerMode(rendererHandle, renderLayerOptions.traceLayerMode)
        } else {
            SolarLabVulkanBridge.unbindRuntimeSession(rendererHandle)
            pushCamera()
        }
    }

    private fun canAttemptVulkan(): Boolean = capabilities.supportsVulkan && SolarLabVulkanBridge.isRuntimeAvailable()

    private fun isRuntimeBound(): Boolean = runtimeSessionHandle != 0L

    private fun onCameraChanged() {
        if (isRuntimeBound()) {
            renderLatestScene()
            return
        }
        packetDirty = true
        pushCamera()
        renderLatestScene()
    }

    private fun pushCamera() {
        if (rendererHandle == 0L || !surfaceReady || isRuntimeBound()) return
        SolarLabVulkanBridge.setCamera(
            handle = rendererHandle,
            centerX = cameraState.centerM.x,
            centerY = cameraState.centerM.y,
            centerZ = cameraState.centerM.z,
            viewRadiusM = cameraState.viewRadiusM,
            yawRadians = cameraState.yawRadians,
            pitchRadians = cameraState.pitchRadians,
        )
    }

    private fun renderLatestScene() {
        if (rendererHandle == 0L || !surfaceReady) return
        if (isRuntimeBound()) {
            if (!SolarLabVulkanBridge.render(rendererHandle)) {
                fatalInitCallback(SolarLabVulkanBridge.lastError(rendererHandle))
                return
            }
            reportStatus(
                "${SolarLabVulkanBridge.backendLabel(rendererHandle)} active. ${SolarLabVulkanBridge.sceneSummary(rendererHandle)}"
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
        latestPacket?.let { SolarLabVulkanBridge.submitScene(rendererHandle, it) }
        if (!SolarLabVulkanBridge.render(rendererHandle)) {
            fatalInitCallback(SolarLabVulkanBridge.lastError(rendererHandle))
            return
        }
        latestPacket?.let {
            reportStatus(
                "${SolarLabVulkanBridge.backendLabel(rendererHandle)} active. ${cameraTelemetryLabel()} · " +
                    SolarLabVulkanBridge.sceneSummary(rendererHandle)
            )
        }
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
        private const val ORBIT_YAW_RADIANS_PER_PIXEL: Double = 0.0075
        private const val ORBIT_PITCH_RADIANS_PER_PIXEL: Double = 0.0050
    }
}
