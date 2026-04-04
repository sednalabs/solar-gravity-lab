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

internal class PacketLease internal constructor(
    val packet: NativeVulkanScenePacket,
    private val releaseAction: (Long) -> Unit,
) : AutoCloseable {
    val packetHandle: Long get() = packet.packetHandle
    val sceneRevision: String get() = packet.sceneRevision
    val summaryLine: String get() = packet.summaryLine()

    @Volatile
    private var released: Boolean = false

    val isReleased: Boolean get() = released

    override fun close() {
        if (released) return
        releaseAction(packet.packetHandle)
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

        val lease = PacketLease(packet = packet, releaseAction = transport::releaseVulkanScene)
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
