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

internal data class PacketLease(
    val packetHandle: Long,
    val sceneRevision: String,
    val summaryLine: String,
)

internal data class RenderPacketRefreshResult(
    val lease: PacketLease? = null,
    val unavailableReason: String? = null,
)

internal class NativeRenderHostAdapter(
    private val transport: NativeRuntimeTransport
) : RenderHostAdapter {
    private var sessionHandle: Long = 0L
    private var activePacketHandle: Long = 0L

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

        activePacketHandle = packet.packetHandle
        return RenderPacketRefreshResult(
            lease = PacketLease(
                packetHandle = packet.packetHandle,
                sceneRevision = packet.sceneRevision,
                summaryLine = packet.summaryLine(),
            )
        )
    }

    override fun releasePacket() {
        if (activePacketHandle == 0L) return
        transport.releaseVulkanScene(activePacketHandle)
        activePacketHandle = 0L
    }
}
