package com.talkback.core.session.gbc.crypto

import com.talkback.core.model.PredecessorWire
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Deterministic canonical encoding for Generation Fact authority and verification context (C2).
 */
object GenerationFactCanonicalCodec {
    private const val PREDECESSOR_ABSENT: Byte = 0
    private const val PREDECESSOR_NONE: Byte = 1
    private const val PREDECESSOR_ID: Byte = 2

    data class AuthoritySemantics(
        val generationIdentity: String,
        val predecessor: PredecessorWire,
        val originAuthorityIdentity: String,
        val attestsCurrent: Boolean,
        val resolvesConflictSet: Set<String> = emptySet(),
    )

    data class VerificationContext(
        val originAuthorityIdentity: String,
        val signerKeyVersion: Long,
        val trustBindingRevision: Long,
    )

    fun encodeAuthority(authority: AuthoritySemantics): ByteArray {
        val sortedConflicts = authority.resolvesConflictSet.filter { it.isNotBlank() }.sorted()
        val generationBytes = utf8(authority.generationIdentity)
        val originBytes = utf8(authority.originAuthorityIdentity)
        val predecessorBytes = encodePredecessor(authority.predecessor)
        val conflictBytes = encodeStringList(sortedConflicts)
        val capacity =
            1 + // schema
                4 + generationBytes.size +
                predecessorBytes.size +
                4 + originBytes.size +
                1 + // attestsCurrent
                conflictBytes.size
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
        buffer.put(GenerationFactDomains.SCHEMA_VERSION.toByte())
        putLengthPrefixed(buffer, generationBytes)
        buffer.put(predecessorBytes)
        putLengthPrefixed(buffer, originBytes)
        buffer.put(if (authority.attestsCurrent) 1.toByte() else 0.toByte())
        buffer.put(conflictBytes)
        return buffer.array()
    }

    fun decodeAuthority(bytes: ByteArray): AuthoritySemantics? =
        runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val schema = buffer.get().toInt() and 0xFF
            if (schema != GenerationFactDomains.SCHEMA_VERSION) return null
            val generationIdentity = readLengthPrefixedUtf8(buffer) ?: return null
            val predecessor = readPredecessor(buffer) ?: return null
            val originAuthorityIdentity = readLengthPrefixedUtf8(buffer) ?: return null
            if (!buffer.hasRemaining()) return null
            val attestsCurrent = buffer.get().toInt() != 0
            val conflicts = readStringList(buffer)
            if (buffer.hasRemaining()) return null
            AuthoritySemantics(
                generationIdentity = generationIdentity,
                predecessor = predecessor,
                originAuthorityIdentity = originAuthorityIdentity,
                attestsCurrent = attestsCurrent,
                resolvesConflictSet = conflicts.toSet(),
            )
        }.getOrNull()

    fun encodeVerificationContext(context: VerificationContext): ByteArray {
        val originBytes = utf8(context.originAuthorityIdentity)
        val capacity = 1 + 4 + originBytes.size + 8 + 8
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
        buffer.put(GenerationFactDomains.SCHEMA_VERSION.toByte())
        putLengthPrefixed(buffer, originBytes)
        buffer.putLong(context.signerKeyVersion)
        buffer.putLong(context.trustBindingRevision)
        return buffer.array()
    }

    fun decodeVerificationContext(bytes: ByteArray): VerificationContext? =
        runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val schema = buffer.get().toInt() and 0xFF
            if (schema != GenerationFactDomains.SCHEMA_VERSION) return null
            val originAuthorityIdentity = readLengthPrefixedUtf8(buffer) ?: return null
            if (buffer.remaining() < 16) return null
            val signerKeyVersion = buffer.getLong()
            val trustBindingRevision = buffer.getLong()
            if (buffer.hasRemaining()) return null
            VerificationContext(
                originAuthorityIdentity = originAuthorityIdentity,
                signerKeyVersion = signerKeyVersion,
                trustBindingRevision = trustBindingRevision,
            )
        }.getOrNull()

    fun computeSemanticDigest(authorityCanonicalBytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(GenerationFactDomains.SEMANTIC_DIGEST_DOMAIN)
        digest.update(authorityCanonicalBytes)
        return digest.digest()
    }

    fun semanticDigestHex(authorityCanonicalBytes: ByteArray): String =
        computeSemanticDigest(authorityCanonicalBytes).toHexLower()

    fun commitmentFromSemanticDigestHex(hex: String): ByteArray? {
        val normalized = hex.trim().lowercase()
        if (normalized.length != 64 || normalized.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun signatureInput(
        authorityCanonicalBytes: ByteArray,
        verificationContextBytes: ByteArray,
    ): ByteArray {
        val out = ByteArray(
            GenerationFactDomains.SIGNATURE_DOMAIN.size +
                authorityCanonicalBytes.size +
                verificationContextBytes.size,
        )
        var offset = 0
        GenerationFactDomains.SIGNATURE_DOMAIN.copyInto(out, offset)
        offset += GenerationFactDomains.SIGNATURE_DOMAIN.size
        authorityCanonicalBytes.copyInto(out, offset)
        offset += authorityCanonicalBytes.size
        verificationContextBytes.copyInto(out, offset)
        return out
    }

    private fun encodePredecessor(predecessor: PredecessorWire): ByteArray =
        when (predecessor) {
            PredecessorWire.Absent ->
                byteArrayOf(PREDECESSOR_ABSENT)
            PredecessorWire.None ->
                byteArrayOf(PREDECESSOR_NONE)
            is PredecessorWire.Id -> {
                val idBytes = utf8(predecessor.generationIdentity)
                ByteBuffer.allocate(1 + 4 + idBytes.size)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(PREDECESSOR_ID)
                    .also { putLengthPrefixed(it, idBytes) }
                    .array()
            }
        }

    private fun readPredecessor(buffer: ByteBuffer): PredecessorWire? {
        if (!buffer.hasRemaining()) return null
        return when (buffer.get()) {
            PREDECESSOR_ABSENT -> PredecessorWire.Absent
            PREDECESSOR_NONE -> PredecessorWire.None
            PREDECESSOR_ID -> {
                val id = readLengthPrefixedUtf8(buffer) ?: return null
                PredecessorWire.Id(id)
            }
            else -> null
        }
    }

    private fun encodeStringList(values: List<String>): ByteArray {
        val body =
            values.fold(ByteArray(0)) { acc, value ->
                val bytes = utf8(value)
                val chunk = ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.BIG_ENDIAN)
                putLengthPrefixed(chunk, bytes)
                acc + chunk.array()
            }
        return ByteBuffer.allocate(4 + body.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(values.size)
            .put(body)
            .array()
    }

    private fun readStringList(buffer: ByteBuffer): List<String> {
        if (!buffer.hasRemaining()) return emptyList()
        if (buffer.remaining() < 4) return emptyList()
        val count = buffer.int
        val out = ArrayList<String>(count)
        repeat(count) {
            out += readLengthPrefixedUtf8(buffer) ?: return emptyList()
        }
        return out
    }

    private fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    private fun putLengthPrefixed(buffer: ByteBuffer, bytes: ByteArray) {
        buffer.putInt(bytes.size)
        buffer.put(bytes)
    }

    private fun readLengthPrefixedUtf8(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 4) return null
        val length = buffer.int
        if (length < 0 || buffer.remaining() < length) return null
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun ByteArray.toHexLower(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
