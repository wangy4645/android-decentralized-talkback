package com.talkback.core.conference.session.profile01

/**
 * Authoritative membership generation converged from CREATION and/or MEMBERSHIP facts.
 */
data class Profile01MembershipGeneration(
    val conferenceId: String,
    val conferenceEpoch: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    /** Current head fact digest (CREATION v0 or latest MEMBERSHIP). */
    val generationFactDigest: ByteArray,
    val creationFactDigest: ByteArray,
    val members: List<Profile01WireMembershipMember>,
    val mediaKeyCommitment: ByteArray,
) {
    fun hasMember(moduleId: String): Boolean = members.any { it.moduleId == moduleId }

    fun memberIncarnation(moduleId: String): ByteArray? =
        members.firstOrNull { it.moduleId == moduleId }?.membershipIncarnationId?.copyOf()
}
