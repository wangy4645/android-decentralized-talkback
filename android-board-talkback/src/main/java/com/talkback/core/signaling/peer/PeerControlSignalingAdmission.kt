package com.talkback.core.signaling.peer

import com.talkback.core.model.SignalType

/**
 * ADR-0022 Q8 Hard gate: only **new** peer-scoped control signaling (assume-reachable).
 * ICE / media recovery dispatch / existing RTP / membership = Ignore.
 *
 * Responses to an already-received request (GROUP_ACCEPT) are not gated — inbound
 * request evidence already proves peer reachability for that transaction.
 *
 * GROUP_JOIN and WEBRTC_OFFER/ANSWER are omitted: they are also vehicles for
 * mesh ICE restart / recovery negotiation (INV-SIG-018 deadlock forbid).
 */
object PeerControlSignalingAdmission {
    /** New outbound request: requires [PEER_EDGE_SIGNALING_READY] before send. */
    fun isRequestAdmissionGated(type: SignalType): Boolean = when (type) {
        SignalType.CALL_INVITE,
        SignalType.CALL_ACCEPT,
        SignalType.CALL_REJECT,
        SignalType.GROUP_INVITE,
        SignalType.GROUP_LEAVE,
        SignalType.GROUP_RESYNC_REQUEST,
        SignalType.CONFERENCE_REJOIN,
        SignalType.HANGUP -> true
        else -> false
    }

    /** Response to an inbound control request — not subject to new-request admission gate. */
    fun isResponseControl(type: SignalType): Boolean = when (type) {
        SignalType.GROUP_ACCEPT -> true
        else -> false
    }

    fun isHardGatedControl(type: SignalType): Boolean = isRequestAdmissionGated(type)

    fun maySendNewControl(type: SignalType, peerEdgeReady: Boolean): Boolean {
        if (isResponseControl(type)) return true
        if (!isRequestAdmissionGated(type)) return true
        return peerEdgeReady
    }

    /**
     * ADR-0036 Phase 2.1: membership recovery resync may bootstrap before full PEER_EDGE_READY
     * when the authority peer is observable on the current transport epoch (not NEVER_OBSERVED).
     */
    fun maySendMembershipRecoveryResync(
        peerSnapshot: PeerEdgeSignalingSnapshot,
        localBidirectionalReady: Boolean
    ): Boolean {
        if (!localBidirectionalReady) return false
        return when (peerSnapshot.reason) {
            null,
            PeerEdgeSignalingNotReadyReason.FRESHNESS_EXPIRED -> true
            PeerEdgeSignalingNotReadyReason.NEVER_OBSERVED,
            PeerEdgeSignalingNotReadyReason.GENERATION_MISMATCH,
            PeerEdgeSignalingNotReadyReason.GENERATION_INVALIDATED,
            PeerEdgeSignalingNotReadyReason.LOCAL_NOT_BIDIRECTIONAL -> false
        }
    }
}
