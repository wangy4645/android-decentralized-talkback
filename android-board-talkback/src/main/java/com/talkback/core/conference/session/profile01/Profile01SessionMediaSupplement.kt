package com.talkback.core.conference.session.profile01

/**
 * Session media key material for CREATION ingress.
 *
 * [masterKey]/[masterSalt]/[keyContextHint64] typically come from MEDIA_KEY_PACKAGE decrypt.
 * [channelId] is product/session scope and is not present in the package wire authority.
 */
data class Profile01SessionMediaSupplement(
    val channelId: String,
    val masterKey: ByteArray,
    val masterSalt: ByteArray,
    val keyContextHint64: ByteArray,
    val mediaKeyEpoch: Long = 0L,
    val membershipVersion: Long = 0L,
    val conferenceId: String = "",
)
