package com.talkback.core.conference.session.profile01.wire

/**
 * Frozen SOURCE_DECLARATION authority payload — read-only input to wire encoder.
 *
 * [declaringModuleId] is both wire sender and signing module (B1-HC1 self-declaration).
 */
data class Profile01SourceDeclarationAuthoritySnapshot(
    val conferenceId: ByteArray,
    val conferenceEpoch: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val declaringModuleId: String,
    val sourceGeneration: Long,
    val sourceInstanceId: ByteArray,
    val ssrc: Int,
    val mediaGroupDescriptorDigest: ByteArray,
    val signerKeyVersion: Long,
)
