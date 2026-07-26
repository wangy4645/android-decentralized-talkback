package com.talkback.core.signaling.prr

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId

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
}