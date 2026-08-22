package com.talkback.core.session

import com.talkback.core.qos.IceConnectivity

/**
 * Maps media ICE and recovery-progress facts into Health projection inputs.
 * Does not infer topology, eligibility, or recovery targets.
 */
object ConferenceHealthFactsAdapter {

    fun mediaObservations(
        snapshot: ConferenceTopologySnapshot,
        localModuleId: String,
        iceStateForModule: (String) -> String?
    ): Set<MediaEdgeUsabilityObservation> {
        val out = LinkedHashSet<MediaEdgeUsabilityObservation>()
        for (edge in snapshot.actualMediaEdges) {
            val observed = observedRemote(edge, localModuleId) ?: continue
            val ice = iceStateForModule(observed)
            out += MediaEdgeUsabilityObservation(
                edge = edge,
                usable = IceConnectivity.isConnected(ice),
                reconnecting = IceConnectivity.isNegotiating(ice) || ice == "DISCONNECTED"
            )
        }
        return out
    }

    fun recoveryProgress(facts: EdgeRecoveryFacts): RecoveryProgressFact =
        RecoveryProgressFact(
            inFlight = facts.anyRecovering,
            succeeded = false,
            failed = facts.anyFailedMediaRecovery
        )

    private fun observedRemote(edge: MediaEdge, localModuleId: String): String? = when (localModuleId) {
        edge.anchorModuleId -> edge.remoteModuleId
        edge.remoteModuleId -> edge.anchorModuleId
        else -> null
    }
}
