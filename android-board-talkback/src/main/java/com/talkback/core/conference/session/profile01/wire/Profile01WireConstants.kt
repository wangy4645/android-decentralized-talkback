package com.talkback.core.conference.session.profile01.wire

object Profile01WireConstants {
    val FACT_DIGEST_DOMAIN: ByteArray =
        "TALKBACK-CONFERENCE-FACT-V1\u0000".encodeToByteArray()
    val DESCRIPTOR_DIGEST_DOMAIN: ByteArray =
        "TALKBACK-MEDIA-GROUP-DESCRIPTOR-V1\u0000".encodeToByteArray()
    val SIGNATURE_DOMAIN: ByteArray =
        "TALKBACK-CONFERENCE-FACT-SIGNATURE-V1\u0000".encodeToByteArray()
    val RTP_SENDER_DISCRIMINATOR_DOMAIN: ByteArray =
        "TALKBACK-RTP-SENDER-DISCRIMINATOR-V1\u0000".encodeToByteArray()
    val RTP_SOURCE_DISCRIMINATOR_DOMAIN: ByteArray =
        "TALKBACK-RTP-SOURCE-DISCRIMINATOR-V1\u0000".encodeToByteArray()
    val MEMBERSHIP_KEY_CONTEXT_DOMAIN: ByteArray =
        "TALKBACK-MEMBERSHIP-KEY-CONTEXT-V1\u0000".encodeToByteArray()
    val MEDIA_KEY_COMMITMENT_DOMAIN: ByteArray =
        "TALKBACK-MEDIA-KEY-COMMITMENT-V1\u0000".encodeToByteArray()
    val SRTP_KEY_INFO_DOMAIN: ByteArray =
        "TALKBACK-SRTP-KEY-INFO-V1\u0000".encodeToByteArray()
    val PACKAGE_AAD_DOMAIN: ByteArray =
        "TALKBACK-MEDIA-KEY-PACKAGE-AAD-V1\u0000".encodeToByteArray()
    val RTP_KEY_CONTEXT_HINT_DOMAIN: ByteArray =
        "TALKBACK-RTP-KEY-CONTEXT-HINT-V1\u0000".encodeToByteArray()

    const val SCHEMA_VERSION: Int = 1
    const val FACT_TYPE_CREATION: Int = 1
    const val FACT_TYPE_MEMBERSHIP: Int = 2
    const val FACT_TYPE_SOURCE_DECLARATION: Int = 9
    const val FACT_TYPE_MEDIA_KEY_PACKAGE: Int = 11

    const val WRAPPED_PEK_BYTES: Int = 384
    const val GCM_NONCE_BYTES: Int = 12
    const val GCM_TAG_BYTES: Int = 16
    const val CONFERENCE_MEDIA_SECRET_BYTES: Int = 32

    const val MAX_FULL_FACT_BYTES: Int = 4096
    const val MAX_SIGNED_FACT_BYTES: Int = 4608
    const val SIGNATURE_BYTES: Int = 64

    const val SIGNER_KEY_CREATION: Int = 2
    const val SIGNER_KEY_SOURCE_DECLARATION: Int = 4
    const val SIGNER_KEY_MEDIA_KEY_PACKAGE: Int = 2
}
