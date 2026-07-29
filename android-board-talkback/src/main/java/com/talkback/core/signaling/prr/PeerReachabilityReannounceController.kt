package com.talkback.core.signaling.prr

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.PeerTarget

/**
 * R28-PRR v1: epoch-scoped signaling reachability re-announcement.
 * Emits facts only; does not mutate LinkQualificationState or recovery state.
 */
class PeerReachabilityReannounceController(
    private val sender: SignalingReannounceSender,
    private val endpointSnapshot: () -> LocalEndpointSnapshot = {
        LocalEndpointSnapshot(
            localModuleId = "stub",
            fromAddress = EndpointAddress(ModuleId("stub"), EndpointId("E01"))
        )
    }
) {
    @Volatile
    private var lastEpisodeEpoch: Long? = null

    fun onTransportEpochChanged(
        transportEpoch: Long,
        socketId: Long,
        networkId: String,
        reason: String
    ) {
        if (lastEpisodeEpoch == transportEpoch) {
            PeerReachabilityReannounceTrace.episodeSkipped(transportEpoch, "IDEMPOTENT")
            return
        }
        lastEpisodeEpoch = transportEpoch
        PeerReachabilityReannounceTrace.episodeStarted(
            transportEpoch = transportEpoch,
            socketId = socketId,
            reason = reason,
            networkId = networkId
        )
        sender.sendReannounce(endpointSnapshot(), transportEpoch)
        PeerReachabilityReannounceTrace.helloSent(transportEpoch, socketId, networkId)
        PeerReachabilityReannounceTrace.endpointReannounced(transportEpoch, socketId, networkId)
    }

    /**
     * Peer-scoped PRR hint after peer-edge freshness loss (ADR-0022 Q6).
     * Does not advance lastEpisodeEpoch / global generation.
     */
    fun onPeerEdgeSignalingHint(
        remoteModuleId: String,
        target: PeerTarget,
        transportEpoch: Long,
        socketId: Long,
        networkId: String
    ) {
        PeerReachabilityReannounceTrace.episodeStarted(
            transportEpoch = transportEpoch,
            socketId = socketId,
            reason = "peer_edge_stale:$remoteModuleId",
            networkId = networkId
        )
        sender.sendReannounceToPeer(endpointSnapshot(), transportEpoch, target)
        PeerReachabilityReannounceTrace.helloSent(transportEpoch, socketId, networkId)
    }
}