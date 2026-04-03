package com.graciousgazelles.solarlab.feature.lab.render

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.render.core.CameraState
import com.graciousgazelles.solarlab.render.core.NativeScenePacket
import com.graciousgazelles.solarlab.render.core.ObserverCameraResolver
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderBackend
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame
import com.graciousgazelles.solarlab.render.core.SceneInteractionMath
import com.graciousgazelles.solarlab.render.core.ScenePacketBuildPolicy
import kotlin.math.max
import kotlin.math.sqrt

internal class SolarSystemVulkanSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val statusCallback: (RenderBackendStatus) -> Unit,
    private val fatalInitCallback: (String) -> Unit,
) : SurfaceView(context, attrs), SurfaceHolder.Callback2, SolarRenderSurface {

    private val capabilities = RenderDeviceCapabilities.query(context)
    private var rendererHandle: Long = 0L
    private var surfaceReady: Boolean = false
    private var latestScene: RenderSceneFrame = emptyScene()
    private var latestPacket: NativeScenePacket? = null
    private var packetDirty: Boolean = true
    private var cameraState: CameraState = CameraState()
    private var interactionListener: RenderInteractionListener? = null
    private var interactionMode: SceneInteractionMode = SceneInteractionMode.NAVIGATE_AND_SELECT
    private var selectedBodyId: String? = null
    private var observerMode: ObserverMode = ObserverMode.FREE
    private var placementStartScreen: Pair<Float, Float>? = null

    private val scenePacketPolicy = ScenePacketBuildPolicy()
    private val minViewRadiusM: Double = 0.001 * PhysicalConstants.ASTRONOMICAL_UNIT_M
    private val maxViewRadiusM: Double = 150_000.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (interactionMode == SceneInteractionMode.PLACE_BODY) {
                    return false
                }
                val scaleFactor = detector.scaleFactor
                if (scaleFactor > 0f) {
                    cameraState = cameraState.copy(
                        viewRadiusM = (cameraState.viewRadiusM / scaleFactor.toDouble()).coerceIn(minViewRadiusM, maxViewRadiusM),
                    )
                    onCameraChanged()
                }
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
                val bodyId = SceneInteractionMath.pickBodyIdAtScreenPoint(
                    frame = latestScene,
                    cameraState = cameraState,
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
                val metersPerPixel = currentMetersPerPixel(cameraState.viewRadiusM)
                cameraState = cameraState.copy(
                    centerM = Vector3d(
                        x = cameraState.centerM.x + distanceX * metersPerPixel,
                        y = cameraState.centerM.y - distanceY * metersPerPixel,
                        z = cameraState.centerM.z,
                    ),
                )
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
        applyObserverTargetIfNeeded(frame)
        packetDirty = true
        renderLatestScene()
    }

    override fun resetCamera() {
        cameraState = CameraState()
        onCameraChanged()
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
        applyObserverTargetIfNeeded(latestScene, snapToSuggestedRadius = observerMode != ObserverMode.FREE)
        packetDirty = true
        renderLatestScene()
    }

    override fun setObserverMode(mode: ObserverMode) {
        observerMode = mode
        applyObserverTargetIfNeeded(latestScene, snapToSuggestedRadius = mode != ObserverMode.FREE)
        packetDirty = true
        renderLatestScene()
    }

    override fun release() {
        surfaceReady = false
        latestPacket = null
        packetDirty = true
        SolarLabVulkanBridge.onSurfaceDestroyed(rendererHandle)
        SolarLabVulkanBridge.destroyRenderer(rendererHandle)
        rendererHandle = 0L
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
                val startWorld = screenToWorld(start)
                val endWorld = screenToWorld(event.x to event.y)
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
        )
    }

    private fun ensureRenderer(): Boolean {
        if (!canAttemptVulkan()) {
            fatalInitCallback("Vulkan backend requested, but the runtime or native library is unavailable.")
            return false
        }
        if (rendererHandle != 0L) return true
        rendererHandle = SolarLabVulkanBridge.createRenderer(context.assets)
        if (rendererHandle == 0L) {
            fatalInitCallback("Failed to create native Vulkan renderer.")
            return false
        }
        return true
    }

    private fun canAttemptVulkan(): Boolean = capabilities.supportsVulkan && SolarLabVulkanBridge.isRuntimeAvailable()

    private fun onCameraChanged() {
        packetDirty = true
        pushCamera()
        renderLatestScene()
    }

    private fun pushCamera() {
        if (rendererHandle == 0L || !surfaceReady) return
        SolarLabVulkanBridge.setCamera(
            handle = rendererHandle,
            centerX = cameraState.centerM.x,
            centerY = cameraState.centerM.y,
            centerZ = cameraState.centerM.z,
            viewRadiusM = cameraState.viewRadiusM,
        )
    }

    private fun renderLatestScene() {
        if (rendererHandle == 0L || !surfaceReady) return
        pushCamera()
        if (packetDirty || latestPacket == null) {
            latestPacket = NativeScenePacket.fromScene(
                frame = latestScene,
                cameraState = cameraState,
                viewportWidthPx = width.coerceAtLeast(1),
                viewportHeightPx = height.coerceAtLeast(1),
                policy = scenePacketPolicy,
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
                "${SolarLabVulkanBridge.backendLabel(rendererHandle)} active. " +
                    SolarLabVulkanBridge.sceneSummary(rendererHandle)
            )
        }
    }

    private fun currentMetersPerPixel(viewRadiusM: Double): Double {
        val minDimension = max(1, minOf(width, height))
        return (2.0 * viewRadiusM) / minDimension
    }

    private fun screenToWorld(screen: Pair<Float, Float>): Vector3d = SceneInteractionMath.screenToWorldPoint(
        screenXPx = screen.first,
        screenYPx = screen.second,
        cameraState = cameraState,
        viewportWidthPx = width.coerceAtLeast(1),
        viewportHeightPx = height.coerceAtLeast(1),
        worldZ = 0.0,
    )

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
}
