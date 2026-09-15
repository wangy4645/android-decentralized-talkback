package com.talkback.core.conference.transport

import com.talkback.core.conference.authority.AuthorityWiringRuntime
import com.talkback.core.conference.runtime.AndroidRuntimeResources
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.runtime.MulticastSocketTransportRebindSeam
import com.talkback.core.conference.runtime.TransportHandle
import com.talkback.core.conference.wire.ConferenceWireConstants
import com.talkback.core.conference.wire.WireIngressResult
import com.talkback.core.conference.wire.WireReplayState
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Phase 1 multicast RTP/SRTP transport seam.
 *
 * One protected artifact per slot on the wire (multicast fan-out = 1 datagram).
 * Ingress path delegates to [AuthorityWiringRuntime.admitWire] when configured.
 */
class ConferenceMulticastRtpSrtpTransport(
    private val resources: AndroidRuntimeResources,
    private val wiring: AuthorityWiringRuntime? = null,
    val observability: MulticastTransportObservability = MulticastTransportObservability(),
) {
    private val rebindSeam: MulticastSocketTransportRebindSeam
        get() = resources.rebindSeam as MulticastSocketTransportRebindSeam

    var socketReceiveTimeoutMs: Int = 250
        set(value) {
            field = value.coerceAtLeast(1)
            rebindSeam.currentSocket?.soTimeout = field
        }

    var multicastTtl: Int = 32
        set(value) {
            field = value.coerceIn(1, 255)
            rebindSeam.currentSocket?.timeToLive = field
        }

    fun beginScope(handle: TransportHandle, nowMs: Long): Boolean {
        val ok = resources.beginTransportScope(handle, nowMs)
        if (ok) {
            rebindSeam.currentSocket?.let { socket ->
                socket.soTimeout = socketReceiveTimeoutMs
                socket.timeToLive = multicastTtl
            }
        }
        return ok
    }

    fun endScope() {
        resources.endTransportScope()
    }

    fun isScopeActive(): Boolean = resources.transportState().active

    fun lastSocketBindResult() = rebindSeam.lastSocketBindResult

    fun lastJoinSucceeded(): Boolean = rebindSeam.lastJoinSucceeded

    fun lastJoinError(): String? = rebindSeam.lastJoinError

    /** Re-assert multicast egress/ingress interface (underlay probe pattern). */
    fun configureNetworkInterface(interfaceName: String): Boolean {
        val socket = rebindSeam.currentSocket ?: return false
        return try {
            val iface = java.net.NetworkInterface.getByName(interfaceName) ?: return false
            socket.networkInterface = iface
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Send a pre-protected 120B SRTP artifact to the conference media group.
     */
    fun sendProtectedArtifact(
        artifact: ByteArray,
        endpoint: MediaGroupEndpointBinding,
    ): Boolean {
        require(artifact.isNotEmpty() && artifact.size <= ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES) {
            "artifact must be 1..${ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES}B, got ${artifact.size}"
        }
        val socket = rebindSeam.currentSocket ?: run {
            observability.recordSendError("SOCKET_NULL")
            return false
        }
        val startNs = System.nanoTime()
        return try {
            val group = InetAddress.getByName(endpoint.multicastAddress)
            val packet =
                DatagramPacket(
                    artifact,
                    artifact.size,
                    group,
                    endpoint.mediaPort,
                )
            socket.send(packet)
            observability.recordSend((System.nanoTime() - startNs) / 1_000L)
            true
        } catch (e: Exception) {
            observability.recordSendError("${e.javaClass.simpleName}:${e.message}")
            false
        }
    }

    /**
     * Protect with [SourceScopedSrtpEgress] then multicast-send one datagram.
     */
    fun sendFromSource(
        egress: SourceScopedSrtpEgress,
        opusPayload: ByteArray,
        endpoint: MediaGroupEndpointBinding,
    ): Boolean {
        return when (val protected = egress.protectNext(opusPayload)) {
            is com.talkback.core.conference.wire.ConferenceWireEgress.EgressResult.Protected ->
                sendProtectedArtifact(protected.udpPayload, endpoint)
            is com.talkback.core.conference.wire.ConferenceWireEgress.EgressResult.Rejected -> {
                observability.recordSendError()
                false
            }
        }
    }

    sealed class ReceiveOutcome {
        data class Ingress(
            val sourceIdentity: String,
            val result: WireIngressResult,
            val rxWallMs: Long,
        ) : ReceiveOutcome()

        data class Raw(
            val payload: ByteArray,
            val length: Int,
            val rxWallMs: Long,
        ) : ReceiveOutcome()
    }

    /**
     * Blocking receive one datagram. When [sourceIdentity] and [wiring] are set, runs Profile 02 ingress.
     */
    fun receiveOnce(
        sourceIdentity: String? = null,
        roc: Int = 0,
        replay: WireReplayState? = null,
    ): ReceiveOutcome? {
        val socket = rebindSeam.currentSocket ?: run {
            observability.recordReceiveError("SOCKET_NULL")
            return null
        }
        val buf = ByteArray(ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES)
        val packet = DatagramPacket(buf, buf.size)
        val startNs = System.nanoTime()
        return try {
            socket.receive(packet)
            val rxWallMs = System.currentTimeMillis()
            val durationUs = (System.nanoTime() - startNs) / 1_000L
            val payload = packet.data.copyOf(packet.length)
            if (sourceIdentity != null && wiring != null) {
                val result = wiring.admitWire(sourceIdentity, payload, roc, replay)
                when (result) {
                    is WireIngressResult.Accepted ->
                        observability.recordIngressAccepted(sourceIdentity, rxWallMs, durationUs)
                    is WireIngressResult.Rejected -> observability.recordIngressRejected()
                }
                ReceiveOutcome.Ingress(sourceIdentity, result, rxWallMs)
            } else {
                observability.recordReceive(sourceIdentity ?: "unknown", rxWallMs, durationUs)
                ReceiveOutcome.Raw(payload, packet.length, rxWallMs)
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            observability.recordReceiveError("${e.javaClass.simpleName}:${e.message}")
            null
        }
    }
}
