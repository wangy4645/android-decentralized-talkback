package com.talkback.core.conference.session.profile01

/**
 * Decoded MEMBERSHIP authority (wire only — no lifecycle policy).
 */
data class Profile01WireMembershipFact(
    val signedFactBytes: ByteArray,
    val factDigest: ByteArray,
    val conferenceId: String,
    val conferenceEpoch: Long,
    val ownerModuleId: String,
    val membershipVersion: Long,
    val previousMembershipDigest: ByteArray,
    val members: List<Profile01WireMembershipMember>,
    val mediaKeyEpoch: Long,
    val mediaKeyCommitment: ByteArray,
)
