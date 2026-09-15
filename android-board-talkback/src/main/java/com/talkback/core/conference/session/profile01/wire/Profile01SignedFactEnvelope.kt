package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.crypto.EcdsaP256Verifier

data class Profile01SignedFactEnvelope(
    val fullCanonicalBytes: ByteArray,
    val signatureRs: ByteArray,
) {
    companion object {
        fun parse(signedFactBytes: ByteArray): Profile01SignedFactEnvelope? =
            runCatching {
                require(signedFactBytes.size <= Profile01WireConstants.MAX_SIGNED_FACT_BYTES) {
                    "signed fact too large"
                }
                val envelope = Profile01CborCodec.decodeStrict(signedFactBytes)
        val map = envelope.intKeyMap() ?: return null
        if (map.size != 2 || !map.containsKey(0) || !map.containsKey(1)) return null
                val full = map[0]?.asByteString() ?: return null
                val signature = map[1]?.asByteString() ?: return null
                if (full.size > Profile01WireConstants.MAX_FULL_FACT_BYTES) return null
                if (signature.size != Profile01WireConstants.SIGNATURE_BYTES) return null
                Profile01SignedFactEnvelope(full.copyOf(), signature.copyOf())
            }.getOrNull()
    }
}

object Profile01SignedFactVerifier {
    fun verifySignature(
        fullCanonicalBytes: ByteArray,
        signatureRs: ByteArray,
        publicKeySpki: ByteArray,
    ): String {
        if (signatureRs.size != Profile01WireConstants.SIGNATURE_BYTES) {
            return "WRONG_SIGNATURE_LENGTH"
        }
        if (!EcdsaP256Verifier.validateSignatureForm(signatureRs)) {
            return "HIGH_S_SIGNATURE"
        }
        val publicKey =
            EcdsaP256Verifier.publicKeyFromSpki(publicKeySpki)
                ?: return "INVALID_INNER_SIGNATURE"
        val message =
            Profile01WireConstants.SIGNATURE_DOMAIN + fullCanonicalBytes
        return if (EcdsaP256Verifier.verify(message, signatureRs, publicKey)) {
            "PASS"
        } else {
            "INVALID_INNER_SIGNATURE"
        }
    }

    fun verifySignatureWithX963(
        fullCanonicalBytes: ByteArray,
        signatureRs: ByteArray,
        publicKeyX963: ByteArray,
    ): String {
        val publicKey =
            EcdsaP256Verifier.publicKeyFromX963(publicKeyX963)
                ?: return "INVALID_INNER_SIGNATURE"
        if (signatureRs.size != Profile01WireConstants.SIGNATURE_BYTES) {
            return "WRONG_SIGNATURE_LENGTH"
        }
        if (!EcdsaP256Verifier.validateSignatureForm(signatureRs)) {
            return "HIGH_S_SIGNATURE"
        }
        val message =
            Profile01WireConstants.SIGNATURE_DOMAIN + fullCanonicalBytes
        return if (EcdsaP256Verifier.verify(message, signatureRs, publicKey)) {
            "PASS"
        } else {
            "INVALID_INNER_SIGNATURE"
        }
    }
}
