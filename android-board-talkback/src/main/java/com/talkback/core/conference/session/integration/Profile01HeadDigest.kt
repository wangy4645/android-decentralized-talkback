package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration

object Profile01HeadDigest {
    fun compute(
        conferenceEpoch: Long,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
        membershipFactDigestHex: String,
    ): String =
        "$conferenceEpoch|$membershipVersion|$mediaKeyEpoch|$membershipFactDigestHex"

    fun fromGeneration(generation: Profile01MembershipGeneration): String =
        compute(
            conferenceEpoch = generation.conferenceEpoch,
            membershipVersion = generation.membershipVersion,
            mediaKeyEpoch = generation.mediaKeyEpoch,
            membershipFactDigestHex = hexAscii(generation.generationFactDigest),
        )

    fun hexAscii(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

data class Profile01AuthoritativeHeadSnapshot(
    val conferenceIdHex: String,
    val sessionId: String,
    val conferenceEpoch: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val membershipFactDigestHex: String,
) {
    val headDigest: String =
        Profile01HeadDigest.compute(
            conferenceEpoch = conferenceEpoch,
            membershipVersion = membershipVersion,
            mediaKeyEpoch = mediaKeyEpoch,
            membershipFactDigestHex = membershipFactDigestHex,
        )

    companion object {
        fun from(
            generation: Profile01MembershipGeneration,
            sessionId: String,
        ): Profile01AuthoritativeHeadSnapshot =
            Profile01AuthoritativeHeadSnapshot(
                conferenceIdHex = generation.conferenceId,
                sessionId = sessionId,
                conferenceEpoch = generation.conferenceEpoch,
                membershipVersion = generation.membershipVersion,
                mediaKeyEpoch = generation.mediaKeyEpoch,
                membershipFactDigestHex = Profile01HeadDigest.hexAscii(generation.generationFactDigest),
            )
    }
}
