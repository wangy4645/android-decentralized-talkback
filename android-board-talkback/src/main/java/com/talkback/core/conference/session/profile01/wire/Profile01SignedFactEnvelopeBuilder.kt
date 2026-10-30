package com.talkback.core.conference.session.profile01.wire

/**
 * SignedFact envelope builder: fullCanonicalBytes + low-S ECDSA signature.
 */
object Profile01SignedFactEnvelopeBuilder {
    fun build(
        fullCanonicalBytes: ByteArray,
        signatureRs: ByteArray,
    ): ByteArray {
        require(fullCanonicalBytes.size <= Profile01WireConstants.MAX_FULL_FACT_BYTES) {
            "full fact too large"
        }
        require(signatureRs.size == Profile01WireConstants.SIGNATURE_BYTES) {
            "signature must be 64 bytes"
        }
        val envelope =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    Profile01CborCodec.CborValue.Unsigned(0) to
                        Profile01CborCodec.CborValue.ByteString(fullCanonicalBytes.copyOf()),
                    Profile01CborCodec.CborValue.Unsigned(1) to
                        Profile01CborCodec.CborValue.ByteString(signatureRs.copyOf()),
                ),
            )
        return Profile01CborCodec.encode(envelope)
    }
}
