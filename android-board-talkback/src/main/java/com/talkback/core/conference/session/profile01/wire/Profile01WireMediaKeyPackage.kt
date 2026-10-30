package com.talkback.core.conference.session.profile01.wire

/**
 * Decoded MEDIA_KEY_PACKAGE authority (wire only — no lifecycle policy).
 */
data class Profile01WireMediaKeyPackage(
    val signedFactBytes: ByteArray,
    val factDigest: ByteArray,
    val fullCanonicalBytes: ByteArray,
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
)
