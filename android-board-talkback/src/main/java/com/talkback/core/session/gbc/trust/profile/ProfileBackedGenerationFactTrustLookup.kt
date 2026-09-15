package com.talkback.core.session.gbc.trust.profile

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import java.util.concurrent.atomic.AtomicReference

/**
 * Durable accepted trust snapshot with immutable publication (PTI-IA-T1/T2).
 */
class AcceptedLocalTrustStateStore(
    private val persistence: AcceptedTrustStatePersistence? = null,
) {
    private val snapshotRef = AtomicReference<AcceptedLocalTrustState?>(persistence?.load())

    fun currentSnapshot(): AcceptedLocalTrustState? = snapshotRef.get()

    fun atomicReplace(next: AcceptedLocalTrustState) {
        persistence?.save(next)
        snapshotRef.set(next)
    }

    fun reloadFromPersistence() {
        snapshotRef.set(persistence?.load())
    }
}

interface AcceptedTrustStatePersistence {
    fun save(state: AcceptedLocalTrustState)

    fun load(): AcceptedLocalTrustState?
}

/**
 * Test-only authenticator: authenticates when protected bytes are registered.
 * MUST NOT be used as production Profile authentication (Layer B).
 */
class FixtureProfileRevisionAuthenticator : ProfileRevisionAuthenticator {
    private val allowedProtectedPayloads = linkedSetOf<ByteArray>()

    fun allow(protectedBytes: ByteArray) {
        allowedProtectedPayloads += protectedBytes.copyOf()
    }

    fun allowPayload(payload: GenerationFactProfileTrustPayload) {
        allow(ProfileRevisionCanonicalCodec.encode(payload))
    }

    override fun authenticate(protectedBytes: ByteArray): ProfileAuthenticationResult {
        val allowed =
            allowedProtectedPayloads.any { registered -> registered.contentEquals(protectedBytes) }
        return if (allowed) {
            ProfileAuthenticationResult.Authenticated
        } else {
            ProfileAuthenticationResult.Rejected("fixture: protected payload not registered")
        }
    }
}

/**
 * Production read-only trust adapter (PTI Layer A).
 */
class ProfileBackedGenerationFactTrustLookup(
    private val store: AcceptedLocalTrustStateStore,
) : com.talkback.core.session.gbc.trust.GenerationFactTrustLookup {
    override fun lookupBinding(
        moduleId: String,
        signerKeyVersion: Long,
        trustBindingRevision: Long,
    ): com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult {
        val snapshot = store.currentSnapshot() ?: return unknownModule(moduleId, snapshot = null)
        val binding =
            snapshot.bindingsByKey[AcceptedLocalTrustState.BindingKey(moduleId, signerKeyVersion)]
                ?: return unknownModule(moduleId, snapshot)

        if (!isVerifiable(binding.keyState)) {
            return com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult.UnknownKeyVersion
        }

        if (trustBindingRevision < binding.activatedAtRevision) {
            return com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult.InvalidTrustBindingRevision
        }
        val retiredAt = binding.retiredVerifyAtRevision
        if (retiredAt != null && trustBindingRevision > retiredAt) {
            return com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult.InvalidTrustBindingRevision
        }

        return com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult.Found(
            com.talkback.core.session.gbc.trust.GenerationFactTrustBinding(
                moduleId = binding.moduleId,
                signerKeyVersion = binding.signerKeyVersion,
                keyState = binding.keyState,
                publicKeySpki = binding.publicKeySpki.copyOf(),
                activatedAtRevision = binding.activatedAtRevision,
                retiredVerifyAtRevision = binding.retiredVerifyAtRevision,
            ),
        )
    }

    override fun retirementCheckpoint(
        moduleId: String,
        signerKeyVersion: Long,
    ): com.talkback.core.session.gbc.trust.GenerationFactRetirementCheckpoint? {
        val snapshot = store.currentSnapshot() ?: return null
        val binding =
            snapshot.bindingsByKey[AcceptedLocalTrustState.BindingKey(moduleId, signerKeyVersion)]
                ?: return null
        val checkpoint = binding.retirementCheckpoint ?: return null
        if (binding.keyState != GenerationFactKeyState.RETIRED_VERIFY) return null
        return com.talkback.core.session.gbc.trust.GenerationFactRetirementCheckpoint(
            moduleId = moduleId,
            signerKeyVersion = signerKeyVersion,
            trustBindingRevision = checkpoint.trustBindingRevision,
            issuanceRoot = checkpoint.issuanceRoot.copyOf(),
        )
    }

    private fun unknownModule(
        moduleId: String,
        snapshot: AcceptedLocalTrustState?,
    ): com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult {
        val hasModule =
            snapshot?.bindingsByKey?.keys?.any { it.moduleId == moduleId } == true
        return if (hasModule) {
            com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult.UnknownKeyVersion
        } else {
            com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult.UnknownModule
        }
    }

    private fun isVerifiable(state: GenerationFactKeyState): Boolean =
        when (state) {
            GenerationFactKeyState.ACTIVE,
            GenerationFactKeyState.RETIRED_VERIFY,
            -> true
            GenerationFactKeyState.PREPARED,
            GenerationFactKeyState.RETIRED_OFF,
            GenerationFactKeyState.REVOKED,
            -> false
        }
}
