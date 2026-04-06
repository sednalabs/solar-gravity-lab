package com.sednalabs.solarlab.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.min

data class RenderFrame(
    val sceneRevision: String,
    val epochSeconds: Double,
    val observerModeCode: Int,
    val directionalLightCount: Int,
    val camera: RenderCamera,
    val bodies: List<RenderBody>,
    val tracers: List<RenderTracer>,
    val trails: List<RenderTrail>,
)

data class RenderCamera(
    val frameOriginX: Double,
    val frameOriginY: Double,
    val frameOriginZ: Double,
    val positionFromOriginX: Float,
    val positionFromOriginY: Float,
    val positionFromOriginZ: Float,
    val targetFromOriginX: Float,
    val targetFromOriginY: Float,
    val targetFromOriginZ: Float,
    val upX: Float,
    val upY: Float,
    val upZ: Float,
    val verticalFovDegrees: Float,
    val exposure: Float,
)

data class RenderBody(
    val bodyId: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val radiusM: Float,
    val selected: Boolean,
    val colorR: Float,
    val colorG: Float,
    val colorB: Float,
    val colorA: Float,
)

data class RenderTracer(
    val x: Float,
    val y: Float,
    val z: Float,
    val sizePx: Float,
    val colorR: Float,
    val colorG: Float,
    val colorB: Float,
    val colorA: Float,
)

data class RenderTrail(
    val sourceBodyId: String,
    val colorR: Float,
    val colorG: Float,
    val colorB: Float,
    val colorA: Float,
    val points: List<RenderPoint>,
    val headHighlighted: Boolean,
)

data class RenderPoint(
    val x: Float,
    val y: Float,
    val z: Float,
)

internal object VulkanPacketRenderFrameDecoder {
    private const val BODY_STRIDE_BYTES = 140
    private const val TRACER_STRIDE_BYTES = 32
    private const val TRAIL_VERTEX_STRIDE_BYTES = 20
    private const val TRAIL_SPAN_STRIDE_BYTES = 132
    private const val BODY_ID_OFFSET_BYTES = 40
    private const val BODY_ID_MAX_BYTES = 96
    private const val BODY_ID_LENGTH_OFFSET_BYTES = 136
    private const val TRAIL_SOURCE_BODY_ID_OFFSET_BYTES = 32
    private const val TRAIL_SOURCE_BODY_ID_MAX_BYTES = 96
    private const val TRAIL_SOURCE_BODY_ID_LENGTH_OFFSET_BYTES = 128

    // Decodes native-side packet layout into immutable Kotlin domain models.
    // Stride constants and slice math are intentionally colocated with decode paths
    // so packet schema drift stays contained to this file.
    fun decode(packet: NativeVulkanScenePacket): RenderFrame {
        val bodies = decodeBodies(packet.bodyInstances, packet.bodyCount)
        val tracers = decodeTracers(packet.tracerInstances, packet.tracerCount)
        val vertices = decodeTrailVertices(packet.trailVertices, packet.trailVertexCount)
        val trails = decodeTrailSpans(packet.trailSpans, packet.trailSpanCount, vertices)
        return RenderFrame(
            sceneRevision = packet.sceneRevision,
            epochSeconds = packet.epochSeconds,
            observerModeCode = packet.observerMode,
            directionalLightCount = packet.directionalLightCount,
            camera = RenderCamera(
                frameOriginX = packet.camera.frameOriginX,
                frameOriginY = packet.camera.frameOriginY,
                frameOriginZ = packet.camera.frameOriginZ,
                positionFromOriginX = packet.camera.positionFromOriginX,
                positionFromOriginY = packet.camera.positionFromOriginY,
                positionFromOriginZ = packet.camera.positionFromOriginZ,
                targetFromOriginX = packet.camera.targetFromOriginX,
                targetFromOriginY = packet.camera.targetFromOriginY,
                targetFromOriginZ = packet.camera.targetFromOriginZ,
                upX = packet.camera.upX,
                upY = packet.camera.upY,
                upZ = packet.camera.upZ,
                verticalFovDegrees = packet.camera.verticalFovDegrees,
                exposure = packet.camera.exposure,
            ),
            bodies = bodies,
            tracers = tracers,
            trails = trails,
        )
    }

    private fun decodeBodies(buffer: ByteBuffer?, count: Int): List<RenderBody> {
        // Native packet buffers may be absent; callers treat null/empty as an intentional no-op draw.
        val ordered = preparedBuffer(buffer) ?: return emptyList()
        val available = min(count.coerceAtLeast(0), ordered.limit() / BODY_STRIDE_BYTES)
        return List(available) { index ->
            val base = index * BODY_STRIDE_BYTES
            RenderBody(
                bodyId = decodeIdentifier(
                    ordered = ordered,
                    bytesOffset = base + BODY_ID_OFFSET_BYTES,
                    maxBytes = BODY_ID_MAX_BYTES,
                    lengthOffset = base + BODY_ID_LENGTH_OFFSET_BYTES,
                ),
                x = ordered.getFloat(base + 0),
                y = ordered.getFloat(base + 4),
                z = ordered.getFloat(base + 8),
                radiusM = ordered.getFloat(base + 12),
                colorR = ordered.getFloat(base + 16),
                colorG = ordered.getFloat(base + 20),
                colorB = ordered.getFloat(base + 24),
                colorA = ordered.getFloat(base + 28),
                selected = ordered.getInt(base + 36) != 0,
            )
        }
    }

    private fun decodeTracers(buffer: ByteBuffer?, count: Int): List<RenderTracer> {
        val ordered = preparedBuffer(buffer) ?: return emptyList()
        val available = min(count.coerceAtLeast(0), ordered.limit() / TRACER_STRIDE_BYTES)
        return List(available) { index ->
            val base = index * TRACER_STRIDE_BYTES
            RenderTracer(
                x = ordered.getFloat(base + 0),
                y = ordered.getFloat(base + 4),
                z = ordered.getFloat(base + 8),
                colorR = ordered.getFloat(base + 12),
                colorG = ordered.getFloat(base + 16),
                colorB = ordered.getFloat(base + 20),
                colorA = ordered.getFloat(base + 24),
                sizePx = ordered.getFloat(base + 28),
            )
        }
    }

    private fun decodeTrailVertices(buffer: ByteBuffer?, count: Int): List<RenderPoint> {
        val ordered = preparedBuffer(buffer) ?: return emptyList()
        val available = min(count.coerceAtLeast(0), ordered.limit() / TRAIL_VERTEX_STRIDE_BYTES)
        return List(available) { index ->
            val base = index * TRAIL_VERTEX_STRIDE_BYTES
            RenderPoint(
                x = ordered.getFloat(base + 8),
                y = ordered.getFloat(base + 12),
                z = ordered.getFloat(base + 16),
            )
        }
    }

    private fun decodeTrailSpans(
        buffer: ByteBuffer?,
        count: Int,
        vertices: List<RenderPoint>,
    ): List<RenderTrail> {
        val ordered = preparedBuffer(buffer) ?: return emptyList()
        val available = min(count.coerceAtLeast(0), ordered.limit() / TRAIL_SPAN_STRIDE_BYTES)
        return List(available) { index ->
            val base = index * TRAIL_SPAN_STRIDE_BYTES
            val offset = ordered.getInt(base + 0).coerceAtLeast(0)
            val pointCount = ordered.getInt(base + 4).coerceAtLeast(0)
            val points = if (offset >= vertices.size || pointCount == 0) {
                emptyList()
            } else {
                vertices.subList(offset, min(offset + pointCount, vertices.size))
            }
            RenderTrail(
                sourceBodyId = decodeIdentifier(
                    ordered = ordered,
                    bytesOffset = base + TRAIL_SOURCE_BODY_ID_OFFSET_BYTES,
                    maxBytes = TRAIL_SOURCE_BODY_ID_MAX_BYTES,
                    lengthOffset = base + TRAIL_SOURCE_BODY_ID_LENGTH_OFFSET_BYTES,
                ),
                colorR = ordered.getFloat(base + 8),
                colorG = ordered.getFloat(base + 12),
                colorB = ordered.getFloat(base + 16),
                colorA = ordered.getFloat(base + 20),
                points = points,
                headHighlighted = ordered.getInt(base + 28) != 0,
            )
        }
    }

    private fun preparedBuffer(buffer: ByteBuffer?): ByteBuffer? {
        if (buffer == null) return null
        // Duplicate keeps native order and avoids mutating caller cursor/state.
        return buffer.duplicate().order(ByteOrder.nativeOrder())
    }

    private fun decodeIdentifier(
        ordered: ByteBuffer,
        bytesOffset: Int,
        maxBytes: Int,
        lengthOffset: Int,
    ): String {
        val requestedLength = ordered.getInt(lengthOffset).coerceIn(0, maxBytes)
        if (requestedLength == 0) {
            return ""
        }
        val availableLength = min(requestedLength, (ordered.limit() - bytesOffset).coerceAtLeast(0))
        if (availableLength == 0) {
            return ""
        }
        val bytes = ByteArray(availableLength)
        val view = ordered.duplicate().order(ByteOrder.nativeOrder())
        view.position(bytesOffset)
        view.get(bytes, 0, availableLength)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
