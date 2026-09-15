package com.talkback.core.session.gbc.trust.profile

import com.talkback.core.session.gbc.trust.GenerationFactKeyState

sealed class ProfileAuthenticationResult {
    data object Authenticated : ProfileAuthenticationResult()

    data class Rejected(
        val reason: String,
    ) : ProfileAuthenticationResult()
}

/**
 * Operational Profile authentication seam (Layer A: interface + fixture only).
 */
fun interface ProfileRevisionAuthenticator {
    fun authenticate(protectedBytes: ByteArray): ProfileAuthenticationResult
}

sealed class ProfileRevisionAcceptResult {
    data class Accepted(
        val state: AcceptedLocalTrustState,
        val idempotent: Boolean,
    ) : ProfileRevisionAcceptResult()

    data class AuthenticationRejected(
        val reason: String,
    ) : ProfileRevisionAcceptResult()

    data object RollbackRejected : ProfileRevisionAcceptResult()

    data object RevisionConflictRejected : ProfileRevisionAcceptResult()

    data class SemanticRejected(
        val reason: String,
    ) : ProfileRevisionAcceptResult()

    data class PersistenceFailed(
        val reason: String,
    ) : ProfileRevisionAcceptResult()
}

/**
 * Authentication-gated Profile trust acceptance (PTI Layer A).
 */
class ProfileRevisionAcceptor(
    private val authenticator: ProfileRevisionAuthenticator,
    private val store: AcceptedLocalTrustStateStore,
) {
    fun acceptProtectedRevision(candidateBytes: ByteArray): ProfileRevisionAcceptResult {
        val delivery = ProfileRevisionDeliveryCodec.resolve(candidateBytes)
        when (val auth = authenticator.authenticate(delivery.bytesForAuthentication)) {
            is ProfileAuthenticationResult.Rejected ->
                return ProfileRevisionAcceptResult.AuthenticationRejected(auth.reason)
            ProfileAuthenticationResult.Authenticated -> Unit
        }

        val protectedBytes = delivery.protectedSemanticBytes
        val payload =
            ProfileRevisionCanonicalCodec.decode(protectedBytes)
                ?: return ProfileRevisionAcceptResult.SemanticRejected("malformed protected payload")

        val canonicalBytes = ProfileRevisionCanonicalCodec.encode(payload)
        val revisionIdentity = ProfileRevisionIdentity.fromProtectedBytes(canonicalBytes)
        val current = store.currentSnapshot()

        if (current != null) {
            when {
                payload.taskProfileRevision < current.taskProfileRevision ->
                    return ProfileRevisionAcceptResult.RollbackRejected
                payload.taskProfileRevision == current.taskProfileRevision &&
                    !current.revisionIdentity.contentEquals(revisionIdentity) ->
                    return ProfileRevisionAcceptResult.RevisionConflictRejected
                payload.taskProfileRevision == current.taskProfileRevision &&
                    current.revisionIdentity.contentEquals(revisionIdentity) ->
                    return ProfileRevisionAcceptResult.Accepted(current, idempotent = true)
            }
        }

        val semantic = ProfileTrustSemanticValidator.validate(payload, current)
        if (semantic != null) {
            return ProfileRevisionAcceptResult.SemanticRejected(semantic)
        }

        val next =
            AcceptedLocalTrustState(
                taskProfileRevision = payload.taskProfileRevision,
                revisionIdentity = revisionIdentity.copyOf(),
                localModuleId = payload.localModuleId,
                bindingsByKey =
                    payload.moduleBindings.associateBy {
                        AcceptedLocalTrustState.BindingKey(it.moduleId, it.signerKeyVersion)
                    },
                deploymentTrustDomainId = payload.deploymentTrustDomainId,
            )

        return try {
            store.atomicReplace(next)
            ProfileRevisionAcceptResult.Accepted(next, idempotent = false)
        } catch (ex: Exception) {
            ProfileRevisionAcceptResult.PersistenceFailed(ex.message ?: "persist failed")
        }
    }

    fun acceptPayload(payload: GenerationFactProfileTrustPayload): ProfileRevisionAcceptResult =
        acceptProtectedRevision(ProfileRevisionCanonicalCodec.encode(payload))
}

internal object ProfileTrustSemanticValidator {
    fun validate(
        incoming: GenerationFactProfileTrustPayload,
        current: AcceptedLocalTrustState?,
    ): String? {
        if (incoming.localModuleId.isBlank()) return "localModuleId required"
        if (incoming.moduleBindings.isEmpty()) return "at least one binding required"

        for (binding in incoming.moduleBindings) {
            if (binding.moduleId.isBlank()) return "moduleId required"
            if (binding.publicKeySpki.isEmpty()) return "publicKeySpki required"
            if (binding.activatedAtRevision < 0) return "activatedAtRevision invalid"

            when (binding.keyState) {
                GenerationFactKeyState.RETIRED_VERIFY -> {
                    val checkpoint = binding.retirementCheckpoint
                    if (checkpoint == null) {
                        return "RETIRED_VERIFY requires retirement checkpoint"
                    }
                    if (checkpoint.issuanceRoot.size != 32) {
                        return "issuanceRoot must be 32 bytes"
                    }
                    if (binding.retiredVerifyAtRevision == null) {
                        return "RETIRED_VERIFY requires retiredVerifyAtRevision"
                    }
                }
                GenerationFactKeyState.ACTIVE,
                GenerationFactKeyState.REVOKED,
                GenerationFactKeyState.PREPARED,
                GenerationFactKeyState.RETIRED_OFF,
                -> Unit
            }

            val key = AcceptedLocalTrustState.BindingKey(binding.moduleId, binding.signerKeyVersion)
            val prior = current?.bindingsByKey?.get(key)
            if (prior != null) {
                if (!prior.publicKeySpki.contentEquals(binding.publicKeySpki)) {
                    return "signerKeyVersion rebind to different public key identity"
                }
                if (isNonActive(prior.keyState) && binding.keyState == GenerationFactKeyState.ACTIVE) {
                    return "rollback resurrection to ACTIVE forbidden"
                }
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
