package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement

/**
 * SRTP key material derived from a verified MEDIA_KEY_PACKAGE decrypt.
 *
 * Does not own membership/generation policy — carries epoch binding only.
 */
data class Profile01DerivedMediaKeyMaterial(
    val conferenceId: String,
    val conferenceEpoch: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val masterKey: ByteArray,
    val masterSalt: ByteArray,
    val keyContextHint64: ByteArray,
    val membershipKeyContextDigest: ByteArray,
) {
    fun toSessionSupplement(channelId: String): Profile01SessionMediaSupplement =
        Profile01SessionMediaSupplement(
            channelId = channelId,
            masterKey = masterKey.copyOf(),
            masterSalt = masterSalt.copyOf(),
            keyContextHint64 = keyContextHint64.copyOf(),
            mediaKeyEpoch = mediaKeyEpoch,
            membershipVersion = membershipVersion,
            conferenceId = conferenceId,
        )
}
