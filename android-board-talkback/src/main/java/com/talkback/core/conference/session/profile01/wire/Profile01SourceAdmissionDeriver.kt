package com.talkback.core.conference.session.profile01.wire

import java.security.MessageDigest

/**
 * Profile 02 Q3 source admission discriminator derivation from accepted Source authority.
 */
object Profile01SourceAdmissionDeriver {
    fun deriveSourceAdmissionKey48(
        conferenceId: ByteArray,
        conferenceEpoch: Long,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
        senderModuleId: String,
        sourceInstanceId: ByteArray,
        ssrc: Int,
        mediaGroupDescriptorDigest: ByteArray,
    ): ByteArray {
        require(conferenceId.size == 16) { "conferenceId must be 16 bytes" }
        require(sourceInstanceId.size == 16) { "sourceInstanceId must be 16 bytes" }
        require(mediaGroupDescriptorDigest.size == 32) { "descriptor digest must be 32 bytes" }
        require(senderModuleId.isNotEmpty() && senderModuleId.length <= 32) { "invalid moduleId" }
        val moduleBytes = senderModuleId.encodeToByteArray()
        val input =
            conferenceId +
                conferenceEpoch.u64Be() +
                membershipVersion.u64Be() +
                mediaKeyEpoch.u64Be() +
                byteArrayOf(moduleBytes.size.toByte()) +
                moduleBytes +
                sourceInstanceId +
                ssrc.u32Be() +
                mediaGroupDescriptorDigest
        val sender = truncatedSha256(Profile01WireConstants.RTP_SENDER_DISCRIMINATOR_DOMAIN, input, 2)
        val source = truncatedSha256(Profile01WireConstants.RTP_SOURCE_DISCRIMINATOR_DOMAIN, input, 4)
        return sender + source
    }

    private fun truncatedSha256(domain: ByteArray, data: ByteArray, length: Int): ByteArray {
        val digest =
            MessageDigest.getInstance("SHA-256").run {
                update(domain)
                update(data)
                digest()
            }
        return digest.copyOfRange(0, length)
    }

    private fun Long.u64Be(): ByteArray =
        byteArrayOf(
            (this shr 56).toByte(),
            (this shr 48).toByte(),
            (this shr 40).toByte(),
            (this shr 32).toByte(),
            (this shr 24).toByte(),
            (this shr 16).toByte(),
            (this shr 8).toByte(),
            this.toByte(),
        )

    private fun Int.u32Be(): ByteArray =
        byteArrayOf(
            (this ushr 24).toByte(),
            (this ushr 16).toByte(),
            (this ushr 8).toByte(),
            this.toByte(),
        )
}
