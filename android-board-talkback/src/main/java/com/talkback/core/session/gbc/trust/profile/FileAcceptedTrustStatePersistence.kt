package com.talkback.core.session.gbc.trust.profile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FileAcceptedTrustStatePersistence(
    private val file: Path,
) : AcceptedTrustStatePersistence {
    init {
        file.parent?.let { Files.createDirectories(it) }
    }

    override fun save(state: AcceptedLocalTrustState) {
        val protectedBytes = state.toProtectedPayloadBytes()
        val identity = ProfileRevisionIdentity.fromProtectedBytes(protectedBytes)
        require(state.revisionIdentity.contentEquals(identity)) {
            "revision identity must be derived from canonical protected payload"
        }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        val buffer =
            ByteBuffer.allocate(8 + 32 + 4 + protectedBytes.size)
                .order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(state.taskProfileRevision)
        buffer.put(state.revisionIdentity)
        buffer.putInt(protectedBytes.size)
        buffer.put(protectedBytes)
        val encoded = buffer.array().copyOf(buffer.position())
        Files.write(temp, encoded)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun load(): AcceptedLocalTrustState? {
        if (!Files.exists(file)) return null
        val buffer = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() < 8 + 32 + 4) return null
        val revision = buffer.long
        val identity = ByteArray(32)
        buffer.get(identity)
        val protectedLen = buffer.int
        if (protectedLen < 0 || buffer.remaining() != protectedLen) return null
        val protectedBytes = ByteArray(protectedLen)
        buffer.get(protectedBytes)
        val derived = ProfileRevisionIdentity.fromProtectedBytes(protectedBytes)
        if (!derived.contentEquals(identity)) return null
        val payload = ProfileRevisionCanonicalCodec.decode(protectedBytes) ?: return null
        if (payload.taskProfileRevision != revision) return null
        return AcceptedLocalTrustState(
            taskProfileRevision = revision,
            revisionIdentity = identity,
            localModuleId = payload.localModuleId,
            bindingsByKey =
                payload.moduleBindings.associateBy {
                    AcceptedLocalTrustState.BindingKey(it.moduleId, it.signerKeyVersion)
                },
            deploymentTrustDomainId = payload.deploymentTrustDomainId,
        )
    }
}

private fun AcceptedLocalTrustState.toProtectedPayloadBytes(): ByteArray {
    val payload =
        GenerationFactProfileTrustPayload(
            taskProfileRevision = taskProfileRevision,
            localModuleId = localModuleId,
            moduleBindings = bindingsByKey.values.toList(),
            deploymentTrustDomainId = deploymentTrustDomainId,
        )
    return if (deploymentTrustDomainId != null) {
        ProfileRevisionCanonicalCodec.encodeV2(payload)
    } else {
        ProfileRevisionCanonicalCodec.encode(payload)
    }
}
