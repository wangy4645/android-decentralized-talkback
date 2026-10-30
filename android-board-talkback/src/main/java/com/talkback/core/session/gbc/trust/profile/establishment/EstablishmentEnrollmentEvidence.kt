package com.talkback.core.session.gbc.trust.profile.establishment

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Device-side enrollment evidence (PR-EP-3).
 *
 * Contains public material only. [establishmentKeyVersion] is intentionally absent —
 * authority assigns it after operator approval.
 */
data class EstablishmentEnrollmentEvidence(
    val moduleId: String,
    val algorithm: EstablishmentAlgorithm,
    val publicKeySpki: ByteArray,
    val publicKeyFingerprintSha256Hex: String,
    val keyAlias: String,
    val localKeyGeneration: Long,
)

enum class EstablishmentEnrollmentTrigger {
    OPERATOR_INITIAL_ENROLLMENT,
    OPERATOR_KEY_ROTATION,
    IDEMPOTENT_REENROLLMENT,
}

object EstablishmentEnrollmentEvidenceCanonicalCodec {
    private const val SCHEMA_V1 = 1

    fun encode(evidence: EstablishmentEnrollmentEvidence): ByteArray {
        val moduleBytes = utf8(evidence.moduleId)
        val aliasBytes = utf8(evidence.keyAlias)
        val fingerprintBytes = utf8(evidence.publicKeyFingerprintSha256Hex)
        val capacity =
            1 + 4 + moduleBytes.size +
                1 + 4 + fingerprintBytes.size +
                4 + aliasBytes.size +
                8 + 4 + evidence.publicKeySpki.size
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
        buffer.put(SCHEMA_V1.toByte())
        putUtf8(buffer, moduleBytes)
        buffer.put(evidence.algorithm.wireOrdinal.toByte())
        putUtf8(buffer, fingerprintBytes)
        putUtf8(buffer, aliasBytes)
        buffer.putLong(evidence.localKeyGeneration)
        buffer.putInt(evidence.publicKeySpki.size)
        buffer.put(evidence.publicKeySpki)
        return buffer.array().copyOf(buffer.position())
    }

    fun decode(bytes: ByteArray): EstablishmentEnrollmentEvidence? =
        runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if ((buffer.get().toInt() and 0xFF) != SCHEMA_V1) return null
            val moduleId = readUtf8(buffer) ?: return null
            if (buffer.remaining() < 1) return null
            val algorithm =
                EstablishmentAlgorithm.fromWireOrdinal(buffer.get().toInt() and 0xFF) ?: return null
            val fingerprint = readUtf8(buffer) ?: return null
            val alias = readUtf8(buffer) ?: return null
            if (buffer.remaining() < 8 + 4) return null
            val localKeyGeneration = buffer.long
            val spkiLen = buffer.int
            if (spkiLen < 0 || buffer.remaining() < spkiLen) return null
            val spki = ByteArray(spkiLen)
            buffer.get(spki)
            if (buffer.hasRemaining()) return null
            EstablishmentEnrollmentEvidence(
                moduleId = moduleId,
                algorithm = algorithm,
                publicKeySpki = spki,
                publicKeyFingerprintSha256Hex = fingerprint,
                keyAlias = alias,
                localKeyGeneration = localKeyGeneration,
            )
        }.getOrNull()

    private fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    private fun putUtf8(
        buffer: ByteBuffer,
        bytes: ByteArray,
    ) {
        buffer.putInt(bytes.size)
        buffer.put(bytes)
    }

    private fun readUtf8(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 4) return null
        val length = buffer.int
        if (length < 0 || buffer.remaining() < length) return null
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes.toString(StandardCharsets.UTF_8)
    }
}

/**
 * Produces canonical enrollment evidence from local Keystore public identity.
 */
object EstablishmentEnrollmentEvidenceProducer {
    fun produce(
        identity: Profile01LocalEstablishmentIdentitySnapshot,
        localKeyGeneration: Long,
    ): EstablishmentEnrollmentEvidenceProduceResult {
        if (identity.moduleId.isBlank()) return EstablishmentEnrollmentEvidenceProduceResult.Rejected("moduleId required")
        if (localKeyGeneration <= 0L) {
            return EstablishmentEnrollmentEvidenceProduceResult.Rejected("localKeyGeneration must be positive")
        }
        if (!EstablishmentSpkiValidator.isSupportedRsa3072(identity.publicKeySpki)) {
            return EstablishmentEnrollmentEvidenceProduceResult.Rejected("unsupported establishment public key")
        }
        val evidence =
            EstablishmentEnrollmentEvidence(
                moduleId = identity.moduleId,
                algorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
                publicKeySpki = identity.publicKeySpki.copyOf(),
                publicKeyFingerprintSha256Hex = identity.publicKeySpki.sha256Hex(),
                keyAlias = identity.keyAlias,
                localKeyGeneration = localKeyGeneration,
            )
        val canonical = EstablishmentEnrollmentEvidenceCanonicalCodec.encode(evidence)
        val roundTrip = EstablishmentEnrollmentEvidenceCanonicalCodec.decode(canonical)
            ?: return EstablishmentEnrollmentEvidenceProduceResult.Rejected("canonical encode failed")
        if (!evidenceContentEquals(evidence, roundTrip)) {
            return EstablishmentEnrollmentEvidenceProduceResult.Rejected("canonical round-trip mismatch")
        }
        return EstablishmentEnrollmentEvidenceProduceResult.Ready(evidence, canonical)
    }

    private fun evidenceContentEquals(
        left: EstablishmentEnrollmentEvidence,
        right: EstablishmentEnrollmentEvidence,
    ): Boolean =
        left.moduleId == right.moduleId &&
            left.algorithm == right.algorithm &&
            left.publicKeySpki.contentEquals(right.publicKeySpki) &&
            left.publicKeyFingerprintSha256Hex == right.publicKeyFingerprintSha256Hex &&
            left.keyAlias == right.keyAlias &&
            left.localKeyGeneration == right.localKeyGeneration
}

sealed class EstablishmentEnrollmentEvidenceProduceResult {
    data class Ready(
        val evidence: EstablishmentEnrollmentEvidence,
        val canonicalBytes: ByteArray,
    ) : EstablishmentEnrollmentEvidenceProduceResult()

    data class Rejected(
        val reason: String,
    ) : EstablishmentEnrollmentEvidenceProduceResult()
}
