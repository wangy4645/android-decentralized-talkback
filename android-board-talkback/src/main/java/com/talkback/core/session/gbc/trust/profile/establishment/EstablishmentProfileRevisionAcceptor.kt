package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

sealed class EstablishmentProfileRevisionAcceptResult {
    data class Accepted(
        val state: AcceptedLocalEstablishmentTrustState,
        val idempotent: Boolean,
    ) : EstablishmentProfileRevisionAcceptResult()

    data object RollbackRejected : EstablishmentProfileRevisionAcceptResult()

    data object RevisionConflictRejected : EstablishmentProfileRevisionAcceptResult()

    data class SemanticRejected(
        val reason: String,
    ) : EstablishmentProfileRevisionAcceptResult()

    data class PersistenceFailed(
        val reason: String,
    ) : EstablishmentProfileRevisionAcceptResult()
}

/**
 * Accepts PAP-authenticated establishment profile revisions into
 * [AcceptedLocalEstablishmentTrustStateStore].
 *
 * Does not authenticate, generate keys, touch Keystore, or read signing trust state.
 */
class EstablishmentProfileRevisionAcceptor(
    private val store: AcceptedLocalEstablishmentTrustStateStore,
) {
    fun acceptAuthenticatedRevision(
        revision: AuthenticatedEstablishmentProfileRevision,
    ): EstablishmentProfileRevisionAcceptResult {
        val payload = revision.payload
        val revisionIdentity = revision.revisionIdentity
        val current = store.currentSnapshot()

        if (current != null) {
            when {
                payload.taskProfileRevision < current.taskProfileRevision ->
                    return EstablishmentProfileRevisionAcceptResult.RollbackRejected
                payload.taskProfileRevision == current.taskProfileRevision &&
                    !current.revisionIdentity.contentEquals(revisionIdentity) ->
                    return EstablishmentProfileRevisionAcceptResult.RevisionConflictRejected
                payload.taskProfileRevision == current.taskProfileRevision &&
                    current.revisionIdentity.contentEquals(revisionIdentity) ->
                    return EstablishmentProfileRevisionAcceptResult.Accepted(current, idempotent = true)
            }
        }

        val semantic = EstablishmentProfileTrustSemanticValidator.validate(payload, current)
        if (semantic != null) {
            return EstablishmentProfileRevisionAcceptResult.SemanticRejected(semantic)
        }

        val next =
            AcceptedLocalEstablishmentTrustState(
                taskProfileRevision = payload.taskProfileRevision,
                revisionIdentity = revisionIdentity.copyOf(),
                localModuleId = payload.localModuleId,
                bindingsByKey =
                    payload.establishmentBindings
                        .map { binding -> binding.toModuleEstablishmentBinding() }
                        .associateBy {
                            AcceptedLocalEstablishmentTrustState.BindingKey(
                                it.moduleId,
                                it.establishmentKeyVersion,
                            )
                        },
                deploymentTrustDomainId = payload.deploymentTrustDomainId,
            )

        return try {
            store.atomicReplace(next)
            EstablishmentProfileRevisionAcceptResult.Accepted(next, idempotent = false)
        } catch (ex: Exception) {
            EstablishmentProfileRevisionAcceptResult.PersistenceFailed(ex.message ?: "persist failed")
        }
    }

    private fun EstablishmentProfileBinding.toModuleEstablishmentBinding(): ModuleEstablishmentBinding =
        ModuleEstablishmentBinding(
            moduleId = moduleId,
            establishmentKeyVersion = establishmentKeyVersion,
            keyState = keyState,
            establishmentPublicKeySpki = establishmentPublicKeySpki.copyOf(),
            activatedAtRevision = activatedAtRevision,
            retiredVerifyAtRevision = retiredVerifyAtRevision,
        )
}

internal object EstablishmentProfileTrustSemanticValidator {
    fun validate(
        incoming: EstablishmentProfileTrustPayload,
        current: AcceptedLocalEstablishmentTrustState?,
    ): String? {
        if (incoming.localModuleId.isBlank()) return "localModuleId required"
        if (incoming.establishmentBindings.isEmpty()) return "at least one establishment binding required"

        val maxVersionByModule = mutableMapOf<String, Long>()
        current?.bindingsByKey?.values?.forEach { prior ->
            val priorMax = maxVersionByModule[prior.moduleId] ?: 0L
            if (prior.establishmentKeyVersion > priorMax) {
                maxVersionByModule[prior.moduleId] = prior.establishmentKeyVersion
            }
        }

        for (binding in incoming.establishmentBindings) {
            if (binding.moduleId.isBlank()) return "moduleId required"
            if (binding.establishmentKeyVersion <= 0L) return "establishmentKeyVersion must be positive"
            if (binding.activatedAtRevision < 0L) return "activatedAtRevision invalid"
            if (binding.establishmentAlgorithm != EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1) {
                return "unsupported establishment algorithm"
            }
            if (!EstablishmentSpkiValidator.isSupportedRsa3072(binding.establishmentPublicKeySpki)) {
                return "invalid or unsupported establishmentPublicKeySpki"
            }

            val key =
                AcceptedLocalEstablishmentTrustState.BindingKey(
                    binding.moduleId,
                    binding.establishmentKeyVersion,
                )
            val prior = current?.bindingsByKey?.get(key)
            if (prior != null &&
                !prior.establishmentPublicKeySpki.contentEquals(binding.establishmentPublicKeySpki)
            ) {
                return "establishmentKeyVersion rebind to different public key identity"
            }

            val priorMax = maxVersionByModule[binding.moduleId] ?: 0L
            if (binding.establishmentKeyVersion < priorMax) {
                return "establishmentKeyVersion regression"
            }
            if (binding.establishmentKeyVersion > priorMax) {
                maxVersionByModule[binding.moduleId] = binding.establishmentKeyVersion
            }

            if (prior != null &&
                isNonActive(prior.keyState) &&
                binding.keyState == GenerationFactKeyState.ACTIVE
            ) {
                return "rollback resurrection to ACTIVE forbidden"
            }
        }
        return null
    }

    private fun isNonActive(state: GenerationFactKeyState): Boolean =
        when (state) {
            GenerationFactKeyState.ACTIVE -> false
            GenerationFactKeyState.PREPARED,
            GenerationFactKeyState.RETIRED_VERIFY,
            GenerationFactKeyState.RETIRED_OFF,
            GenerationFactKeyState.REVOKED,
            -> true
        }
}

internal object EstablishmentSpkiValidator {
    fun isSupportedRsa3072(spki: ByteArray): Boolean =
        runCatching {
            if (spki.isEmpty()) return false
            val key =
                KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(spki))
            key is RSAPublicKey && key.modulus.bitLength() >= 3072
        }.getOrDefault(false)
}
