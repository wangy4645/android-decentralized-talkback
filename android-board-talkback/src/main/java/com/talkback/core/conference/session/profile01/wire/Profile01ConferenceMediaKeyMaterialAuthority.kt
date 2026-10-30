package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Authority-owned conference media secret + commitment for CREATION origin.
 *
 * Allocated once per Meeting session on first authoritative topology publish — not in Publisher.
 */
class Profile01ConferenceMediaKeyMaterialAuthority {
    data class Material(
        val conferenceMediaSecret: ByteArray,
        val membershipKeyContextDigest: ByteArray,
        val mediaKeyCommitment: ByteArray,
        val membershipVersion: Long = 0L,
        val mediaKeyEpoch: Long = 1L,
    )

    private val bySessionId = ConcurrentHashMap<String, Material>()

    fun ensureMaterial(
        sessionId: String,
        conferenceId: ByteArray,
        conferenceEpoch: Long,
        ownerModuleId: String,
        mediaGroupDescriptorDigest: ByteArray,
        membershipView: List<Profile01WireMembershipMember>,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
    ): Material =
        bySessionId.getOrPut(sessionId) {
            val secret = ByteArray(Profile01WireConstants.CONFERENCE_MEDIA_SECRET_BYTES).also {
                SecureRandom().nextBytes(it)
            }
            val contextDigest =
                Profile01MembershipKeyContextDigest.computeForCreation(
                    conferenceId = conferenceId,
                    conferenceEpoch = conferenceEpoch,
                    ownerModuleId = ownerModuleId,
                    mediaGroupDescriptorDigest = mediaGroupDescriptorDigest,
                    membershipVersion = membershipVersion,
                    completeMembershipView = membershipView,
                    mediaKeyEpoch = mediaKeyEpoch,
                )
            val commitment =
                Profile01PackageCrypto.computeMediaKeyCommitment(
                    conferenceMediaSecret = secret,
                    membershipKeyContextDigest = contextDigest,
                )
            Material(
                conferenceMediaSecret = secret.copyOf(),
                membershipKeyContextDigest = contextDigest.copyOf(),
                mediaKeyCommitment = commitment.copyOf(),
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
            )
        }

    fun rotateForMembership(
        sessionId: String,
        conferenceId: ByteArray,
        conferenceEpoch: Long,
        ownerModuleId: String,
        mediaGroupDescriptorDigest: ByteArray,
        membershipVersion: Long,
        previousMembershipDigest: ByteArray,
        membershipView: List<Profile01WireMembershipMember>,
        mediaKeyEpoch: Long,
    ): Material {
        require(previousMembershipDigest.size == 32) { "previousMembershipDigest must be 32 bytes" }
        val secret =
            ByteArray(Profile01WireConstants.CONFERENCE_MEDIA_SECRET_BYTES).also {
                SecureRandom().nextBytes(it)
            }
        val contextDigest =
            Profile01MembershipKeyContextDigest.computeForMembership(
                conferenceId = conferenceId,
                conferenceEpoch = conferenceEpoch,
                ownerModuleId = ownerModuleId,
                mediaGroupDescriptorDigest = mediaGroupDescriptorDigest,
                membershipVersion = membershipVersion,
                previousMembershipDigest = previousMembershipDigest,
                completeMembershipView = membershipView,
                mediaKeyEpoch = mediaKeyEpoch,
            )
        val commitment =
            Profile01PackageCrypto.computeMediaKeyCommitment(
                conferenceMediaSecret = secret,
                membershipKeyContextDigest = contextDigest,
            )
        val material =
            Material(
                conferenceMediaSecret = secret.copyOf(),
                membershipKeyContextDigest = contextDigest.copyOf(),
                mediaKeyCommitment = commitment.copyOf(),
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
            )
        bySessionId[sessionId] = material
        return material
    }

    fun materialForSession(sessionId: String): Material? = bySessionId[sessionId]

    fun clearSession(sessionId: String) {
        bySessionId.remove(sessionId)
    }
}
