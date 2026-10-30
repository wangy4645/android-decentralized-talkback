package com.talkback.core.conference.session.profile01.wire

/**
 * Frozen MEDIA_KEY_PACKAGE authority payload — read-only input to wire encoder.
 */
data class Profile01MediaKeyPackageAuthoritySnapshot(
    val conferenceId: ByteArray,
    val conferenceEpoch: Long,
    val ownerModuleId: String,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val membershipFactDigest: ByteArray,
    val mediaKeyCommitment: ByteArray,
    val recipientModuleId: String,
    val packageIdentity: ByteArray,
    val wrappedPek: ByteArray,
    val gcmNonce: ByteArray,
    val ciphertext: ByteArray,
    val gcmTag: ByteArray,
    val recipientKeyVersion: Long,
    val signerKeyVersion: Long,
)
