package com.talkback.core.session

/**
 * P0.1e-1: one-shot Anchor realization after a late spoke membership ACCEPT.
 *
 * Not Recovery. Not topology republish. Not a conference rebuild.
 */
enum class ConferenceLateSpokeEdgePhase {
    NONE,
    OFFERING,
    ANSWERING,
    CONNECTED
}

object ConferenceLateSpokeRealizationContract {

    const val REASON_ALREADY_REALIZED = "ALREADY_REALIZED"
    const val REASON_REALIZATION_IN_FLIGHT = "REALIZATION_IN_FLIGHT"

    fun decide(
        request: RealizationAuthRequest,
        snapshot: ConferenceTopologySnapshot?,
        phase: ConferenceLateSpokeEdgePhase
    ): RealizationDecision {
        when (phase) {
            ConferenceLateSpokeEdgePhase.CONNECTED ->
                return RealizationDecision.Denied(REASON_ALREADY_REALIZED)
            ConferenceLateSpokeEdgePhase.OFFERING,
            ConferenceLateSpokeEdgePhase.ANSWERING ->
                return RealizationDecision.Denied(REASON_REALIZATION_IN_FLIGHT)
            ConferenceLateSpokeEdgePhase.NONE -> Unit
        }
        return ConferenceMediaEdgeRealizationContract.authorizeCreateOffer(request, snapshot)
    }

    fun shouldForwardMembershipToAnchor(
        localModuleId: String,
        acceptedRemoteId: String,
        snapshot: ConferenceTopologySnapshot
    ): Boolean {
        if (snapshot.topologyMode != ConferenceTopologyMode.ANCHOR) return false
        val anchorId = snapshot.anchorId ?: return false
        if (snapshot.hostModuleId != localModuleId) return false
        if (localModuleId == anchorId) return false
        if (acceptedRemoteId == localModuleId || acceptedRemoteId == anchorId) return false
        val edge = MediaEdge(anchorModuleId = anchorId, remoteModuleId = acceptedRemoteId)
        return edge in snapshot.actualMediaEdges
    }
}
