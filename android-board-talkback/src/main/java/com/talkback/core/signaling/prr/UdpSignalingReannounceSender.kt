package com.talkback.core.signaling.prr

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.HelloPayload
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.security.SignalSecurity
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.signaling.SignalingChannel
import java.util.UUID

/**
 * PRR_REANNOUNCE: one signed HELLO per target (minimal payload, no session topology).
 * Does not use TalkbackCoordinator.broadcastHello().
 */
class UdpSignalingReannounceSender(
    private val signalingChannel: SignalingChannel,
    private val sharedSecret: String,
    private val helloTargetProvider: PrrHelloTargetProvider
) : SignalingReannounceSender {

    override fun sendReannounce(snapshot: LocalEndpointSnapshot, transportEpoch: Long) {
        val payload = HelloPayload(
            moduleId = snapshot.localModuleId,
            endpoints = snapshot.endpoints,
            transportEpoch = transportEpoch
        ).encode()
        val envelope = buildSignedEnvelope(
            from = snapshot.fromAddress,
            payload = payload
        )
        helloTargetProvider.helloTargets().forEach { target ->
            runCatching {
                signalingChannel.send(target, envelope)
            }
        }
    }

    private fun buildSignedEnvelope(from: EndpointAddress, payload: String): SignalEnvelope {
        val unsigned = SignalEnvelope(
            type = SignalType.HELLO,
            from = from,
            to = null,
            sessionId = "hello",
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            nonce = UUID.randomUUID().toString(),
            signature = ""
        )
        return unsigned.copy(signature = SignalSecurity.sign(unsigned, sharedSecret))
    }
}
