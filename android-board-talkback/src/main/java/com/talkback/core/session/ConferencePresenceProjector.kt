package com.talkback.core.session

/**
 * Pure Conference presence projection (ADR-0022 R27′-B).
 * Sibling to [ConferenceRuntimeProjector]; consumes the same read-only facts, different semantics.
 * No side effects.
 */
object ConferencePresenceProjector {

    data class Input(
        val sessionAccepted: Boolean,
        /** Primary meeting size from membership (ADR-0020 P2). */
        val joinedParticipantCount: Int,
        /** ICE-direct connected remote module ids (connectivity fact). */
        val connectedRemoteModuleIds: Set<String>,
        /** Per-edge recovery facts from [ConferenceEdgeRecoveryController]. */
        val recoveringRemoteModuleIds: Set<String> = emptySet()
    )

    fun project(input: Input): ConferencePresenceProjection {
        if (!input.sessionAccepted) {
            return ConferencePresenceProjection(
                joinedCount = 0,
                connectedCount = 0,
                recoveringPeers = emptySet()
            )
        }
        val connectedCount = 1 + input.connectedRemoteModuleIds.size
        return ConferencePresenceProjection(
            joinedCount = input.joinedParticipantCount,
            connectedCount = connectedCount,
            recoveringPeers = input.recoveringRemoteModuleIds.toSet()
        )
    }
}
