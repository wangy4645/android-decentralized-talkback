package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember

/**
 * Canonical CBOR encoder for Profile01 signed facts (production origin).
 */
object Profile01WireCborEncoder {
    fun encodeCreationFullFact(snapshot: Profile01CreationAuthoritySnapshot): ByteArray {
        require(snapshot.conferenceId.size == 16) { "conferenceId must be id128" }
        require(snapshot.mediaKeyCommitment.size == 32) { "mediaKeyCommitment must be 32 bytes" }
        require(snapshot.membershipView.isNotEmpty()) { "membershipView required" }
        val authority =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to Profile01CborCodec.CborValue.ByteString(snapshot.conferenceId.copyOf()),
                    u(1) to u(snapshot.conferenceEpoch),
                    u(2) to Profile01CborCodec.CborValue.Text(snapshot.ownerModuleId),
                    u(3) to snapshot.mediaGroupDescriptor,
                    u(4) to Profile01MembershipKeyContextDigest.encodeMembershipView(snapshot.membershipView),
                    u(5) to u(snapshot.initialMembershipVersion),
                    u(6) to u(snapshot.initialMediaKeyEpoch),
                    u(7) to Profile01CborCodec.CborValue.ByteString(snapshot.mediaKeyCommitment.copyOf()),
                    u(8) to u(Profile01MediaGroupDescriptor.keyDistributionProfile()),
                    u(9) to Profile01CborCodec.CborValue.Null,
                ),
            )
        val signedMetadata =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to u(snapshot.signerKeyVersion),
                    u(1) to Profile01CborCodec.CborValue.Null,
                ),
            )
        val fullFact =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to u(Profile01WireConstants.SCHEMA_VERSION.toLong()),
                    u(1) to u(Profile01WireConstants.FACT_TYPE_CREATION.toLong()),
                    u(2) to authority,
                    u(3) to signedMetadata,
                ),
            )
        return Profile01CborCodec.encode(fullFact)
    }

    fun encodeMediaKeyPackageFullFact(snapshot: Profile01MediaKeyPackageAuthoritySnapshot): ByteArray {
        require(snapshot.conferenceId.size == 16) { "conferenceId must be id128" }
        require(snapshot.packageIdentity.size == 16) { "packageIdentity must be id128" }
        require(snapshot.membershipFactDigest.size == 32) { "membershipFactDigest must be 32 bytes" }
        require(snapshot.mediaKeyCommitment.size == 32) { "mediaKeyCommitment must be 32 bytes" }
        require(snapshot.wrappedPek.size == Profile01WireConstants.WRAPPED_PEK_BYTES) { "invalid wrappedPek" }
        require(snapshot.gcmNonce.size == Profile01WireConstants.GCM_NONCE_BYTES) { "invalid gcmNonce" }
        require(snapshot.gcmTag.size == Profile01WireConstants.GCM_TAG_BYTES) { "invalid gcmTag" }
        require(snapshot.ciphertext.isNotEmpty() && snapshot.ciphertext.size <= 512) { "invalid ciphertext" }
        val authority =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to Profile01CborCodec.CborValue.ByteString(snapshot.conferenceId.copyOf()),
                    u(1) to u(snapshot.conferenceEpoch),
                    u(2) to Profile01CborCodec.CborValue.Text(snapshot.ownerModuleId),
                    u(3) to u(snapshot.membershipVersion),
                    u(4) to u(snapshot.mediaKeyEpoch),
                    u(5) to Profile01CborCodec.CborValue.ByteString(snapshot.membershipFactDigest.copyOf()),
                    u(6) to Profile01CborCodec.CborValue.ByteString(snapshot.mediaKeyCommitment.copyOf()),
                    u(7) to Profile01CborCodec.CborValue.Text(snapshot.recipientModuleId),
                    u(8) to Profile01CborCodec.CborValue.ByteString(snapshot.packageIdentity.copyOf()),
                    u(9) to Profile01CborCodec.CborValue.ByteString(snapshot.wrappedPek.copyOf()),
                    u(10) to Profile01CborCodec.CborValue.ByteString(snapshot.gcmNonce.copyOf()),
                    u(11) to Profile01CborCodec.CborValue.ByteString(snapshot.ciphertext.copyOf()),
                    u(12) to Profile01CborCodec.CborValue.ByteString(snapshot.gcmTag.copyOf()),
                    u(13) to u(snapshot.recipientKeyVersion),
                ),
            )
        val signedMetadata =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to u(snapshot.signerKeyVersion),
                    u(1) to Profile01CborCodec.CborValue.Null,
                ),
            )
        val fullFact =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to u(Profile01WireConstants.SCHEMA_VERSION.toLong()),
                    u(1) to u(Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE.toLong()),
                    u(2) to authority,
                    u(3) to signedMetadata,
                ),
            )
        return Profile01CborCodec.encode(fullFact)
    }

    fun encodeMembershipFullFact(snapshot: Profile01MembershipAuthoritySnapshot): ByteArray {
        require(snapshot.conferenceId.size == 16) { "conferenceId must be id128" }
        require(snapshot.previousMembershipDigest.size == 32) { "previousMembershipDigest must be 32 bytes" }
        require(snapshot.mediaKeyCommitment.size == 32) { "mediaKeyCommitment must be 32 bytes" }
        require(snapshot.members.isNotEmpty()) { "members required" }
        val authority =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to Profile01CborCodec.CborValue.ByteString(snapshot.conferenceId.copyOf()),
                    u(1) to u(snapshot.conferenceEpoch),
                    u(2) to Profile01CborCodec.CborValue.Text(snapshot.ownerModuleId),
                    u(3) to u(snapshot.membershipVersion),
                    u(4) to Profile01CborCodec.CborValue.ByteString(snapshot.previousMembershipDigest.copyOf()),
                    u(5) to Profile01MembershipKeyContextDigest.encodeMembershipView(snapshot.members),
                    u(6) to u(snapshot.mediaKeyEpoch),
                    u(7) to Profile01CborCodec.CborValue.ByteString(snapshot.mediaKeyCommitment.copyOf()),
                ),
            )
        val signedMetadata =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to u(snapshot.signerKeyVersion),
                    u(1) to Profile01CborCodec.CborValue.Null,
                ),
            )
        val fullFact =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to u(Profile01WireConstants.SCHEMA_VERSION.toLong()),
                    u(1) to u(Profile01WireConstants.FACT_TYPE_MEMBERSHIP.toLong()),
                    u(2) to authority,
                    u(3) to signedMetadata,
                ),
            )
        return Profile01CborCodec.encode(fullFact)
    }

    fun encodeSourceDeclarationFullFact(snapshot: Profile01SourceDeclarationAuthoritySnapshot): ByteArray {
        require(snapshot.conferenceId.size == 16) { "conferenceId must be id128" }
        require(snapshot.sourceInstanceId.size == 16) { "sourceInstanceId must be id128" }
        require(snapshot.mediaGroupDescriptorDigest.size == 32) { "descriptor digest must be 32 bytes" }
        require(snapshot.sourceGeneration > 0L) { "sourceGeneration must be > 0" }
        require(snapshot.declaringModuleId.isNotBlank()) { "declaringModuleId required" }
        val authority =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to Profile01CborCodec.CborValue.ByteString(snapshot.conferenceId.copyOf()),
                    u(1) to u(snapshot.conferenceEpoch),
                    u(2) to u(snapshot.membershipVersion),
                    u(3) to u(snapshot.mediaKeyEpoch),
                    u(4) to Profile01CborCodec.CborValue.Text(snapshot.declaringModuleId),
                    u(5) to u(snapshot.sourceGeneration),
                    u(6) to Profile01CborCodec.CborValue.ByteString(snapshot.sourceInstanceId.copyOf()),
                    u(7) to u(snapshot.ssrc.toLong() and 0xFFFF_FFFFL),
                    u(9) to Profile01CborCodec.CborValue.ByteString(snapshot.mediaGroupDescriptorDigest.copyOf()),
                ),
            )
        val signedMetadata =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to u(snapshot.signerKeyVersion),
                    u(1) to Profile01CborCodec.CborValue.Null,
                ),
            )
        val fullFact =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to u(Profile01WireConstants.SCHEMA_VERSION.toLong()),
                    u(1) to u(Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION.toLong()),
                    u(2) to authority,
                    u(3) to signedMetadata,
                ),
            )
        return Profile01CborCodec.encode(fullFact)
    }

    private fun u(key: Int): Profile01CborCodec.CborValue = Profile01CborCodec.CborValue.Unsigned(key.toLong())

    private fun u(value: Long): Profile01CborCodec.CborValue = Profile01CborCodec.CborValue.Unsigned(value)
}
