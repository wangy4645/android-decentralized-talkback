package com.talkback.core.session.gbc.trust.profile

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * ADR-0057 PTI Layer A — protected Profile trust semantics (representation only).
 */
data class GenerationFactProfileTrustPayload(
    val taskProfileRevision: Long,
    val localModuleId: String,
    val moduleBindings: List<ModuleSigningBinding>,
    val deploymentTrustDomainId: String? = null,
)

data class ModuleSigningBinding(
    val moduleId: String,
    val signerKeyVersion: Long,
    val keyState: GenerationFactKeyState,
    val publicKeySpki: ByteArray,
    val activatedAtRevision: Long,
    val retiredVerifyAtRevision: Long? = null,
    val retirementCheckpoint: ProfileRetirementCheckpointBinding? = null,
)

data class ProfileRetirementCheckpointBinding(
    val trustBindingRevision: Long,
    val issuanceRoot: ByteArray,
)

data class AcceptedLocalTrustState(
    val taskProfileRevision: Long,
    val revisionIdentity: ByteArray,
    val localModuleId: String,
    val bindingsByKey: Map<BindingKey, ModuleSigningBinding>,
    val deploymentTrustDomainId: String? = null,
) {
    fun checkpoint(
        moduleId: String,
        signerKeyVersion: Long,
    ): ProfileRetirementCheckpointBinding? =
        bindingsByKey[BindingKey(moduleId, signerKeyVersion)]?.retirementCheckpoint

    data class BindingKey(
        val moduleId: String,
        val signerKeyVersion: Long,
    )
}

object ProfileRevisionIdentity {
    private val DOMAIN = "TALKBACK-GENERATION-FACT-PROFILE-TRUST-ID-V1".encodeToByteArray()

    /**
     * revisionIdentity MUST be derived from complete protected semantics (PTI-IA-T4).
     */
    fun fromProtectedBytes(protectedBytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN)
        digest.update(protectedBytes)
        return digest.digest()
    }
}

/**
 * Deterministic protected payload codec for Layer A representation/tests.
 */
object ProfileRevisionCanonicalCodec {
    const val SCHEMA_V1: Int = 1
    const val SCHEMA_V2: Int = 2

    fun encode(payload: GenerationFactProfileTrustPayload): ByteArray {
        if (payload.deploymentTrustDomainId != null) {
            return encodeV2(payload)
        }
        return encodeV1(payload)
    }

    fun encodeV2(payload: GenerationFactProfileTrustPayload): ByteArray {
        val domain = payload.deploymentTrustDomainId
        require(!domain.isNullOrBlank()) { "deploymentTrustDomainId required for schema v2" }
        return encodeWithSchema(payload, SCHEMA_V2, domain)
    }

    fun isProductionAuthenticationAdmissible(protectedBytes: ByteArray): Boolean {
        if (protectedBytes.isEmpty()) return false
        if ((protectedBytes[0].toInt() and 0xFF) != SCHEMA_V2) return false
        val payload = decode(protectedBytes) ?: return false
        return !payload.deploymentTrustDomainId.isNullOrBlank()
    }

    fun decode(protectedBytes: ByteArray): GenerationFactProfileTrustPayload? =
        runCatching {
            if (protectedBytes.isEmpty()) return null
            when (protectedBytes[0].toInt() and 0xFF) {
                SCHEMA_V1 -> decodeV1(protectedBytes)
                SCHEMA_V2 -> decodeV2(protectedBytes)
                else -> null
            }
        }.getOrNull()

    private fun encodeV1(payload: GenerationFactProfileTrustPayload): ByteArray =
        encodeWithSchema(payload, SCHEMA_V1, deploymentTrustDomainId = null)

    private fun encodeWithSchema(
        payload: GenerationFactProfileTrustPayload,
        schema: Int,
        deploymentTrustDomainId: String?,
    ): ByteArray {
        val sortedBindings =
            payload.moduleBindings.sortedWith(
                compareBy<ModuleSigningBinding> { it.moduleId }
                    .thenBy { it.signerKeyVersion },
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

    private fun decodeV1(protectedBytes: ByteArray): GenerationFactProfileTrustPayload? {
        val buffer = ByteBuffer.wrap(protectedBytes).order(ByteOrder.BIG_ENDIAN)
        if ((buffer.get().toInt() and 0xFF) != SCHEMA_V1) return null
        val revision = buffer.long
        val localModuleId = readUtf8(buffer) ?: return null
        val count = buffer.int
        if (count < 0) return null
        val bindings = ArrayList<ModuleSigningBinding>(count)
        repeat(count) {
            bindings += readBinding(buffer) ?: return null
        }
        if (buffer.hasRemaining()) return null
        return GenerationFactProfileTrustPayload(
            taskProfileRevision = revision,
            localModuleId = localModuleId,
            moduleBindings = bindings,
            deploymentTrustDomainId = null,
        )
    }

    private fun decodeV2(protectedBytes: ByteArray): GenerationFactProfileTrustPayload? {
        val buffer = ByteBuffer.wrap(protectedBytes).order(ByteOrder.BIG_ENDIAN)
        if ((buffer.get().toInt() and 0xFF) != SCHEMA_V2) return null
        val revision = buffer.long
        val deploymentTrustDomainId = readUtf8(buffer) ?: return null
        if (deploymentTrustDomainId.isBlank()) return null
        val localModuleId = readUtf8(buffer) ?: return null
        val count = buffer.int
        if (count < 0) return null
        val bindings = ArrayList<ModuleSigningBinding>(count)
        repeat(count) {
            bindings += readBinding(buffer) ?: return null
        }
        if (buffer.hasRemaining()) return null
        return GenerationFactProfileTrustPayload(
            taskProfileRevision = revision,
            localModuleId = localModuleId,
            moduleBindings = bindings,
            deploymentTrustDomainId = deploymentTrustDomainId,
        )
    }

    private fun encodeBinding(binding: ModuleSigningBinding): ByteArray {
        val moduleBytes = utf8(binding.moduleId)
        val checkpoint = binding.retirementCheckpoint
        val retiredExtra = if (binding.retiredVerifyAtRevision == null) 1 else 1 + 8
        val checkpointExtra = if (checkpoint == null) 1 else 1 + 8 + 32
        val capacity =
            4 + moduleBytes.size +
                8 + 1 + 8 +
                retiredExtra +
                4 + binding.publicKeySpki.size +
                checkpointExtra
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
        putUtf8(buffer, moduleBytes)
        buffer.putLong(binding.signerKeyVersion)
        buffer.put(keyStateOrdinal(binding.keyState).toByte())
        buffer.putLong(binding.activatedAtRevision)
        if (binding.retiredVerifyAtRevision == null) {
            buffer.put(0)
        } else {
            buffer.put(1)
            buffer.putLong(binding.retiredVerifyAtRevision)
        }
        buffer.putInt(binding.publicKeySpki.size)
        buffer.put(binding.publicKeySpki)
        if (checkpoint == null) {
            buffer.put(0)
        } else {
            buffer.put(1)
            buffer.putLong(checkpoint.trustBindingRevision)
            require(checkpoint.issuanceRoot.size == 32)
            buffer.put(checkpoint.issuanceRoot)
        }
        return buffer.array().copyOf(buffer.position())
    }

    private fun readBinding(buffer: ByteBuffer): ModuleSigningBinding? {
        val moduleId = readUtf8(buffer) ?: return null
        if (buffer.remaining() < 8 + 1 + 8 + 1) return null
        val signerKeyVersion = buffer.long
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
        if (spkiLen < 0 || buffer.remaining() < spkiLen + 1) return null
        val spki = ByteArray(spkiLen)
        buffer.get(spki)
        val hasCheckpoint = buffer.get().toInt() != 0
        val checkpoint =
            if (hasCheckpoint) {
                if (buffer.remaining() < 8 + 32) return null
                val trustBindingRevision = buffer.long
                val root = ByteArray(32)
                buffer.get(root)
                ProfileRetirementCheckpointBinding(trustBindingRevision, root)
            } else {
                null
            }
        return ModuleSigningBinding(
            moduleId = moduleId,
            signerKeyVersion = signerKeyVersion,
            keyState = keyState,
            publicKeySpki = spki,
            activatedAtRevision = activatedAtRevision,
            retiredVerifyAtRevision = retiredVerifyAtRevision,
            retirementCheckpoint = checkpoint,
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
