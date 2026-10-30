package com.talkback.core.session

/**
 * ADR-0056 A: participant UI/transmit ready remotes.
 *
 * ANCHOR: local admitted [MediaEdge] other endpoint(s).
 * MESH / no local star: ADR-0016 host ICE fallback.
 *
 * Does not change RecoveryEdgeSet, CompletionPolicy, or topology publish.
 */
enum class ConferenceReadyGate {
    HOST_SESSION,
    ADMITTED_EDGE,
    HOST_ICE,
    ANY_REMOTE
}

data class ConferenceReadyRequirement(
    val remotes: Set<String>,
    val gate: ConferenceReadyGate
) {
    fun blockReason(allConnected: Boolean): String {
        if (allConnected) return "NONE"
        return when (gate) {
            ConferenceReadyGate.HOST_SESSION -> "NONE"
            ConferenceReadyGate.ADMITTED_EDGE -> "WAIT_ADMITTED_EDGE"
            ConferenceReadyGate.HOST_ICE -> "WAIT_HOST_ICE"
            ConferenceReadyGate.ANY_REMOTE -> "WAIT_ANY_REMOTE"
        }
    }
}

object ConferenceParticipantReadyContract {

    fun remotesRequiredForReady(
        localModuleId: String,
        hostModuleId: String?,
        topology: ConferenceTopologySnapshot?
    ): ConferenceReadyRequirement {
        if (!hostModuleId.isNullOrBlank() && hostModuleId == localModuleId) {
            return ConferenceReadyRequirement(emptySet(), ConferenceReadyGate.HOST_SESSION)
        }
        val admitted = admittedPeerRemotes(localModuleId, topology)
        if (admitted.isNotEmpty()) {
            return ConferenceReadyRequirement(admitted, ConferenceReadyGate.ADMITTED_EDGE)
        }
        if (hostModuleId.isNullOrBlank()) {
            return ConferenceReadyRequirement(emptySet(), ConferenceReadyGate.ANY_REMOTE)
        }
        return ConferenceReadyRequirement(setOf(hostModuleId), ConferenceReadyGate.HOST_ICE)
    }

    private fun admittedPeerRemotes(
        localModuleId: String,
        topology: ConferenceTopologySnapshot?
    ): Set<String> {
        if (topology == null) return emptySet()
        if (topology.topologyMode != ConferenceTopologyMode.ANCHOR) return emptySet()
        if (topology.actualMediaEdges.isEmpty()) return emptySet()
        return topology.actualMediaEdges.mapNotNull { edge ->
            when (localModuleId) {
                edge.anchorModuleId -> edge.remoteModuleId
                edge.remoteModuleId -> edge.anchorModuleId
                else -> null
            }
        }.toSet()
    }
}
