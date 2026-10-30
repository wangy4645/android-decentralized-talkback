package com.talkback.core.conference.session.integration

/**
 * Peer READY confirmation on [com.talkback.core.model.SignalType.CHANNEL_TEXT].
 * Independent of [Profile01MediaKeyHeadRepublishRequest] (NACK).
 */
object Profile01HeadSupplementReadyAck {
    const val TEXT_PREFIX = "//P01/HSR/"
    const val STATUS_READY = "READY"

    fun encode(
        sessionId: String,
        snapshot: Profile01AuthoritativeHeadSnapshot,
        peerModuleId: String,
    ): String =
        TEXT_PREFIX +
            listOf(
                sessionId,
                snapshot.conferenceEpoch.toString(),
                snapshot.membershipVersion.toString(),
                snapshot.mediaKeyEpoch.toString(),
                snapshot.membershipFactDigestHex,
                peerModuleId,
                STATUS_READY,
            ).joinToString("|")

    fun decode(text: String): Decoded? {
        if (!text.startsWith(TEXT_PREFIX)) return null
        val parts = text.removePrefix(TEXT_PREFIX).split("|")
        if (parts.size != 7) return null
        val sessionId = parts[0].trim()
        val conferenceEpoch = parts[1].trim().toLongOrNull() ?: return null
        val membershipVersion = parts[2].trim().toLongOrNull() ?: return null
        val mediaKeyEpoch = parts[3].trim().toLongOrNull() ?: return null
        val digestHex = parts[4].trim()
        val peerModuleId = parts[5].trim()
        val status = parts[6].trim()
        if (sessionId.isBlank() || digestHex.isBlank() || peerModuleId.isBlank()) return null
        if (status != STATUS_READY) return null
        if (mediaKeyEpoch < 1L) return null
        val headDigest =
            Profile01HeadDigest.compute(
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                membershipFactDigestHex = digestHex,
            )
        return Decoded(
            sessionId = sessionId,
            peerModuleId = peerModuleId,
            status = status,
            snapshot =
                Profile01AuthoritativeHeadSnapshot(
                    conferenceIdHex = "",
                    sessionId = sessionId,
                    conferenceEpoch = conferenceEpoch,
                    membershipVersion = membershipVersion,
                    mediaKeyEpoch = mediaKeyEpoch,
                    membershipFactDigestHex = digestHex,
                ),
            headDigest = headDigest,
        )
    }

    data class Decoded(
        val sessionId: String,
        val peerModuleId: String,
        val status: String,
        val snapshot: Profile01AuthoritativeHeadSnapshot,
        val headDigest: String,
    )
}
