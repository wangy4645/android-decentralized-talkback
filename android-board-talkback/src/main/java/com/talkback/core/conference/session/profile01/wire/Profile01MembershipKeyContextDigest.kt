package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import java.security.MessageDigest

/**
 * Q5 MembershipKeyContextV1 digest for CREATION v0 and later Membership facts.
 */
object Profile01MembershipKeyContextDigest {
    private const val CRYPTO_PROFILE_ID: Long = 1L

    fun computeForCreation(
        conferenceId: ByteArray,
        conferenceEpoch: Long,
        ownerModuleId: String,
        mediaGroupDescriptorDigest: ByteArray,
        membershipVersion: Long,
        completeMembershipView: List<Profile01WireMembershipMember>,
        mediaKeyEpoch: Long,
    ): ByteArray =
        compute(
            conferenceId = conferenceId,
            conferenceEpoch = conferenceEpoch,
            ownerModuleId = ownerModuleId,
            mediaGroupDescriptorDigest = mediaGroupDescriptorDigest,
            membershipVersion = membershipVersion,
            previousMembershipDigest = null,
            completeMembershipView = completeMembershipView,
            mediaKeyEpoch = mediaKeyEpoch,
        )

    fun computeForMembership(
        conferenceId: ByteArray,
        conferenceEpoch: Long,
        ownerModuleId: String,
        mediaGroupDescriptorDigest: ByteArray,
        membershipVersion: Long,
        previousMembershipDigest: ByteArray,
        completeMembershipView: List<Profile01WireMembershipMember>,
        mediaKeyEpoch: Long,
    ): ByteArray {
        require(previousMembershipDigest.size == 32) { "previousMembershipDigest must be 32 bytes" }
        return compute(
            conferenceId = conferenceId,
            conferenceEpoch = conferenceEpoch,
            ownerModuleId = ownerModuleId,
            mediaGroupDescriptorDigest = mediaGroupDescriptorDigest,
            membershipVersion = membershipVersion,
            previousMembershipDigest = previousMembershipDigest,
            completeMembershipView = completeMembershipView,
            mediaKeyEpoch = mediaKeyEpoch,
        )
    }

    private fun compute(
        conferenceId: ByteArray,
        conferenceEpoch: Long,
        ownerModuleId: String,
        mediaGroupDescriptorDigest: ByteArray,
        membershipVersion: Long,
        previousMembershipDigest: ByteArray?,
        completeMembershipView: List<Profile01WireMembershipMember>,
        mediaKeyEpoch: Long,
    ): ByteArray {
        require(conferenceId.size == 16) { "conferenceId must be 16 bytes" }
        require(mediaGroupDescriptorDigest.size == 32) { "descriptor digest must be 32 bytes" }
        val previousDigestValue =
            previousMembershipDigest?.let { digest ->
                Profile01CborCodec.CborValue.ByteString(digest.copyOf())
            } ?: Profile01CborCodec.CborValue.Null
        val context =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to Profile01CborCodec.CborValue.ByteString(conferenceId.copyOf()),
                    u(1) to u(conferenceEpoch),
                    u(2) to Profile01CborCodec.CborValue.Text(ownerModuleId),
                    u(3) to Profile01CborCodec.CborValue.ByteString(mediaGroupDescriptorDigest.copyOf()),
                    u(4) to u(membershipVersion),
                    u(5) to previousDigestValue,
                    u(6) to encodeMembershipView(completeMembershipView),
                    u(7) to u(mediaKeyEpoch),
                    u(8) to u(CRYPTO_PROFILE_ID),
                ),
            )
        val canonical = Profile01CborCodec.encode(context)
        return MessageDigest.getInstance("SHA-256").run {
            update(Profile01WireConstants.MEMBERSHIP_KEY_CONTEXT_DOMAIN)
            update(canonical)
            digest()
        }
    }

    fun encodeMembershipView(
        members: List<Profile01WireMembershipMember>,
    ): Profile01CborCodec.CborValue.CborArray {
        val sorted =
            members
                .sortedBy { it.moduleId }
                .map { member ->
                    Profile01CborCodec.CborValue.CborArray(
                        listOf(
                            Profile01CborCodec.CborValue.Text(member.moduleId),
                            Profile01CborCodec.CborValue.ByteString(member.membershipIncarnationId.copyOf()),
                        ),
                    )
                }
        return Profile01CborCodec.CborValue.CborArray(sorted)
    }

    private fun u(key: Int): Profile01CborCodec.CborValue = Profile01CborCodec.CborValue.Unsigned(key.toLong())

    private fun u(value: Long): Profile01CborCodec.CborValue = Profile01CborCodec.CborValue.Unsigned(value)
}
