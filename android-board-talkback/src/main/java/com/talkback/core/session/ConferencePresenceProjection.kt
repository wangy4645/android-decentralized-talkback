package com.talkback.core.session

/**
 * Conference presence read model (ADR-0022 R27′).
 * UI MUST consume this for joined/connected/recovering counts — not [ReachabilitySnapshot].
 */
data class ConferencePresenceProjection(
    /** Membership-joined participants (includes local; excludes pending invitees). */
    val joinedCount: Int,
    /** Participants with active media relation (VIA_ANCHOR/DIRECT + FRESH). */
    val connectedCount: Int,
    /** Remote module ids with an active edge recovery obligation on this device. */
    val recoveringPeers: Set<String> = emptySet(),
    /**
     * Advisory media-health facts (ADR-0023 R29-C). MUST NOT drive joined/left roster semantics.
     */
    val mediaUnavailablePeers: Set<String> = emptySet(),
    /** Canonical CPP vector. Aggregates MUST match this list when non-empty. */
    val participants: List<ParticipantPresenceRecord> = emptyList()
) {
    val joiningCount: Int
        get() = if (participants.isNotEmpty()) {
            participants.count { it.membership == CppMembership.JOINED } -
                participants.count { it.mediaConnected }
        } else {
            (joinedCount - connectedCount).coerceAtLeast(0)
        }
}

fun ParticipantPresenceProjection.toConferencePresenceProjection(): ConferencePresenceProjection =
    ConferencePresenceProjection(
        joinedCount = joinedCount,
        connectedCount = connectedCount,
        recoveringPeers = recoveringPeers,
        mediaUnavailablePeers = participants
            .filter { it.mediaRelation == CppMediaRelation.DEGRADED }
            .map { it.moduleId }
            .toSet(),
        participants = participants
    )
