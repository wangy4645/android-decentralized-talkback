package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01WireMemberSourceFact
import com.talkback.core.conference.session.profile01.Profile01WireMembershipFact
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.Profile01WireSessionFact

/**
 * Raw CBOR wire decoder: signedFactBytes → typed Profile 01 wire facts.
 *
 * Does not verify signatures, decide lifecycle, or touch registry policy.
 */
object Profile01WireCborDecoder {
    private val MODULE_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,32}$")

    sealed class DecodeResult<out T> {
        data class Ready<T>(val value: T) : DecodeResult<T>()

        data class Rejected(val reason: String) : DecodeResult<Nothing>()
    }

    fun decodeCreationSession(
        signedFactBytes: ByteArray,
        supplement: Profile01SessionMediaSupplement,
    ): DecodeResult<Profile01WireSessionFact> {
        val envelope = parseEnvelope(signedFactBytes) ?: return DecodeResult.Rejected("MALFORMED_SIGNED_FACT")
        val fact = decodeFullFact(envelope.fullCanonicalBytes) ?: return DecodeResult.Rejected("MALFORMED_FULL_FACT")
        if (fact.factType != Profile01WireConstants.FACT_TYPE_CREATION) {
            return DecodeResult.Rejected("UNSUPPORTED_FACT_TYPE")
        }
        val authority = fact.authority
        val conferenceId = authority.requireId128(0, "conferenceId")
        val conferenceEpoch = authority.requireU64(1, "conferenceEpoch")
        val mediaKeyEpoch = authority.requireU64(6, "initialMediaKeyEpoch")
        val membershipVersion = authority.requireU64(5, "initialMembershipVersion")
        val membershipView =
            parseMembershipView(authority[4])
                ?: return DecodeResult.Rejected("INVALID_MEMBERSHIP_VIEW")
        val mediaKeyCommitment =
            authority[7]?.asByteString()?.copyOf()
                ?: return DecodeResult.Rejected("MISSING_MEDIA_KEY_COMMITMENT")
        if (mediaKeyCommitment.size != 32) return DecodeResult.Rejected("INVALID_MEDIA_KEY_COMMITMENT")
        val descriptor = authority[3] ?: return DecodeResult.Rejected("MISSING_DESCRIPTOR")
        val endpoint = descriptorToEndpoint(descriptor) ?: return DecodeResult.Rejected("INVALID_DESCRIPTOR")
        val factDigest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
        return DecodeResult.Ready(
            Profile01WireSessionFact(
                signedFactBytes = signedFactBytes.copyOf(),
                factDigest = factDigest,
                conferenceId = conferenceId.toHex(),
                channelId = supplement.channelId,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                membershipView = membershipView,
                mediaKeyCommitment = mediaKeyCommitment,
                endpoint = endpoint,
                masterKey = supplement.masterKey.copyOf(),
                masterSalt = supplement.masterSalt.copyOf(),
                keyContextHint64 = supplement.keyContextHint64.copyOf(),
            ),
        )
    }

    fun decodeMembership(
        signedFactBytes: ByteArray,
    ): DecodeResult<Profile01WireMembershipFact> {
        val envelope = parseEnvelope(signedFactBytes) ?: return DecodeResult.Rejected("MALFORMED_SIGNED_FACT")
        val fact = decodeFullFact(envelope.fullCanonicalBytes) ?: return DecodeResult.Rejected("MALFORMED_FULL_FACT")
        if (fact.factType != Profile01WireConstants.FACT_TYPE_MEMBERSHIP) {
            return DecodeResult.Rejected("UNSUPPORTED_FACT_TYPE")
        }
        return runCatching {
            val authority = fact.authority
            val conferenceId = authority.requireId128(0, "conferenceId")
            val conferenceEpoch = authority.requireU64(1, "conferenceEpoch")
            val ownerModuleId = authority.requireModuleId(2, "ownerModuleId")
            val membershipVersion = authority.requireU64(3, "membershipVersion")
            val previousMembershipDigest =
                authority[4]?.asByteString()?.copyOf()
                    ?: throw Profile01CborCodec.CborException("missing previousMembershipDigest")
            val members =
                parseMembershipView(authority[5])
                    ?: throw Profile01CborCodec.CborException("invalid completeMembershipView")
            val mediaKeyEpoch = authority.requireU64(6, "mediaKeyEpoch")
            val mediaKeyCommitment =
                authority[7]?.asByteString()?.copyOf()
                    ?: throw Profile01CborCodec.CborException("missing mediaKeyCommitment")
            if (previousMembershipDigest.size != 32) {
                throw Profile01CborCodec.CborException("invalid previousMembershipDigest")
            }
            if (mediaKeyCommitment.size != 32) {
                throw Profile01CborCodec.CborException("invalid mediaKeyCommitment")
            }
            val factDigest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
            DecodeResult.Ready(
                Profile01WireMembershipFact(
                    signedFactBytes = signedFactBytes.copyOf(),
                    factDigest = factDigest,
                    conferenceId = conferenceId.toHex(),
                    conferenceEpoch = conferenceEpoch,
                    ownerModuleId = ownerModuleId,
                    membershipVersion = membershipVersion,
                    previousMembershipDigest = previousMembershipDigest,
                    members = members,
                    mediaKeyEpoch = mediaKeyEpoch,
                    mediaKeyCommitment = mediaKeyCommitment,
                ),
            )
        }.getOrElse {
            DecodeResult.Rejected("MALFORMED_MEMBERSHIP")
        }
    }

    fun decodeMediaKeyPackage(
        signedFactBytes: ByteArray,
    ): DecodeResult<Profile01WireMediaKeyPackage> {
        val envelope = parseEnvelope(signedFactBytes) ?: return DecodeResult.Rejected("MALFORMED_SIGNED_FACT")
        val fact = decodeFullFact(envelope.fullCanonicalBytes) ?: return DecodeResult.Rejected("MALFORMED_FULL_FACT")
        if (fact.factType != Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE) {
            return DecodeResult.Rejected("UNSUPPORTED_FACT_TYPE")
        }
        return decodeMediaKeyPackageAuthority(
            signedFactBytes = signedFactBytes,
            fullCanonicalBytes = envelope.fullCanonicalBytes,
            authority = fact.authority,
        )
    }

    fun decodeSourceDeclarationMember(
        signedFactBytes: ByteArray,
    ): DecodeResult<Profile01WireMemberSourceFact> {
        val envelope = parseEnvelope(signedFactBytes) ?: return DecodeResult.Rejected("MALFORMED_SIGNED_FACT")
        val fact = decodeFullFact(envelope.fullCanonicalBytes) ?: return DecodeResult.Rejected("MALFORMED_FULL_FACT")
        if (fact.factType != Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION) {
            return DecodeResult.Rejected("UNSUPPORTED_FACT_TYPE")
        }
        val authority = fact.authority
        val conferenceId = authority.requireId128(0, "conferenceId")
        val conferenceEpoch = authority.requireU64(1, "conferenceEpoch")
        val membershipVersion = authority.requireU64(2, "membershipVersion")
        val mediaKeyEpoch = authority.requireU64(3, "mediaKeyEpoch")
        val moduleId = authority.requireModuleId(4, "senderModuleId")
        val sourceGeneration = authority.requireU64(5, "sourceGeneration")
        val sourceInstanceId = authority.requireId128(6, "sourceInstanceId")
        val ssrc = authority.requireU32(7, "ssrc")
        val descriptorDigest =
            when (val digestValue = authority[9]) {
                null -> return DecodeResult.Rejected("MISSING_DESCRIPTOR_DIGEST")
                is Profile01CborCodec.CborValue.Null -> return DecodeResult.Rejected("MISSING_DESCRIPTOR_DIGEST")
                else -> digestValue.asByteString()?.copyOf()
                    ?: return DecodeResult.Rejected("INVALID_DESCRIPTOR_DIGEST")
            }
        if (descriptorDigest.size != 32) return DecodeResult.Rejected("INVALID_DESCRIPTOR_DIGEST")
        val admissionKey =
            Profile01SourceAdmissionDeriver.deriveSourceAdmissionKey48(
                conferenceId = conferenceId,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                senderModuleId = moduleId,
                sourceInstanceId = sourceInstanceId,
                ssrc = ssrc,
                mediaGroupDescriptorDigest = descriptorDigest,
            )
        val factDigest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
        return DecodeResult.Ready(
            Profile01WireMemberSourceFact(
                signedFactBytes = signedFactBytes.copyOf(),
                factDigest = factDigest,
                conferenceId = conferenceId.toHex(),
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                moduleId = moduleId,
                sourceGeneration = sourceGeneration,
                ssrc = ssrc,
                sourceAdmissionKey48 = admissionKey,
            ),
        )
    }

    fun parseEnvelope(signedFactBytes: ByteArray): Profile01SignedFactEnvelope? =
        Profile01SignedFactEnvelope.parse(signedFactBytes)

    /** Read wire factType without full semantic validation. */
    fun readFactType(signedFactBytes: ByteArray): Int? {
        val envelope = parseEnvelope(signedFactBytes) ?: return null
        return decodeFullFact(envelope.fullCanonicalBytes)?.factType
    }

    /**
     * Structural routing identity only — no signature verify, no lifecycle policy, not admission.
     */
    data class RoutingIdentity(
        val conferenceIdHex: String,
        val factType: Int,
        val factDigestHex: String,
    )

    /** Routing-only decode for pre-bind retention keying. */
    fun readRoutingIdentity(signedFactBytes: ByteArray): RoutingIdentity? =
        runCatching {
            val envelope = parseEnvelope(signedFactBytes) ?: return null
            val fact = decodeFullFact(envelope.fullCanonicalBytes) ?: return null
            val conferenceId = fact.authority.requireId128(0, "conferenceId")
            val factDigest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
            RoutingIdentity(
                conferenceIdHex = conferenceId.toHexLower(),
                factType = fact.factType,
                factDigestHex = factDigest.toHexLower(),
            )
        }.getOrNull()

    private fun decodeMediaKeyPackageAuthority(
        signedFactBytes: ByteArray,
        fullCanonicalBytes: ByteArray,
        authority: Map<Int, Profile01CborCodec.CborValue>,
    ): DecodeResult<Profile01WireMediaKeyPackage> {
        return runCatching {
            val conferenceId = authority.requireId128(0, "conferenceId")
            val conferenceEpoch = authority.requireU64(1, "conferenceEpoch")
            val ownerModuleId = authority.requireModuleId(2, "ownerModuleId")
            val membershipVersion = authority.requireU64(3, "membershipVersion")
            val mediaKeyEpoch = authority.requireU64(4, "mediaKeyEpoch")
            val membershipFactDigest =
                authority[5]?.asByteString()?.copyOf()
                    ?: throw Profile01CborCodec.CborException("missing membershipFactDigest")
            val mediaKeyCommitment =
                authority[6]?.asByteString()?.copyOf()
                    ?: throw Profile01CborCodec.CborException("missing mediaKeyCommitment")
            val recipientModuleId = authority.requireModuleId(7, "recipientModuleId")
            val packageIdentity = authority.requireId128(8, "packageIdentity")
            val wrappedPek =
                authority[9]?.asByteString()?.copyOf()
                    ?: throw Profile01CborCodec.CborException("missing wrappedPEK")
            val gcmNonce =
                authority[10]?.asByteString()?.copyOf()
                    ?: throw Profile01CborCodec.CborException("missing gcmNonce")
            val ciphertext =
                authority[11]?.asByteString()?.copyOf()
                    ?: throw Profile01CborCodec.CborException("missing ciphertext")
            val gcmTag =
                authority[12]?.asByteString()?.copyOf()
                    ?: throw Profile01CborCodec.CborException("missing gcmTag")
            val recipientKeyVersion = authority.requireU32(13, "recipientKeyVersion")
            if (membershipFactDigest.size != 32) throw Profile01CborCodec.CborException("invalid membershipFactDigest")
            if (mediaKeyCommitment.size != 32) throw Profile01CborCodec.CborException("invalid mediaKeyCommitment")
            if (wrappedPek.size != Profile01WireConstants.WRAPPED_PEK_BYTES) {
                throw Profile01CborCodec.CborException("invalid wrappedPEK")
            }
            if (gcmNonce.size != Profile01WireConstants.GCM_NONCE_BYTES) {
                throw Profile01CborCodec.CborException("invalid gcmNonce")
            }
            if (gcmTag.size != Profile01WireConstants.GCM_TAG_BYTES) {
                throw Profile01CborCodec.CborException("invalid gcmTag")
            }
            if (ciphertext.isEmpty() || ciphertext.size > 512) {
                throw Profile01CborCodec.CborException("invalid ciphertext")
            }
            val factDigest = Profile01FactDigest.computeFactDigest(fullCanonicalBytes)
            DecodeResult.Ready(
                Profile01WireMediaKeyPackage(
                    signedFactBytes = signedFactBytes.copyOf(),
                    factDigest = factDigest,
                    fullCanonicalBytes = fullCanonicalBytes.copyOf(),
                    conferenceId = conferenceId,
                    conferenceEpoch = conferenceEpoch,
                    ownerModuleId = ownerModuleId,
                    membershipVersion = membershipVersion,
                    mediaKeyEpoch = mediaKeyEpoch,
                    membershipFactDigest = membershipFactDigest,
                    mediaKeyCommitment = mediaKeyCommitment,
                    recipientModuleId = recipientModuleId,
                    packageIdentity = packageIdentity,
                    wrappedPek = wrappedPek,
                    gcmNonce = gcmNonce,
                    ciphertext = ciphertext,
                    gcmTag = gcmTag,
                    recipientKeyVersion = recipientKeyVersion.toLong(),
                ),
            )
        }.getOrElse {
            DecodeResult.Rejected("MALFORMED_MEDIA_KEY_PACKAGE")
        }
    }

    private data class DecodedFullFact(
        val factType: Int,
        val authority: Map<Int, Profile01CborCodec.CborValue>,
    )

    private fun decodeFullFact(fullCanonicalBytes: ByteArray): DecodedFullFact? =
        runCatching {
            val value = Profile01CborCodec.decodeStrict(fullCanonicalBytes)
            val map = value.intKeyMap() ?: return null
            if (map.size != 4 || !map.containsKey(0) || !map.containsKey(1) || !map.containsKey(2) || !map.containsKey(3)) {
                return null
            }
            val schema = map[0]?.asUnsigned()?.toInt() ?: return null
            if (schema != Profile01WireConstants.SCHEMA_VERSION) return null
            val factType = map[1]?.asUnsigned()?.toInt() ?: return null
            val authority = map[2]?.intKeyMap() ?: return null
            if (map[3]?.intKeyMap() == null) return null
            DecodedFullFact(factType = factType, authority = authority)
        }.getOrNull()

    private fun parseMembershipView(value: Profile01CborCodec.CborValue?): List<Profile01WireMembershipMember>? {
        val items = (value as? Profile01CborCodec.CborValue.CborArray)?.items ?: return null
        if (items.isEmpty() || items.size > 10) return null
        val members = ArrayList<Profile01WireMembershipMember>(items.size)
        var previousModuleBytes: ByteArray? = null
        for (item in items) {
            val entry = item.asArray() ?: return null
            if (entry.size != 2) return null
            val moduleId = entry[0].asText() ?: return null
            if (!MODULE_ID_REGEX.matches(moduleId)) return null
            val incarnation = entry[1].asByteString() ?: return null
            if (incarnation.size != 16) return null
            val moduleBytes = moduleId.encodeToByteArray()
            previousModuleBytes?.let { prior ->
                if (compareLex(moduleBytes, prior) <= 0) return null
            }
            previousModuleBytes = moduleBytes
            members +=
                Profile01WireMembershipMember(
                    moduleId = moduleId,
                    membershipIncarnationId = incarnation.copyOf(),
                )
        }
        return members
    }

    private fun compareLex(left: ByteArray, right: ByteArray): Int {
        val min = minOf(left.size, right.size)
        for (i in 0 until min) {
            val delta = (left[i].toInt() and 0xFF) - (right[i].toInt() and 0xFF)
            if (delta != 0) return delta
        }
        return left.size - right.size
    }

    private fun descriptorToEndpoint(descriptor: Profile01CborCodec.CborValue): MediaGroupEndpointBinding? {
        val map = descriptor.intKeyMap() ?: return null
        val family = map[0]?.asUnsigned()?.toInt() ?: return null
        val address = map[1]?.asByteString() ?: return null
        val mediaPort = map[2]?.asUnsigned()?.toInt() ?: return null
        val underlayScope = map[4]?.asUnsigned()?.toInt() ?: return null
        if (mediaPort < 1 || mediaPort > 65535) return null
        val multicastAddress =
            when (family) {
                4 -> {
                    if (address.size != 4) return null
                    address.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                else -> return null
            }
        return MediaGroupEndpointBinding(
            multicastAddress = multicastAddress,
            mediaPort = mediaPort,
            underlayScopeId = "p01-underlay-$underlayScope",
        )
    }

    private fun Map<Int, Profile01CborCodec.CborValue>.requireU64(
        key: Int,
        label: String,
    ): Long {
        val value = this[key]?.asUnsigned() ?: throw Profile01CborCodec.CborException("missing $label")
        return value
    }

    private fun Map<Int, Profile01CborCodec.CborValue>.requireU32(
        key: Int,
        label: String,
    ): Int {
        val value = requireU64(key, label)
        if (value > 0xFFFFFFFFL) throw Profile01CborCodec.CborException("out of range $label")
        return value.toInt()
    }

    private fun Map<Int, Profile01CborCodec.CborValue>.requireId128(
        key: Int,
        label: String,
    ): ByteArray {
        val value = this[key]?.asByteString() ?: throw Profile01CborCodec.CborException("missing $label")
        if (value.size != 16) throw Profile01CborCodec.CborException("invalid $label")
        return value.copyOf()
    }

    private fun Map<Int, Profile01CborCodec.CborValue>.requireModuleId(
        key: Int,
        label: String,
    ): String {
        val value = this[key]?.asText() ?: throw Profile01CborCodec.CborException("missing $label")
        if (!MODULE_ID_REGEX.matches(value)) throw Profile01CborCodec.CborException("invalid $label")
        return value
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
}
