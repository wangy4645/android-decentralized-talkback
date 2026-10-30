package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants

/**
 * Validates Host-retained MEMBERSHIP signed facts for rejoin authoritative catch-up.
 */
object Profile01AuthoritativeMembershipChain {
    data class Link(
        val membershipVersion: Long,
        val signedFactBytes: ByteArray,
        val factDigest: ByteArray,
        val previousMembershipDigest: ByteArray,
        val conferenceIdHex: String,
        val mediaKeyEpoch: Long,
        val conferenceEpoch: Long,
    )

    sealed class Validation {
        data class Valid(val links: List<Link>) : Validation()

        data class Invalid(val reason: String) : Validation()
    }

    fun parseLink(signedFactBytes: ByteArray): Link? {
        when (val decoded = Profile01WireCborDecoder.decodeMembership(signedFactBytes)) {
            is Profile01WireCborDecoder.DecodeResult.Rejected -> return null
            is Profile01WireCborDecoder.DecodeResult.Ready -> {
                val wire = decoded.value
                val envelope =
                    Profile01SignedFactEnvelope.parse(signedFactBytes) ?: return null
                val digest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
                return Link(
                    membershipVersion = wire.membershipVersion,
                    signedFactBytes = signedFactBytes.copyOf(),
                    factDigest = digest.copyOf(),
                    previousMembershipDigest = wire.previousMembershipDigest.copyOf(),
                    conferenceIdHex = wire.conferenceId,
                    mediaKeyEpoch = wire.mediaKeyEpoch,
                    conferenceEpoch = wire.conferenceEpoch,
                )
            }
        }
    }

    fun validateAgainstCreationAndHead(
        creationSignedFactBytes: ByteArray,
        chainSignedFacts: List<ByteArray>,
        expectedHead: Profile01MembershipGeneration,
    ): Validation {
        val creationEnvelope =
            Profile01SignedFactEnvelope.parse(creationSignedFactBytes)
                ?: return Validation.Invalid("MALFORMED_CREATION")
        val creationRouting =
            Profile01WireCborDecoder.readRoutingIdentity(creationSignedFactBytes)
                ?: return Validation.Invalid("MALFORMED_CREATION")
        if (creationRouting.factType != Profile01WireConstants.FACT_TYPE_CREATION) {
            return Validation.Invalid("NOT_CREATION_FACT")
        }
        val creationDigest = Profile01FactDigest.computeFactDigest(creationEnvelope.fullCanonicalBytes)
        if (chainSignedFacts.isEmpty()) {
            if (expectedHead.membershipVersion == 0L &&
                expectedHead.generationFactDigest.contentEquals(creationDigest)
            ) {
                return Validation.Valid(emptyList())
            }
            return Validation.Invalid("EMPTY_MEMBERSHIP_CHAIN")
        }
        val links =
            chainSignedFacts.mapNotNull { parseLink(it) }.sortedBy { it.membershipVersion }
        if (links.size != chainSignedFacts.size) {
            return Validation.Invalid("CHAIN_PARSE_FAILED")
        }
        val conferenceIdHex = expectedHead.conferenceId
        links.forEach { link ->
            if (link.conferenceIdHex != conferenceIdHex) {
                return Validation.Invalid("CONFERENCE_ID_MISMATCH")
            }
        }
        if (!links.last().factDigest.contentEquals(expectedHead.generationFactDigest)) {
            return Validation.Invalid("CHAIN_HEAD_MISMATCH")
        }
        if (links.last().membershipVersion != expectedHead.membershipVersion) {
            return Validation.Invalid("CHAIN_VERSION_MISMATCH")
        }
        if (links.last().mediaKeyEpoch != expectedHead.mediaKeyEpoch) {
            return Validation.Invalid("CHAIN_MEDIA_KEY_EPOCH_MISMATCH")
        }
        if (links.last().conferenceEpoch != expectedHead.conferenceEpoch) {
            return Validation.Invalid("CHAIN_CONFERENCE_EPOCH_MISMATCH")
        }
        for (index in links.indices) {
            val link = links[index]
            val expectedPrevious =
                if (index == 0) {
                    creationDigest
                } else {
                    links[index - 1].factDigest
                }
            if (!link.previousMembershipDigest.contentEquals(expectedPrevious)) {
                return Validation.Invalid("CHAIN_PREDECESSOR_BREAK_AT_V${link.membershipVersion}")
            }
        }
        for (index in 1 until links.size) {
            if (links[index].membershipVersion <= links[index - 1].membershipVersion) {
                return Validation.Invalid("CHAIN_VERSION_NOT_STRICTLY_INCREASING")
            }
        }
        return Validation.Valid(links)
    }
}
