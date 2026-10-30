package com.talkback.core.session.gbc.trust.profile

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Signed Profile revision delivery envelope (Layer B production authentication).
 *
 * Cryptographic domain is isolated from Generation Fact signatures (PTI Layer B).
 */
object AuthenticatedProfileRevisionEnvelope {
    private const val ENVELOPE_VERSION: Int = 1
    private val SIGNATURE_DOMAIN = "TALKBACK-PROFILE-REVISION-AUTH-V1".encodeToByteArray()

    data class Parsed(
        val protectedBytes: ByteArray,
        val signatureRs: ByteArray,
    )

    fun build(
        protectedBytes: ByteArray,
        signatureRs: ByteArray,
    ): ByteArray {
        require(signatureRs.size == 64) { "signature must be 64-byte R||S" }
        val buffer =
            ByteBuffer.allocate(1 + 4 + protectedBytes.size + 64)
                .order(ByteOrder.BIG_ENDIAN)
        buffer.put(ENVELOPE_VERSION.toByte())
        buffer.putInt(protectedBytes.size)
        buffer.put(protectedBytes)
        buffer.put(signatureRs)
        return buffer.array().copyOf(buffer.position())
    }

    fun parse(candidateBytes: ByteArray): Parsed? =
        runCatching {
            val buffer = ByteBuffer.wrap(candidateBytes).order(ByteOrder.BIG_ENDIAN)
            if (buffer.remaining() < 1 + 4 + 64) return null
            if ((buffer.get().toInt() and 0xFF) != ENVELOPE_VERSION) return null
            val protectedLen = buffer.int
            if (protectedLen < 0 || buffer.remaining() < protectedLen + 64) return null
            val protectedBytes = ByteArray(protectedLen)
            buffer.get(protectedBytes)
            val signatureRs = ByteArray(64)
            buffer.get(signatureRs)
            if (buffer.hasRemaining()) return null
            Parsed(protectedBytes, signatureRs)
        }.getOrNull()

    fun signatureInput(protectedBytes: ByteArray): ByteArray {
        val domainLen = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(SIGNATURE_DOMAIN.size).array()
        val protectedLen = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(protectedBytes.size).array()
        return domainLen + SIGNATURE_DOMAIN + protectedLen + protectedBytes
    }

    fun isEnvelope(candidateBytes: ByteArray): Boolean = parse(candidateBytes) != null
}

/**
 * Resolves candidate delivery bytes for authentication vs semantic decode.
 */
object ProfileRevisionDeliveryCodec {
    data class ResolvedDelivery(
        val bytesForAuthentication: ByteArray,
        val protectedSemanticBytes: ByteArray,
    )

    fun resolve(candidateBytes: ByteArray): ResolvedDelivery {
        val envelope = AuthenticatedProfileRevisionEnvelope.parse(candidateBytes)
        return if (envelope != null) {
            ResolvedDelivery(
                bytesForAuthentication = candidateBytes.copyOf(),
                protectedSemanticBytes = envelope.protectedBytes.copyOf(),
            )
        } else {
            ResolvedDelivery(
                bytesForAuthentication = candidateBytes.copyOf(),
                protectedSemanticBytes = candidateBytes.copyOf(),
            )
        }
    }
}
