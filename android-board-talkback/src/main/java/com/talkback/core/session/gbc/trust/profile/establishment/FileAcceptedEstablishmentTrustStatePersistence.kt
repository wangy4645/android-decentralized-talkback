package com.talkback.core.session.gbc.trust.profile.establishment

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FileAcceptedEstablishmentTrustStatePersistence(
    private val file: Path,
) : AcceptedEstablishmentTrustStatePersistence {
    init {
        file.parent?.let { Files.createDirectories(it) }
    }

    override fun save(state: AcceptedLocalEstablishmentTrustState) {
        val protectedBytes = state.toProtectedPayloadBytes()
        val identity = EstablishmentRevisionIdentity.fromProtectedBytes(protectedBytes)
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

    override fun load(): AcceptedLocalEstablishmentTrustState? {
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
        val derived = EstablishmentRevisionIdentity.fromProtectedBytes(protectedBytes)
        if (!derived.contentEquals(identity)) return null
        val payload = EstablishmentProfileRevisionCanonicalCodec.decode(protectedBytes) ?: return null
        if (payload.taskProfileRevision != revision) return null
        return AcceptedLocalEstablishmentTrustState(
            taskProfileRevision = revision,
            revisionIdentity = identity,
            localModuleId = payload.localModuleId,
            bindingsByKey =
                payload.establishmentBindings
                    .map { binding ->
                        ModuleEstablishmentBinding(
                            moduleId = binding.moduleId,
                            establishmentKeyVersion = binding.establishmentKeyVersion,
                            keyState = binding.keyState,
                            establishmentPublicKeySpki = binding.establishmentPublicKeySpki.copyOf(),
                            activatedAtRevision = binding.activatedAtRevision,
                            retiredVerifyAtRevision = binding.retiredVerifyAtRevision,
                        )
                    }.associateBy {
                        AcceptedLocalEstablishmentTrustState.BindingKey(
                            it.moduleId,
                            it.establishmentKeyVersion,
                        )
                    },
            deploymentTrustDomainId = payload.deploymentTrustDomainId,
        )
    }
}

private fun AcceptedLocalEstablishmentTrustState.toProtectedPayloadBytes(): ByteArray =
    EstablishmentProfileRevisionCanonicalCodec.encode(
        EstablishmentProfileTrustPayload(
            taskProfileRevision = taskProfileRevision,
            localModuleId = localModuleId,
            establishmentBindings =
                bindingsByKey.values.map { binding ->
                    EstablishmentProfileBinding(
                        moduleId = binding.moduleId,
                        establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
                        establishmentKeyVersion = binding.establishmentKeyVersion,
                        keyState = binding.keyState,
                        establishmentPublicKeySpki = binding.establishmentPublicKeySpki.copyOf(),
                        activatedAtRevision = binding.activatedAtRevision,
                        retiredVerifyAtRevision = binding.retiredVerifyAtRevision,
                    )
                },
            deploymentTrustDomainId = deploymentTrustDomainId,
        ),
    )
