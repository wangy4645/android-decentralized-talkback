package com.talkback.core.session

/**
 * CPP canonical participant vector. Aggregates MUST be functions of [participants].
 * Not a third authority — composed from roster + [ConferencePresenceSnapshot].
 */
enum class CppMembership {
    JOINED,
    NOT_JOINED,
    PENDING
}

enum class CppMediaRelation {
    VIA_ANCHOR,
    DIRECT,
    NONE,
    DEGRADED
}

enum class CppEvidence {
    FRESH,
    STALE,
    UNKNOWN
}

data class ParticipantPresenceRecord(
    val moduleId: String,
    val membership: CppMembership,
    val mediaRelation: CppMediaRelation,
    val evidence: CppEvidence
) {
    val mediaConnected: Boolean
        get() = evidence == CppEvidence.FRESH &&
            (mediaRelation == CppMediaRelation.VIA_ANCHOR ||
                mediaRelation == CppMediaRelation.DIRECT)
}

data class ParticipantPresenceProjection(
    val participants: List<ParticipantPresenceRecord>,
    val recoveringPeers: Set<String> = emptySet()
) {
    val joinedCount: Int = participants.count { it.membership == CppMembership.JOINED }
    val connectedCount: Int = participants.count { it.mediaConnected }
    val joiningCount: Int = joinedCount - connectedCount
    val uiParticipantCount: Int = participants.size
}

/**
 * Anchor-produced media facts. Projection fact, not PresenceAuthority.
 */
data class ConferencePresenceSnapshot(
    val conferenceId: String,
    val producerModuleId: String,
    val rosterEpoch: Long,
    val anchorEpoch: Long,
    val meshGeneration: Long,
    val producedAtMs: Long,
    val mediaByModuleId: Map<String, CppMediaRelation>
)
