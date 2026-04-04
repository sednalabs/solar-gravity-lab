package com.sednalabs.solarlab.runtime

/**
 * Android render-host seam for native Vulkan scene packet export.
 *
 * Android keeps packet handling host-oriented: request, hold, summarize, release.
 * Packing and payload interpretation remain backend concerns.
 */
internal interface RenderHostAdapter {
    fun bindSession(sessionHandle: Long)

    fun refreshPacket(): RenderPacketRefreshResult

    fun releasePacket()
}

internal data class RenderPacketSnapshot(
    val sceneRevision: String,
    val bodyCount: Int,
    val tracerCount: Int,
    val trailSpanCount: Int,
    val trailVertexCount: Int,
    val directionalLightCount: Int,
    val uploadBytes: Int,
) {
    fun summaryLine(): String =
        "bodies=$bodyCount, tracers=$tracerCount, trails=$trailSpanCount/$trailVertexCount, lights=$directionalLightCount, uploadBytes=$uploadBytes"
}

internal class PacketLease internal constructor(
    private val packetHandle: Long,
    val snapshot: RenderPacketSnapshot,
    private val releaseAction: (Long) -> Unit,
) : AutoCloseable {
    val sceneRevision: String get() = snapshot.sceneRevision
    val summaryLine: String get() = snapshot.summaryLine()

    @Volatile
    private var released: Boolean = false

    val isReleased: Boolean get() = released

    override fun close() {
        if (released) return
        releaseAction(packetHandle)
        released = true
    }
}

internal data class RenderPacketRefreshResult(
    val lease: PacketLease? = null,
    val unavailableReason: String? = null,
)

internal class NativeRenderHostAdapter(
    private val transport: NativeRuntimeTransport
) : RenderHostAdapter {
    private var sessionHandle: Long = 0L
    private var activeLease: PacketLease? = null

    override fun bindSession(sessionHandle: Long) {
        if (this.sessionHandle != sessionHandle) {
            releasePacket()
        }
        this.sessionHandle = sessionHandle
    }

    override fun refreshPacket(): RenderPacketRefreshResult {
        if (sessionHandle == 0L) {
            return RenderPacketRefreshResult(unavailableReason = "Render host has no bound session")
        }

        releasePacket()

        val packetResult = runCatching {
            transport.exportVulkanScene(sessionHandle)
        }.getOrElse { error ->
            return RenderPacketRefreshResult(
                unavailableReason = "Render export unavailable: ${error.message ?: error::class.java.simpleName}"
            )
        }

        val packet = packetResult.packet
        if (!packetResult.result.isOk() || packet == null) {
            return RenderPacketRefreshResult(
                unavailableReason = "Render export unavailable: ${packetResult.result.describe()}"
            )
        }

        val lease = PacketLease(
            packetHandle = packet.packetHandle,
            snapshot = packet.toSnapshot(),
            releaseAction = transport::releaseVulkanScene,
        )
        activeLease = lease
        return RenderPacketRefreshResult(
            lease = lease
        )
    }

    override fun releasePacket() {
        val lease = activeLease ?: return
        lease.close()
        activeLease = null
    }
}

private fun NativeVulkanScenePacket.toSnapshot(): RenderPacketSnapshot {
    val uploadBytes = listOf(
        bodyInstances?.capacity() ?: 0,
        tracerInstances?.capacity() ?: 0,
        trailSpans?.capacity() ?: 0,
        trailVertices?.capacity() ?: 0,
        directionalLights?.capacity() ?: 0,
    ).sum()
    return RenderPacketSnapshot(
        sceneRevision = sceneRevision,
        bodyCount = bodyCount,
        tracerCount = tracerCount,
        trailSpanCount = trailSpanCount,
        trailVertexCount = trailVertexCount,
        directionalLightCount = directionalLightCount,
        uploadBytes = uploadBytes,
    )
}
