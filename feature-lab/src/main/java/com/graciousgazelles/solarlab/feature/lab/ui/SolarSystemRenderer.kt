package com.graciousgazelles.solarlab.feature.lab.ui

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.render.core.CameraState
import com.graciousgazelles.solarlab.render.core.RenderBody
import com.graciousgazelles.solarlab.render.core.RenderBodyKind
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame
import com.graciousgazelles.solarlab.render.core.RenderTrail
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

internal class SolarSystemRenderer : GLSurfaceView.Renderer {

    private var scene: RenderSceneFrame = RenderSceneFrame(
        epochSeconds = 0.0,
        authoritativeBodies = emptyList(),
        tracerBodies = emptyList(),
        trails = emptyList(),
    )

    private var surfaceWidth: Int = 1
    private var surfaceHeight: Int = 1

    private var cameraState: CameraState = CameraState()
    private var selectedBodyId: String? = null
    private var followBodyId: String? = null

    private val minViewRadiusM: Double = 0.001 * PhysicalConstants.ASTRONOMICAL_UNIT_M
    private val maxViewRadiusM: Double = 150_000.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M
    private val bodyRadiusExaggeration: Double = 6_000.0

    private var bodyProgram: Int = 0
    private var lineProgram: Int = 0
    private var bodyPositionHandle: Int = -1
    private var bodyPointSizeHandle: Int = -1
    private var bodyColorHandle: Int = -1
    private var linePositionHandle: Int = -1
    private var lineColorHandle: Int = -1

    private var bodyScratch: FloatArray = FloatArray(0)
    private var bodyBuffer: FloatBuffer = allocateFloatBuffer(1)
    private var trailScratch: FloatArray = FloatArray(0)
    private var trailBuffer: FloatBuffer = allocateFloatBuffer(1)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        bodyProgram = buildProgram(BODY_VERTEX_SHADER, BODY_FRAGMENT_SHADER)
        lineProgram = buildProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)

        bodyPositionHandle = GLES20.glGetAttribLocation(bodyProgram, "a_Position")
        bodyPointSizeHandle = GLES20.glGetAttribLocation(bodyProgram, "a_PointSize")
        bodyColorHandle = GLES20.glGetAttribLocation(bodyProgram, "a_Color")
        linePositionHandle = GLES20.glGetAttribLocation(lineProgram, "a_Position")
        lineColorHandle = GLES20.glGetAttribLocation(lineProgram, "a_Color")

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val localScene: RenderSceneFrame
        val localCameraState: CameraState
        synchronized(this) {
            localScene = scene
            localCameraState = cameraState
        }

        if (localScene.authoritativeBodies.isEmpty() && localScene.tracerBodies.isEmpty()) {
            return
        }

        drawTrails(localScene.trails, localCameraState)
        drawBodies(localScene.authoritativeBodies, localScene.tracerBodies, localCameraState)
    }

    @Synchronized
    fun submitScene(scene: RenderSceneFrame) {
        this.scene = scene
        applyFollowTargetIfNeeded(scene)
    }

    @Synchronized
    fun cameraState(): CameraState = cameraState

    @Synchronized
    fun setSelectedBodyId(bodyId: String?) {
        selectedBodyId = bodyId
    }

    @Synchronized
    fun setFollowBodyId(bodyId: String?) {
        followBodyId = bodyId
        applyFollowTargetIfNeeded(scene)
    }

    @Synchronized
    fun panByPixels(distanceX: Float, distanceY: Float) {
        if (followBodyId != null) return
        val metersPerPixel = currentMetersPerPixel(cameraState.viewRadiusM)
        cameraState = cameraState.copy(
            centerM = Vector3d(
                x = cameraState.centerM.x + distanceX * metersPerPixel,
                y = cameraState.centerM.y - distanceY * metersPerPixel,
                z = cameraState.centerM.z,
            ),
        )
    }

    @Synchronized
    fun zoomByScale(scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        cameraState = cameraState.copy(
            viewRadiusM = (cameraState.viewRadiusM / scaleFactor.toDouble()).coerceIn(minViewRadiusM, maxViewRadiusM),
        )
    }

    @Synchronized
    fun resetCamera() {
        cameraState = CameraState()
    }

    @Synchronized
    private fun applyFollowTargetIfNeeded(frame: RenderSceneFrame) {
        val targetId = followBodyId ?: return
        val target = (frame.authoritativeBodies + frame.tracerBodies).firstOrNull { it.id == targetId } ?: return
        cameraState = cameraState.copy(centerM = target.positionM)
    }

    private fun drawBodies(
        authoritativeBodies: List<RenderBody>,
        tracerBodies: List<RenderBody>,
        cameraState: CameraState,
    ) {
        val metersPerPixel = currentMetersPerPixel(cameraState.viewRadiusM)
        val allBodies = authoritativeBodies.size + tracerBodies.size
        if (allBodies == 0) return

        ensureBodyCapacity(allBodies * BODY_VERTEX_STRIDE_FLOATS)
        var cursor = 0

        for (body in authoritativeBodies) {
            cursor = appendBody(body, metersPerPixel, cameraState, cursor, tracerAlpha = 1.0f)
        }
        for (body in tracerBodies) {
            cursor = appendBody(body, metersPerPixel, cameraState, cursor, tracerAlpha = 0.35f)
        }

        if (cursor == 0) return

        bodyBuffer.clear()
        bodyBuffer.put(bodyScratch, 0, cursor)
        bodyBuffer.position(0)

        GLES20.glUseProgram(bodyProgram)
        val strideBytes = BODY_VERTEX_STRIDE_FLOATS * FLOAT_SIZE_BYTES

        bodyBuffer.position(0)
        GLES20.glVertexAttribPointer(bodyPositionHandle, 2, GLES20.GL_FLOAT, false, strideBytes, bodyBuffer)
        GLES20.glEnableVertexAttribArray(bodyPositionHandle)

        bodyBuffer.position(2)
        GLES20.glVertexAttribPointer(bodyPointSizeHandle, 1, GLES20.GL_FLOAT, false, strideBytes, bodyBuffer)
        GLES20.glEnableVertexAttribArray(bodyPointSizeHandle)

        bodyBuffer.position(3)
        GLES20.glVertexAttribPointer(bodyColorHandle, 4, GLES20.GL_FLOAT, false, strideBytes, bodyBuffer)
        GLES20.glEnableVertexAttribArray(bodyColorHandle)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, cursor / BODY_VERTEX_STRIDE_FLOATS)

        GLES20.glDisableVertexAttribArray(bodyPositionHandle)
        GLES20.glDisableVertexAttribArray(bodyPointSizeHandle)
        GLES20.glDisableVertexAttribArray(bodyColorHandle)
    }

    private fun appendBody(
        body: RenderBody,
        metersPerPixel: Double,
        cameraState: CameraState,
        cursorStart: Int,
        tracerAlpha: Float,
    ): Int {
        val clip = toClipSpace(body.positionM - cameraState.centerM, cameraState.viewRadiusM) ?: return cursorStart
        val baseRadiusPx = body.radiusM / metersPerPixel
        val minimumPx = when {
            body.isMassive -> 3.0
            body.kind == RenderBodyKind.DWARF_PLANET -> 2.5
            else -> 1.5
        }
        val visualRadiusPx = max(minimumPx, baseRadiusPx * bodyRadiusExaggeration).coerceIn(1.5, 96.0)
        val alpha = if (body.isMassive) 1.0f else tracerAlpha
        val isSelected = body.id == selectedBodyId
        val selectedScale = if (isSelected) 1.4 else 1.0

        var cursor = cursorStart
        bodyScratch[cursor++] = clip.first
        bodyScratch[cursor++] = clip.second
        bodyScratch[cursor++] = (visualRadiusPx * selectedScale).toFloat()
        bodyScratch[cursor++] = colorChannel(body.colorArgb, 16)
        bodyScratch[cursor++] = colorChannel(body.colorArgb, 8)
        bodyScratch[cursor++] = colorChannel(body.colorArgb, 0)
        bodyScratch[cursor++] = if (isSelected) 1.0f else alpha
        return cursor
    }

    private fun drawTrails(
        trails: List<RenderTrail>,
        cameraState: CameraState,
    ) {
        if (trails.isEmpty()) return

        GLES20.glUseProgram(lineProgram)
        GLES20.glLineWidth(1f)
        val strideBytes = LINE_VERTEX_STRIDE_FLOATS * FLOAT_SIZE_BYTES

        for (trail in trails) {
            if (trail.pointsM.size < 2) continue
            ensureTrailCapacity(trail.pointsM.size * LINE_VERTEX_STRIDE_FLOATS)

            var cursor = 0
            val r = colorChannel(trail.colorArgb, 16)
            val g = colorChannel(trail.colorArgb, 8)
            val b = colorChannel(trail.colorArgb, 0)
            for (point in trail.pointsM) {
                val clip = toClipSpace(point - cameraState.centerM, cameraState.viewRadiusM) ?: continue
                trailScratch[cursor++] = clip.first
                trailScratch[cursor++] = clip.second
                trailScratch[cursor++] = r
                trailScratch[cursor++] = g
                trailScratch[cursor++] = b
                trailScratch[cursor++] = trail.alpha
            }

            if (cursor < LINE_VERTEX_STRIDE_FLOATS * 2) continue

            trailBuffer.clear()
            trailBuffer.put(trailScratch, 0, cursor)
            trailBuffer.position(0)

            trailBuffer.position(0)
            GLES20.glVertexAttribPointer(linePositionHandle, 2, GLES20.GL_FLOAT, false, strideBytes, trailBuffer)
            GLES20.glEnableVertexAttribArray(linePositionHandle)

            trailBuffer.position(2)
            GLES20.glVertexAttribPointer(lineColorHandle, 4, GLES20.GL_FLOAT, false, strideBytes, trailBuffer)
            GLES20.glEnableVertexAttribArray(lineColorHandle)

            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, cursor / LINE_VERTEX_STRIDE_FLOATS)
        }

        GLES20.glDisableVertexAttribArray(linePositionHandle)
        GLES20.glDisableVertexAttribArray(lineColorHandle)
    }

    private fun toClipSpace(
        relativePositionM: Vector3d,
        viewRadiusM: Double,
    ): Pair<Float, Float>? {
        val minDimension = min(surfaceWidth, surfaceHeight).coerceAtLeast(1).toDouble()
        val halfSpanX = viewRadiusM * (surfaceWidth.toDouble() / minDimension)
        val halfSpanY = viewRadiusM * (surfaceHeight.toDouble() / minDimension)

        if (halfSpanX == 0.0 || halfSpanY == 0.0) {
            return null
        }

        val x = relativePositionM.x / halfSpanX
        val y = relativePositionM.y / halfSpanY

        if (x < -1.25 || x > 1.25 || y < -1.25 || y > 1.25) {
            return null
        }

        return x.toFloat() to y.toFloat()
    }

    private fun currentMetersPerPixel(viewRadiusM: Double): Double {
        val minDimension = min(surfaceWidth, surfaceHeight).coerceAtLeast(1)
        return (2.0 * viewRadiusM) / minDimension
    }

    private fun colorChannel(argb: Int, shift: Int): Float = ((argb shr shift) and 0xFF) / 255f

    private fun ensureBodyCapacity(requiredFloats: Int) {
        if (bodyScratch.size >= requiredFloats) return
        val capacity = requiredFloats.coerceAtLeast(bodyScratch.size * 2).coerceAtLeast(256)
        bodyScratch = FloatArray(capacity)
        bodyBuffer = allocateFloatBuffer(capacity)
    }

    private fun ensureTrailCapacity(requiredFloats: Int) {
        if (trailScratch.size >= requiredFloats) return
        val capacity = requiredFloats.coerceAtLeast(trailScratch.size * 2).coerceAtLeast(256)
        trailScratch = FloatArray(capacity)
        trailBuffer = allocateFloatBuffer(capacity)
    }

    private fun buildProgram(vertexShaderSource: String, fragmentShaderSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)

        val program = GLES20.glCreateProgram()
        check(program != 0) { "Failed to create OpenGL program." }

        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] == GLES20.GL_TRUE) {
            val infoLog = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            "OpenGL program link failed: $infoLog"
        }

        return program
    }

    private fun compileShader(type: Int, shaderSource: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Failed to create shader of type $type" }

        GLES20.glShaderSource(shader, shaderSource)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        check(compileStatus[0] == GLES20.GL_TRUE) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            "OpenGL shader compile failed: $infoLog"
        }

        return shader
    }

    private companion object {
        const val FLOAT_SIZE_BYTES: Int = 4
        const val BODY_VERTEX_STRIDE_FLOATS: Int = 7
        const val LINE_VERTEX_STRIDE_FLOATS: Int = 6

        fun allocateFloatBuffer(floatCapacity: Int): FloatBuffer = ByteBuffer.allocateDirect(floatCapacity * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        const val BODY_VERTEX_SHADER: String = """
            attribute vec2 a_Position;
            attribute float a_PointSize;
            attribute vec4 a_Color;
            varying vec4 v_Color;

            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
                gl_PointSize = a_PointSize;
                v_Color = a_Color;
            }
        """

        const val BODY_FRAGMENT_SHADER: String = """
            precision mediump float;
            varying vec4 v_Color;

            void main() {
                vec2 centered = gl_PointCoord - vec2(0.5, 0.5);
                if (dot(centered, centered) > 0.25) {
                    discard;
                }
                gl_FragColor = v_Color;
            }
        """

        const val LINE_VERTEX_SHADER: String = """
            attribute vec2 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;

            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
                v_Color = a_Color;
            }
        """

        const val LINE_FRAGMENT_SHADER: String = """
            precision mediump float;
            varying vec4 v_Color;

            void main() {
                gl_FragColor = v_Color;
            }
        """
    }
}
