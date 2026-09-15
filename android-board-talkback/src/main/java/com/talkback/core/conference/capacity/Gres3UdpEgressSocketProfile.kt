package com.talkback.core.conference.capacity

import java.net.DatagramSocket

/**
 * H1d.5 UDP egress socket configuration snapshot (manifest only; not per-slot).
 * H1d.5a: warm-up validity uses [egressWarmupSucceeded], not "completed".
 */
data class Gres3UdpEgressSocketProfile(
    val socketMode: String,
    val connected: Boolean,
    val reuseAddress: Boolean,
    val broadcast: Boolean,
    val sendBufferSizeDefault: Int,
    val sendBufferSizeRequested: Int,
    val sendBufferSizeEffective: Int,
    val receiveBufferSizeEffective: Int,
    val trafficClass: Int,
    val egressWarmupAttempted: Boolean = false,
    val egressWarmupSucceeded: Boolean = false,
    val egressWarmupLegsSucceeded: Int = 0,
    val egressWarmupLegsFailed: Int = 0,
    val egressWarmupLegFailures: List<Gres3EgressWarmupLegFailure> = emptyList(),
    val egressWarmupSetupError: String? = null,
) {
    fun withWarmup(result: Gres3EgressWarmupResult): Gres3UdpEgressSocketProfile =
        copy(
            egressWarmupAttempted = result.attempted,
            egressWarmupSucceeded = result.succeeded,
            egressWarmupLegsSucceeded = result.legsSucceeded,
            egressWarmupLegsFailed = result.legsFailed,
            egressWarmupLegFailures = result.legFailures,
            egressWarmupSetupError = result.setupError,
        )
}

/**
 * H1d.5: minimal DatagramSocket egress configuration — SO_SNDBUF, unconnected mode preserved.
 */
object Gres3UdpEgressSocketConfigurator {
    /** 256 KiB — headroom for 9×120B legs per 20ms slot without kernel send-buffer pressure. */
    const val REQUESTED_SEND_BUFFER_BYTES: Int = 262_144

    fun configure(socket: DatagramSocket): Gres3UdpEgressSocketProfile {
        val defaultSendBuffer = socket.sendBufferSize
        socket.sendBufferSize = REQUESTED_SEND_BUFFER_BYTES
        return Gres3UdpEgressSocketProfile(
            socketMode = "unconnected",
            connected = socket.isConnected,
            reuseAddress = socket.reuseAddress,
            broadcast = socket.broadcast,
            sendBufferSizeDefault = defaultSendBuffer,
            sendBufferSizeRequested = REQUESTED_SEND_BUFFER_BYTES,
            sendBufferSizeEffective = socket.sendBufferSize,
            receiveBufferSizeEffective = socket.receiveBufferSize,
            trafficClass = socket.trafficClass,
        )
    }
}
