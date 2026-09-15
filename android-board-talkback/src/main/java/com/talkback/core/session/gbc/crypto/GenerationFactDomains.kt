package com.talkback.core.session.gbc.crypto

/**
 * Byte-exact domain separators for ADR-0057 Generation Fact crypto (PV-E).
 * MUST differ from each other and from ADR-0058 Conference Fact domains.
 */
object GenerationFactDomains {
    const val SCHEMA_VERSION: Int = 1

    val SIGNATURE_DOMAIN: ByteArray =
        "TALKBACK-GENERATION-FACT-SIG-V1".encodeToByteArray()

    val SEMANTIC_DIGEST_DOMAIN: ByteArray =
        "TALKBACK-GENERATION-FACT-DIGEST-V1".encodeToByteArray()

    val ISSUANCE_MERKLE_DOMAIN: ByteArray =
        "TALKBACK-GENERATION-FACT-ISSUANCE-V1".encodeToByteArray()
}
