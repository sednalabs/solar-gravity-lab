package com.sednalabs.solarlab.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanPacketRenderFrameDecoderTest {
    @Test
    fun decode_preservesBodyIdentifiers() {
        val bodyInstances = ByteBuffer.allocateDirect(BODY_STRIDE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                val bodyId = "earth".toByteArray(StandardCharsets.UTF_8)
                putFloat(1f)
                putFloat(2f)
                putFloat(3f)
                putFloat(4f)
                putFloat(0.5f)
                putFloat(0.6f)
                putFloat(0.7f)
                putFloat(1.0f)
                putFloat(0f)
                putInt(1)
                position(BODY_ID_OFFSET_BYTES)
                put(bodyId)
                position(BODY_ID_LENGTH_OFFSET_BYTES)
                putInt(bodyId.size)
                flip()
            }

        val frame = VulkanPacketRenderFrameDecoder.decode(
            NativeVulkanScenePacket(
                packetHandle = 7L,
                sceneRevision = "scene=alpha",
                epochSeconds = 0.0,
                observerMode = 0,
                timelineSemantics = 1,
                camera = NativeVulkanCameraPacket(
                    frameOriginX = 0.0,
                    frameOriginY = 0.0,
                    frameOriginZ = 0.0,
                    positionFromOriginX = 0f,
                    positionFromOriginY = 0f,
                    positionFromOriginZ = 0f,
                    targetFromOriginX = 0f,
                    targetFromOriginY = 0f,
                    targetFromOriginZ = 0f,
                    upX = 0f,
                    upY = 1f,
                    upZ = 0f,
                    verticalFovDegrees = 60f,
                    exposure = 1f,
                ),
                bodyCount = 1,
                tracerCount = 0,
                trailSpanCount = 0,
                trailVertexCount = 0,
                directionalLightCount = 0,
                diagnostics = NativeRenderDiagnostics(
                    frameNumber = 1L,
                    cpuExtractMs = 0f,
                    gpuUploadMs = 0f,
                    droppedFrames = 0,
                ),
                provenanceSource = null,
                provenanceVersion = null,
                provenanceManifestId = null,
                provenanceManifestDigest = null,
                provenancePackageDigest = null,
                bodyInstances = bodyInstances,
                tracerInstances = null,
                trailSpans = null,
                trailVertices = null,
                directionalLights = null,
            )
        )

        val body = frame.bodies.single()
        assertEquals("earth", body.bodyId)
        assertTrue(body.selected)
        assertEquals(4f, body.radiusM)
    }

    @Test
    fun decode_preservesTrailSourceBodyIdAndHighlightFlag() {
        val trailVertices = ByteBuffer.allocateDirect(TRAIL_VERTEX_STRIDE_BYTES * 2)
            .order(ByteOrder.nativeOrder())
            .apply {
                putInt(0)
                putInt(0)
                putFloat(1f)
                putFloat(2f)
                putFloat(3f)

                putInt(0)
                putInt(1)
                putFloat(4f)
                putFloat(5f)
                putFloat(6f)
                flip()
            }

        val trailSpans = ByteBuffer.allocateDirect(TRAIL_SPAN_STRIDE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                val sourceBodyId = "moon".toByteArray(StandardCharsets.UTF_8)
                putInt(0)
                putInt(2)
                putFloat(0.2f)
                putFloat(0.4f)
                putFloat(0.8f)
                putFloat(1.0f)
                putInt(256)
                putInt(1)
                putInt(2)
                position(TRAIL_SOURCE_BODY_ID_OFFSET_BYTES)
                put(sourceBodyId)
                position(TRAIL_SOURCE_BODY_ID_LENGTH_OFFSET_BYTES)
                putInt(sourceBodyId.size)
                flip()
            }

        val frame = VulkanPacketRenderFrameDecoder.decode(
            NativeVulkanScenePacket(
                packetHandle = 9L,
                sceneRevision = "scene=alpha",
                epochSeconds = 0.0,
                observerMode = 0,
                timelineSemantics = 1,
                camera = NativeVulkanCameraPacket(
                    frameOriginX = 0.0,
                    frameOriginY = 0.0,
                    frameOriginZ = 0.0,
                    positionFromOriginX = 0f,
                    positionFromOriginY = 0f,
                    positionFromOriginZ = 0f,
                    targetFromOriginX = 0f,
                    targetFromOriginY = 0f,
                    targetFromOriginZ = 0f,
                    upX = 0f,
                    upY = 1f,
                    upZ = 0f,
                    verticalFovDegrees = 60f,
                    exposure = 1f,
                ),
                bodyCount = 0,
                tracerCount = 0,
                trailSpanCount = 1,
                trailVertexCount = 2,
                directionalLightCount = 0,
                diagnostics = NativeRenderDiagnostics(
                    frameNumber = 1L,
                    cpuExtractMs = 0f,
                    gpuUploadMs = 0f,
                    droppedFrames = 0,
                ),
                provenanceSource = null,
                provenanceVersion = null,
                provenanceManifestId = null,
                provenanceManifestDigest = null,
                provenancePackageDigest = null,
                bodyInstances = null,
                tracerInstances = null,
                trailSpans = trailSpans,
                trailVertices = trailVertices,
                directionalLights = null,
            )
        )

        val trail = frame.trails.single()
        assertEquals("moon", trail.sourceBodyId)
        assertEquals(RenderTrailFamily.Prediction, trail.family)
        assertTrue(trail.headHighlighted)
        assertEquals(2, trail.points.size)
        assertEquals(4f, trail.points.last().x)
    }

    private companion object {
        private const val BODY_STRIDE_BYTES = 140
        private const val BODY_ID_OFFSET_BYTES = 40
        private const val BODY_ID_LENGTH_OFFSET_BYTES = 136
        private const val TRAIL_VERTEX_STRIDE_BYTES = 20
        private const val TRAIL_SPAN_STRIDE_BYTES = 136
        private const val TRAIL_SOURCE_BODY_ID_OFFSET_BYTES = 36
        private const val TRAIL_SOURCE_BODY_ID_LENGTH_OFFSET_BYTES = 132
    }
}
