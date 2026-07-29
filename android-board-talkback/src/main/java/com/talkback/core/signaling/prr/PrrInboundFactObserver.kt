package com.talkback.core.signaling.prr

import com.talkback.core.model.HelloPayload
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.signaling.PeerTarget

/**
 * R28-PRR-3: inbound HELLO observation (emit-only facts).
 * Does not mutate LinkQualificationState or recovery/qualification coordinators.
 */
object PrrInboundFactObserver {

    fun observe(
        envelope: SignalEnvelope,
        source: PeerTarget,
        localEpoch: Long,
        socketId: Long
    ) {
        if (envelope.type != SignalType.HELLO) return
        val payload = HelloPayload.decode(envelope.payload) ?: return
        PeerReachabilityReannounceTrace.factObserved(
            localEpoch = localEpoch,
            remoteModuleId = envelope.from.moduleId.value,
            fact = "HELLO_RECEIVED",
            remoteEpoch = payload.transportEpoch,
            src = "${source.host}:${source.port}",
            socketId = socketId
        )
    }
}