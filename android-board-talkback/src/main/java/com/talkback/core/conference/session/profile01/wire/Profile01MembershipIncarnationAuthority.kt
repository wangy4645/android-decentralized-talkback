package com.talkback.core.conference.session.profile01.wire

import java.security.MessageDigest

/**
 * Authority-owned stable membershipIncarnationId (id128) per conference member.
 */
object Profile01MembershipIncarnationAuthority {
    private val DOMAIN: ByteArray =
        "TALKBACK-PROFILE01-MEMBERSHIP-INCARNATION-V1\u0000".encodeToByteArray()

    fun deriveIncarnationId128(
        conferenceId: ByteArray,
        moduleId: String,
    ): ByteArray {
        require(conferenceId.size == 16) { "conferenceId must be 16 bytes" }
        require(moduleId.isNotBlank()) { "moduleId required" }
        return MessageDigest.getInstance("SHA-256").run {
            update(DOMAIN)
            update(conferenceId)
            update(moduleId.toByteArray(Charsets.UTF_8))
            digest().copyOfRange(0, 16)
        }
    }
}
