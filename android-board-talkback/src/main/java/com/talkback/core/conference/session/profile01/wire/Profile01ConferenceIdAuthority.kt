package com.talkback.core.conference.session.profile01.wire

import java.security.MessageDigest

/**
 * Authority-owned stable Profile01 conference id128 derived from Meeting sessionId.
 *
 * Deterministic and repeatable — not randomly generated per topology publish.
 */
object Profile01ConferenceIdAuthority {
    private val DOMAIN: ByteArray =
        "TALKBACK-PROFILE01-CONFERENCE-ID-V1\u0000".encodeToByteArray()

    fun deriveId128Hex(sessionId: String): String =
        deriveId128Bytes(sessionId).toHexLower()

    fun deriveId128Bytes(sessionId: String): ByteArray {
        require(sessionId.isNotBlank()) { "sessionId required" }
        return MessageDigest.getInstance("SHA-256").run {
            update(DOMAIN)
            update(sessionId.toByteArray(Charsets.UTF_8))
            digest().copyOfRange(0, 16)
        }
    }
}
