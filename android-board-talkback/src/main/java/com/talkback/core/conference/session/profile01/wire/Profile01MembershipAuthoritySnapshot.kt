package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember

/**
 * Frozen MEMBERSHIP authority payload — read-only input to wire encoder.
 */
data class Profile01MembershipAuthoritySnapshot(
    val conferenceId: ByteArray,
    val conferenceEpoch: Long,
    val ownerModuleId: String,
    val membershipVersion: Long,
    val previousMembershipDigest: ByteArray,
    val members: List<Profile01WireMembershipMember>,
    val mediaKeyEpoch: Long,
    val mediaKeyCommitment: ByteArray,
    val signerKeyVersion: Long,
)
