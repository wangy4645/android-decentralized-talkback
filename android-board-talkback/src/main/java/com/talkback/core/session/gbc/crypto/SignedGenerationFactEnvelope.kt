package com.talkback.core.session.gbc.crypto

import java.util.Base64

/**
 * Signed inner object and S4 verification-material bundle (PV-B).
 *
 * signedFactBytes layout:
 *   [authorityLen: u32][authority][contextLen: u32][context][signature: 64]
 *
 * verificationMaterial wire form (v1):
 *   v1|<base64 signedFactBytes>|<optional base64 inclusion proof>
 */
data class SignedGenerationFactEnvelope(
    val authorityCanonicalBytes: ByteArray,
    val verificationContextBytes: ByteArray,
    val signatureRs: ByteArray,
) {
    fun toSignedFactBytes(): ByteArray {
        val capacity =
            4 + authorityCanonicalBytes.size +
                4 + verificationContextBytes.size +
                64
        val buffer = java.nio.ByteBuffer.allocate(capacity).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putInt(authorityCanonicalBytes.size)
        buffer.put(authorityCanonicalBytes)
        buffer.putInt(verificationContextBytes.size)
        buffer.put(verificationContextBytes)
        require(signatureRs.size == 64) { "signature must be 64 bytes" }
        buffer.put(signatureRs)
        return buffer.array()
    }

    companion object {
        fun parseSignedFactBytes(bytes: ByteArray): SignedGenerationFactEnvelope? =
            runCatching {
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
                if (buffer.remaining() < 4) return null
                val authorityLen = buffer.int
                if (authorityLen < 0 || buffer.remaining() < authorityLen + 4) return null
                val authority = ByteArray(authorityLen)
                buffer.get(authority)
                val contextLen = buffer.int
                if (contextLen < 0 || buffer.remaining() < contextLen + 64) return null
                val context = ByteArray(contextLen)
                buffer.get(context)
                val signature = ByteArray(64)
                buffer.get(signature)
                if (buffer.hasRemaining()) return null
                SignedGenerationFactEnvelope(authority, context, signature)
            }.getOrNull()
    }
}

data class GenerationFactVerificationBundle(
    val signedFactBytes: ByteArray,
    val historicalInclusionProof: ByteArray? = null,
) {
    fun encodeToVerificationMaterial(): String {
        val signed = Base64.getEncoder().encodeToString(signedFactBytes)
        val proof =
            historicalInclusionProof?.let { Base64.getEncoder().encodeToString(it) }
                ?: ""
        return "$WIRE_PREFIX$signed|$proof"
    }

    companion object {
        private const val WIRE_PREFIX = "v1|"

        fun decodeFromVerificationMaterial(raw: String): GenerationFactVerificationBundle? =
            runCatching {
                val trimmed = raw.trim()
                if (!trimmed.startsWith(WIRE_PREFIX)) return null
                val body = trimmed.removePrefix(WIRE_PREFIX)
                val separator = body.indexOf('|')
                if (separator < 0) return null
                val signed = Base64.getDecoder().decode(body.substring(0, separator))
                val proofPart = body.substring(separator + 1)
                val proof = proofPart.takeIf { it.isNotBlank() }?.let { Base64.getDecoder().decode(it) }
                GenerationFactVerificationBundle(signed, proof)
            }.getOrNull()
    }
}
