package com.talkback.core.signaling.peer

import com.talkback.core.model.SignalType

/**
 * ADR-0022 Q8 Hard gate: only new peer-scoped control signaling.
 * ICE / media recovery dispatch / existing RTP / membership = Ignore.
 *
 * GROUP_JOIN and WEBRTC_OFFER/ANSWER are omitted: they are also vehicles for
 * mesh ICE restart / recovery negotiation (INV-SIG-018 deadlock forbid).
 */
object PeerControlSignalingAdmission {
    fun isHardGatedControl(type: SignalType): Boolean = when (type) {
        SignalType.CALL_INVITE,
        SignalType.CALL_ACCEPT,
        SignalType.CALL_REJECT,
        SignalType.GROUP_INVITE,
        SignalType.GROUP_ACCEPT,
        SignalType.GROUP_LEAVE,
        SignalType.GROUP_RESYNC_REQUEST,
        SignalType.CONFERENCE_REJOIN,
        SignalType.HANGUP -> true
        else -> false
    }

    fun maySendNewControl(type: SignalType, peerEdgeReady: Boolean): Boolean {
        if (!isHardGatedControl(type)) return true
        return peerEdgeReady
    }
}