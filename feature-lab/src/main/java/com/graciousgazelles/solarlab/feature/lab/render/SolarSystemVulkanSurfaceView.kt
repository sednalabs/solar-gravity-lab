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
    private val hardwareSummaryPrefix = listOf(
        capabilities.hardwareSummary(),
        SolarLabVulkanBridge.cpuCapabilitySummary(),
    ).joinToString(separator = " | ")
    private val hardwareDetailsPrefix = listOf(
        capabilities.hardwareDetails(),
        SolarLabVulkanBridge.cpuCapabilityDetails(),
    ).joinToString(separator = "\n")
    private var rendererHandle: Long = 0L
    private var surfaceReady: Boolean = false
    private var rendererHardwareSummary: String = "gpu=vulkan-pending"
    private var rendererHardwareDetails: String = "GPU renderer details pending"
    private var latestScene: RenderSceneFrame = emptyScene()
    private var latestPacket: NativeScenePacket? = null
    private var packetDirty: Boolean = true
    private var lastSubmittedFrameRevision: Long = Long.MIN_VALUE
    private var lastSubmittedEpochSeconds: Double = 0.0
    private var cameraState: CameraState = CameraState()
    private var interactionListener: RenderInteractionListener? = null
    private var interactionMode: SceneInteractionMode = SceneInteractionMode.NAVIGATE_AND_SELECT
    private var processingMode: RenderProcessingMode = RenderProcessingMode.DEFAULT
    private var tracerMutualGravityEnabled: Boolean = false
    private var selectedBodyId: String? = null
    private var observerMode: ObserverMode = ObserverMode.FREE
    private var placementStartScreen: Pair<Float, Float>? = null

    private var scenePacketPolicy = defaultScenePacketPolicy()
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
        resetSubmissionState()
        refreshRendererHardwareSummary()
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
        refreshRendererHardwareSummary()
        resetSubmissionState()
        renderLatestScene()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        resetSubmissionState()
        rendererHardwareSummary = "gpu=vulkan-surface-destroyed"
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

    override fun setTracerMutualGravityEnabled(enabled: Boolean) {
        tracerMutualGravityEnabled = enabled
        renderLatestScene()
    }

    override fun resetCamera() {
        cameraState = CameraState()
        onCameraChanged()
    }

    override fun setProcessingMode(mode: RenderProcessingMode) {
        if (processingMode == mode) return
        processingMode = mode
        scenePacketPolicy = packetPolicyForMode(mode)
        packetDirty = true
        renderLatestScene()
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
        resetSubmissionState()
        rendererHardwareSummary = "gpu=vulkan-renderer-released"
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
            fatalInitCallback("Vulkan runtime or native library is unavailable.")
            return false
        }
        if (rendererHandle != 0L) return true
        rendererHandle = SolarLabVulkanBridge.createRenderer(context.assets)
        if (rendererHandle == 0L) {
            fatalInitCallback("Failed to create native Vulkan renderer.")
            return false
        }
        rendererHardwareSummary = "gpu=vulkan-created-awaiting-surface"
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
                selectedBodyId = selectedBodyId,
                cameraState = cameraState,
                viewportWidthPx = width.coerceAtLeast(1),
                viewportHeightPx = height.coerceAtLeast(1),
                policy = scenePacketPolicy,
            )
            packetDirty = false
        }
        latestPacket?.let { packet ->
            SolarLabVulkanBridge.submitScene(rendererHandle, packet)
            val simulationAdvanceSeconds = when {
                packet.sourceRevision == lastSubmittedFrameRevision -> 0.0
                lastSubmittedFrameRevision == Long.MIN_VALUE -> 0.0
                packet.sourceRevision < lastSubmittedFrameRevision -> 0.0
                latestScene.epochSeconds < lastSubmittedEpochSeconds -> 0.0
                else -> (latestScene.epochSeconds - lastSubmittedEpochSeconds).coerceAtLeast(0.0)
            }
            SolarLabVulkanBridge.setFrameState(
                handle = rendererHandle,
                frameState = packet.toFrameState(
                    epochSeconds = latestScene.epochSeconds,
                    simulationAdvanceSeconds = simulationAdvanceSeconds,
                    includeTracerMutualGravity = tracerMutualGravityEnabled,
                ),
            )
            lastSubmittedFrameRevision = packet.sourceRevision
            lastSubmittedEpochSeconds = latestScene.epochSeconds
        }
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
                hardwareSummary = listOf(
                    hardwareSummaryPrefix,
                    rendererHardwareSummary,
                ).joinToString(separator = " | "),
                hardwareDetails = listOf(
                    hardwareDetailsPrefix,
                    rendererHardwareDetails,
                ).joinToString(separator = "\n"),
                sceneSummary = if (rendererHandle != 0L && surfaceReady) {
                    SolarLabVulkanBridge.sceneSummary(rendererHandle)
                } else {
                    null
                },
            ),
        )
    }

    private fun refreshRendererHardwareSummary() {
        rendererHardwareSummary = if (rendererHandle != 0L && surfaceReady) {
            SolarLabVulkanBridge.hardwareSummary(rendererHandle)
        } else {
            "gpu=vulkan-pending"
        }
        rendererHardwareDetails = if (rendererHandle != 0L && surfaceReady) {
            SolarLabVulkanBridge.hardwareDetails(rendererHandle)
        } else {
            "GPU renderer details pending"
        }
    }

    private fun resetSubmissionState() {
        latestPacket = null
        packetDirty = true
        lastSubmittedFrameRevision = Long.MIN_VALUE
        lastSubmittedEpochSeconds = 0.0
        rendererHardwareDetails = "GPU renderer details pending"
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
}
