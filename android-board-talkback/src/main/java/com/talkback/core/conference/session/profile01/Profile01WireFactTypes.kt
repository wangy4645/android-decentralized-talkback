package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding

/**
 * Profile 01 wire fact after decode + trust verification.
 *
 * Parser/validator only — no lifecycle policy.
 */
data class Profile01WireSessionFact(
    val signedFactBytes: ByteArray,
    val factDigest: ByteArray,
    val conferenceId: String,
    val channelId: String,
    val conferenceEpoch: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val membershipView: List<Profile01WireMembershipMember>,
    val mediaKeyCommitment: ByteArray,
    val endpoint: MediaGroupEndpointBinding,
    val masterKey: ByteArray,
    val masterSalt: ByteArray,
    val keyContextHint64: ByteArray,
)

data class Profile01WireMemberSourceFact(
    val signedFactBytes: ByteArray,
    val factDigest: ByteArray,
    val conferenceId: String,
    val conferenceEpoch: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val moduleId: String,
    /** Maps to [AuthoritativeConferenceMediaMemberDeclaration.membershipIncarnationId]. */
    val sourceGeneration: Long,
    val ssrc: Int,
    val sourceAdmissionKey48: ByteArray,
)
