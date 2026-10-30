package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember

/**
 * Frozen CREATION authority payload — read-only input to wire encoder.
 */
data class Profile01CreationAuthoritySnapshot(
    val conferenceId: ByteArray,
    val conferenceEpoch: Long,
    val ownerModuleId: String,
    val mediaGroupDescriptor: Profile01CborCodec.CborValue,
    val membershipView: List<Profile01WireMembershipMember>,
    val initialMembershipVersion: Long,
    val initialMediaKeyEpoch: Long,
    val mediaKeyCommitment: ByteArray,
    val signerKeyVersion: Long,
)
