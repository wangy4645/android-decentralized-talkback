package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Protected establishment-profile trust semantics (PR-EP-1).
 *
 * Logically separate from [com.talkback.core.session.gbc.trust.profile.GenerationFactProfileTrustPayload].
 */
data class EstablishmentProfileTrustPayload(
    val taskProfileRevision: Long,
    val localModuleId: String,
    val establishmentBindings: List<EstablishmentProfileBinding>,
    val deploymentTrustDomainId: String? = null,
)

/**
 * One establishment binding row inside an authenticated profile revision.
 */
data class EstablishmentProfileBinding(
    val moduleId: String,
    val establishmentAlgorithm: EstablishmentAlgorithm,
    val establishmentKeyVersion: Long,
    val keyState: GenerationFactKeyState,
    val establishmentPublicKeySpki: ByteArray,
    val activatedAtRevision: Long,
    val retiredVerifyAtRevision: Long? = null,
)

enum class EstablishmentAlgorithm(val wireOrdinal: Int) {
    RSA_3072_OAEP_SHA256_MGF1_SHA1(1),
    ;

    companion object {
        fun fromWireOrdinal(ordinal: Int): EstablishmentAlgorithm? =
            entries.firstOrNull { it.wireOrdinal == ordinal }
    }
}

object EstablishmentRevisionIdentity {
    private val DOMAIN = "TALKBACK-ESTABLISHMENT-PROFILE-TRUST-ID-V1".encodeToByteArray()

    fun fromProtectedBytes(protectedBytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN)
        digest.update(protectedBytes)
        return digest.digest()
    }
}

/**
 * Deterministic protected payload codec for establishment profile semantics.
 */
object EstablishmentProfileRevisionCanonicalCodec {
    const val SCHEMA_V1: Int = 1
    const val SCHEMA_V2: Int = 2

    fun encode(payload: EstablishmentProfileTrustPayload): ByteArray {
        if (payload.deploymentTrustDomainId != null) {
            return encodeWithSchema(payload, SCHEMA_V2, payload.deploymentTrustDomainId)
        }
        return encodeWithSchema(payload, SCHEMA_V1, deploymentTrustDomainId = null)
    }

    fun decode(protectedBytes: ByteArray): EstablishmentProfileTrustPayload? =
        runCatching {
            if (protectedBytes.isEmpty()) return null
            when (protectedBytes[0].toInt() and 0xFF) {
                SCHEMA_V1 -> decodeV1(protectedBytes)
                SCHEMA_V2 -> decodeV2(protectedBytes)
                else -> null
            }
        }.getOrNull()

    private fun encodeWithSchema(
        payload: EstablishmentProfileTrustPayload,
        schema: Int,
        deploymentTrustDomainId: String?,
    ): ByteArray {
        val sortedBindings =
            payload.establishmentBindings.sortedWith(
                compareBy<EstablishmentProfileBinding> { it.moduleId }
                    .thenBy { it.establishmentKeyVersion },
            )
        val body =
            sortedBindings.fold(ByteArray(0)) { acc, binding ->
                acc + encodeBinding(binding)
            }
        val localBytes = utf8(payload.localModuleId)
        val domainBytes = deploymentTrustDomainId?.let { utf8(it) }
        val capacity =
            1 + 8 +
                (if (domainBytes != null) 4 + domainBytes.size else 0) +
                4 + localBytes.size + 4 + body.size
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
        buffer.put(schema.toByte())
        buffer.putLong(payload.taskProfileRevision)
        if (domainBytes != null) {
            buffer.putInt(domainBytes.size)
            buffer.put(domainBytes)
        }
        buffer.putInt(localBytes.size)
        buffer.put(localBytes)
        buffer.putInt(sortedBindings.size)
        buffer.put(body)
        return buffer.array().copyOf(buffer.position())
    }

    private fun decodeV1(protectedBytes: ByteArray): EstablishmentProfileTrustPayload? {
        val buffer = ByteBuffer.wrap(protectedBytes).order(ByteOrder.BIG_ENDIAN)
        if ((buffer.get().toInt() and 0xFF) != SCHEMA_V1) return null
        val revision = buffer.long
        val localModuleId = readUtf8(buffer) ?: return null
        val count = buffer.int
        if (count < 0) return null
        val bindings = ArrayList<EstablishmentProfileBinding>(count)
        repeat(count) {
            bindings += readBinding(buffer) ?: return null
        }
        if (buffer.hasRemaining()) return null
        return EstablishmentProfileTrustPayload(
            taskProfileRevision = revision,
            localModuleId = localModuleId,
            establishmentBindings = bindings,
            deploymentTrustDomainId = null,
        )
    }

    private fun decodeV2(protectedBytes: ByteArray): EstablishmentProfileTrustPayload? {
        val buffer = ByteBuffer.wrap(protectedBytes).order(ByteOrder.BIG_ENDIAN)
        if ((buffer.get().toInt() and 0xFF) != SCHEMA_V2) return null
        val revision = buffer.long
        val deploymentTrustDomainId = readUtf8(buffer) ?: return null
        if (deploymentTrustDomainId.isBlank()) return null
        val localModuleId = readUtf8(buffer) ?: return null
        val count = buffer.int
        if (count < 0) return null
        val bindings = ArrayList<EstablishmentProfileBinding>(count)
        repeat(count) {
            bindings += readBinding(buffer) ?: return null
        }
        if (buffer.hasRemaining()) return null
        return EstablishmentProfileTrustPayload(
            taskProfileRevision = revision,
            localModuleId = localModuleId,
            establishmentBindings = bindings,
            deploymentTrustDomainId = deploymentTrustDomainId,
        )
    }

    private fun encodeBinding(binding: EstablishmentProfileBinding): ByteArray {
        val moduleBytes = utf8(binding.moduleId)
        val retiredExtra = if (binding.retiredVerifyAtRevision == null) 1 else 1 + 8
        val capacity =
            4 + moduleBytes.size +
                1 + 8 + 1 + 8 +
                retiredExtra +
                4 + binding.establishmentPublicKeySpki.size
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
        putUtf8(buffer, moduleBytes)
        buffer.put(binding.establishmentAlgorithm.wireOrdinal.toByte())
        buffer.putLong(binding.establishmentKeyVersion)
        buffer.put(keyStateOrdinal(binding.keyState).toByte())
        buffer.putLong(binding.activatedAtRevision)
        if (binding.retiredVerifyAtRevision == null) {
            buffer.put(0)
        } else {
            buffer.put(1)
            buffer.putLong(binding.retiredVerifyAtRevision)
        }
        buffer.putInt(binding.establishmentPublicKeySpki.size)
        buffer.put(binding.establishmentPublicKeySpki)
        return buffer.array().copyOf(buffer.position())
    }

    private fun readBinding(buffer: ByteBuffer): EstablishmentProfileBinding? {
        val moduleId = readUtf8(buffer) ?: return null
        if (buffer.remaining() < 1 + 8 + 1 + 8 + 1) return null
        val algorithm =
            EstablishmentAlgorithm.fromWireOrdinal(buffer.get().toInt() and 0xFF) ?: return null
        val establishmentKeyVersion = buffer.long
        val keyState = readKeyState(buffer.get().toInt() and 0xFF) ?: return null
        val activatedAtRevision = buffer.long
        val hasRetired = buffer.get().toInt() != 0
        val retiredVerifyAtRevision =
            if (hasRetired) {
                if (buffer.remaining() < 8) return null
                buffer.long
            } else {
                null
            }
        if (buffer.remaining() < 4) return null
        val spkiLen = buffer.int
        if (spkiLen < 0 || buffer.remaining() < spkiLen) return null
        val spki = ByteArray(spkiLen)
        buffer.get(spki)
        return EstablishmentProfileBinding(
            moduleId = moduleId,
            establishmentAlgorithm = algorithm,
            establishmentKeyVersion = establishmentKeyVersion,
            keyState = keyState,
            establishmentPublicKeySpki = spki,
            activatedAtRevision = activatedAtRevision,
            retiredVerifyAtRevision = retiredVerifyAtRevision,
        )
    }

    private fun keyStateOrdinal(state: GenerationFactKeyState): Int =
        when (state) {
            GenerationFactKeyState.ACTIVE -> 1
            GenerationFactKeyState.RETIRED_VERIFY -> 2
            GenerationFactKeyState.REVOKED -> 3
            GenerationFactKeyState.PREPARED -> 4
            GenerationFactKeyState.RETIRED_OFF -> 5
        }

    private fun readKeyState(ordinal: Int): GenerationFactKeyState? =
        when (ordinal) {
            1 -> GenerationFactKeyState.ACTIVE
            2 -> GenerationFactKeyState.RETIRED_VERIFY
            3 -> GenerationFactKeyState.REVOKED
            4 -> GenerationFactKeyState.PREPARED
            5 -> GenerationFactKeyState.RETIRED_OFF
            else -> null
        }

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
