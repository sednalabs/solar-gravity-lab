package com.sednalabs.solarlab.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanPacketRenderFrameDecoderTest {
    @Test
    fun sceneBodyKind_decodesExplicitCometTaxonomy() {
        assertEquals(RuntimeSceneBodyKind.Comet, RuntimeSceneBodyKind.fromNativeCode(5))
    }

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
                putInt(RuntimeSceneBodyKind.Planet.nativeCode)
                putInt(BODY_APPEARANCE_OFFSET_BYTES + 0, RenderCelestialMaterial.Terrestrial.nativeCode)
                putInt(
                    BODY_APPEARANCE_OFFSET_BYTES + 4,
                    RenderAppearanceProvenance.CuratedVisualGuide.nativeCode,
                )
                putFloat(BODY_APPEARANCE_OFFSET_BYTES + 8, 0.397f)
                putFloat(BODY_APPEARANCE_OFFSET_BYTES + 12, 0.918f)
                putFloat(BODY_APPEARANCE_OFFSET_BYTES + 16, 0f)
                putInt(BODY_APPEARANCE_OFFSET_BYTES + 24, APPEARANCE_HAS_ATMOSPHERE)
                putFloat(BODY_APPEARANCE_OFFSET_BYTES + 52, 104f)
                putFloat(BODY_APPEARANCE_OFFSET_BYTES + 56, 1f)
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
        assertEquals(RuntimeSceneBodyKind.Planet, body.kind)
        assertTrue(body.selected)
        assertEquals(4f, body.radiusM)
        assertEquals(
            RenderCelestialAppearance(
                material = RenderCelestialMaterial.Terrestrial,
                provenance = RenderAppearanceProvenance.CuratedVisualGuide,
                northPole = RenderDirection(0.397f, 0.918f, 0f),
                referenceMeridianRadians = 0f,
                ringSystem = null,
                atmosphere = RenderAtmosphere(outerRadiusM = 104f, opticalDensity = 1f),
                comet = null,
            ),
            body.appearance,
        )
    }

    @Test
    fun decode_preservesTracerSourceBodyIdsAcrossAbi9Stride() {
        val tracerInstances = ByteBuffer.allocateDirect(TRACER_STRIDE_BYTES * 2)
            .order(ByteOrder.nativeOrder())
            .apply {
                putTracer(
                    x = 1f,
                    y = 2f,
                    z = 3f,
                    sizePx = 8f,
                    sourceBodyId = "earth",
                )
                putTracer(
                    x = 4f,
                    y = 5f,
                    z = 6f,
                    sizePx = 3f,
                    sourceBodyId = "probe",
                )
                flip()
            }

        val frame = VulkanPacketRenderFrameDecoder.decode(
            NativeVulkanScenePacket(
                packetHandle = 8L,
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
                tracerCount = 2,
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
                bodyInstances = null,
                tracerInstances = tracerInstances,
                trailSpans = null,
                trailVertices = null,
                directionalLights = null,
            )
        )

        assertEquals(2, frame.tracers.size)
        assertEquals("earth", frame.tracers[0].sourceBodyId)
        assertEquals(1f, frame.tracers[0].x)
        assertEquals(8f, frame.tracers[0].sizePx)
        assertEquals("probe", frame.tracers[1].sourceBodyId)
        assertEquals(4f, frame.tracers[1].x)
        assertEquals(3f, frame.tracers[1].sizePx)
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

    private fun ByteBuffer.putTracer(
        x: Float,
        y: Float,
        z: Float,
        sizePx: Float,
        sourceBodyId: String,
    ) {
        val base = position()
        val encodedSourceBodyId = sourceBodyId.toByteArray(StandardCharsets.UTF_8)
        putFloat(base + 0, x)
        putFloat(base + 4, y)
        putFloat(base + 8, z)
        putFloat(base + 12, 0.2f)
        putFloat(base + 16, 0.4f)
        putFloat(base + 20, 0.8f)
        putFloat(base + 24, 1.0f)
        putFloat(base + 28, sizePx)
        position(base + TRACER_SOURCE_BODY_ID_OFFSET_BYTES)
        put(encodedSourceBodyId)
        position(base + TRACER_SOURCE_BODY_ID_LENGTH_OFFSET_BYTES)
        putInt(encodedSourceBodyId.size)
        position(base + TRACER_STRIDE_BYTES)
    }

    private companion object {
        private const val BODY_STRIDE_BYTES = 244
        private const val BODY_APPEARANCE_OFFSET_BYTES = 44
        private const val BODY_ID_OFFSET_BYTES = 144
        private const val BODY_ID_LENGTH_OFFSET_BYTES = 240
        private const val APPEARANCE_HAS_ATMOSPHERE = 1 shl 1
        private const val TRACER_STRIDE_BYTES = 132
        private const val TRACER_SOURCE_BODY_ID_OFFSET_BYTES = 32
        private const val TRACER_SOURCE_BODY_ID_LENGTH_OFFSET_BYTES = 128
        private const val TRAIL_VERTEX_STRIDE_BYTES = 20
        private const val TRAIL_SPAN_STRIDE_BYTES = 136
        private const val TRAIL_SOURCE_BODY_ID_OFFSET_BYTES = 36
        private const val TRAIL_SOURCE_BODY_ID_LENGTH_OFFSET_BYTES = 132
    }
}
