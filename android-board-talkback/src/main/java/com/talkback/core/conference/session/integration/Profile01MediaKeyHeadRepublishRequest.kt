package com.talkback.core.conference.session.integration

/**
 * Profile01 control-plane hint on [com.talkback.core.model.SignalType.CHANNEL_TEXT]:
 * peer requests host to republish MEDIA_KEY_PACKAGE at current membership head.
 */
object Profile01MediaKeyHeadRepublishRequest {
    const val TEXT_PREFIX = "//P01/MKR/"

    fun encode(
        sessionId: String,
        mediaKeyEpoch: Long,
    ): String = "$TEXT_PREFIX$sessionId|$mediaKeyEpoch"

    fun decode(text: String): Decoded? {
        if (!text.startsWith(TEXT_PREFIX)) return null
        val body = text.removePrefix(TEXT_PREFIX)
        val parts = body.split("|", limit = 2)
        if (parts.size != 2) return null
        val sessionId = parts[0].trim()
        val epoch = parts[1].trim().toLongOrNull() ?: return null
        if (sessionId.isBlank() || epoch < 1L) return null
        return Decoded(sessionId = sessionId, mediaKeyEpoch = epoch)
    }

    data class Decoded(
        val sessionId: String,
        val mediaKeyEpoch: Long,
    )
}
