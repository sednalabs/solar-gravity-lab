package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRenderHostAdapterTest {
    @Test
    fun refreshPacket_returnsUnavailable_whenNativeBridgeReturnsNullResult() {
        val transport = FakeNativeRuntimeTransport(exportResult = null)
        val adapter = NativeRenderHostAdapter(transport)

        adapter.bindSession(sessionHandle = 42L)
        val refresh = adapter.refreshPacket()

        assertEquals(
            "Render export unavailable: native bridge returned no packet result",
            refresh.unavailableReason,
        )
        assertEquals(listOf(42L), transport.exportedHandles)
        assertTrue(transport.releasedPacketHandles.isEmpty())
    }

    @Test
    fun refreshPacket_returnsUnavailable_whenNativeBridgeReturnsOkWithoutPacket() {
        val transport = FakeNativeRuntimeTransport(
            exportResult = NativeVulkanScenePacketResult(
                result = NativeResult(code = 0),
                packet = null,
            ),
        )
        val adapter = NativeRenderHostAdapter(transport)

        adapter.bindSession(sessionHandle = 42L)
        val refresh = adapter.refreshPacket()

        assertEquals(
            "Render export unavailable: native bridge returned an empty packet",
            refresh.unavailableReason,
        )
        assertEquals(listOf(42L), transport.exportedHandles)
        assertTrue(transport.releasedPacketHandles.isEmpty())
    }

    @Test
    fun refreshPacket_returnsLease_andReleasePacketIsIdempotent() {
        val packet = samplePacket(packetHandle = 77L)
        val transport = FakeNativeRuntimeTransport(
            exportResult = NativeVulkanScenePacketResult(
                result = NativeResult(code = 0),
                packet = packet,
            ),
        )
        val adapter = NativeRenderHostAdapter(transport)

        adapter.bindSession(sessionHandle = 42L)
        val refresh = adapter.refreshPacket()

        assertNotNull(refresh.lease)
        assertEquals(packet.summaryLine(), refresh.lease?.summaryLine)
        assertFalse(refresh.lease?.isReleased ?: true)

        adapter.releasePacket()
        adapter.releasePacket()

        assertEquals(listOf(42L), transport.exportedHandles)
        assertEquals(listOf(77L), transport.releasedPacketHandles)
        assertTrue(refresh.lease?.isReleased == true)
    }

    private fun samplePacket(packetHandle: Long): NativeVulkanScenePacket = NativeVulkanScenePacket(
        packetHandle = packetHandle,
        sceneRevision = "scene-1",
        epochSeconds = 12.5,
        observerMode = 1,
        timelineSemantics = 1,
        camera = NativeVulkanCameraPacket(
            frameOriginX = 0.0,
            frameOriginY = 0.0,
            frameOriginZ = 0.0,
            positionFromOriginX = 0f,
            positionFromOriginY = 0f,
            positionFromOriginZ = 10f,
            targetFromOriginX = 0f,
            targetFromOriginY = 0f,
            targetFromOriginZ = 0f,
            upX = 0f,
            upY = 1f,
            upZ = 0f,
            verticalFovDegrees = 45f,
            exposure = 1f,
        ),
        bodyCount = 1,
        tracerCount = 0,
        trailSpanCount = 0,
        trailVertexCount = 0,
        directionalLightCount = 1,
        diagnostics = NativeRenderDiagnostics(
            frameNumber = 1L,
            cpuExtractMs = 0.5f,
            gpuUploadMs = 0.25f,
            droppedFrames = 0,
        ),
        provenanceSource = "fixture",
        provenanceVersion = "v1",
        provenanceManifestId = "manifest",
        provenanceManifestDigest = "digest",
        provenancePackageDigest = "pkg",
        bodyInstances = null,
        tracerInstances = null,
        trailSpans = null,
        trailVertices = null,
        directionalLights = null,
    )

    private class FakeNativeRuntimeTransport(
        private val exportResult: NativeVulkanScenePacketResult?,
    ) : NativeRuntimeTransport {
        val exportedHandles = mutableListOf<Long>()
        val releasedPacketHandles = mutableListOf<Long>()

        override fun ensureLibraryLoaded(): NativeLibraryLoadOutcome = NativeLibraryLoadOutcome.Success

        override fun createSession(
            scenarioId: String,
            rootBranchId: String,
        ): NativeCreateSessionResult = throw UnsupportedOperationException("unused in test")

        override fun runtimeInfo(handle: Long): NativeRuntimeInfoResult =
            throw UnsupportedOperationException("unused in test")

        override fun snapshotSummary(handle: Long): NativeSnapshotSummaryResult =
            throw UnsupportedOperationException("unused in test")

        override fun refreshSession(handle: Long): NativeSnapshotSummaryResult =
            throw UnsupportedOperationException("unused in test")

        override fun applyCommand(
            handle: Long,
            command: NativeRuntimeCommandPayload,
        ): NativeSnapshotSummaryResult = throw UnsupportedOperationException("unused in test")

        override fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult? {
            exportedHandles += handle
            return exportResult
        }

        override fun releaseVulkanScene(packetHandle: Long) {
            releasedPacketHandles += packetHandle
        }

        override fun destroySession(handle: Long) = Unit
    }
}
