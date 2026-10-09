package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.issuance.GenerationFactSignerPersistenceState
import com.talkback.core.session.gbc.issuance.GbcFieldEstablishedSignerSupport
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import java.io.File

/**
 * Resolves the local Profile01 ECDSA signer from persisted field material and the current
 * accepted trust snapshot. Re-evaluated on each call so late-arriving trust bindings are visible
 * without recreating the app runtime.
 */
fun interface Profile01SignedFactSignerSource {
    fun resolve(): Profile01SignedFactSignerResolveResult

    companion object {
        fun fixed(signer: Profile01SignedFactSigner): Profile01SignedFactSignerSource =
            Profile01SignedFactSignerSource { Profile01SignedFactSignerResolveResult.Ready(signer) }
    }
}

sealed interface Profile01SignedFactSignerResolveResult {
    data class Ready(val signer: Profile01SignedFactSigner) : Profile01SignedFactSignerResolveResult

    data class Unavailable(val reason: String) : Profile01SignedFactSignerResolveResult
}

class Profile01FieldEstablishedSignedFactSignerSource(
    private val trustDir: File,
    private val trustStore: AcceptedLocalTrustStateStore,
    private val localModuleId: String,
) : Profile01SignedFactSignerSource {
    override fun resolve(): Profile01SignedFactSignerResolveResult {
        val persistence = GbcFieldEstablishedSignerSupport.signerPersistence(trustDir)
        when (val state = persistence.currentState()) {
            GenerationFactSignerPersistenceState.Empty ->
                return Profile01SignedFactSignerResolveResult.Unavailable("NO_PERSISTED_SIGNER")
            is GenerationFactSignerPersistenceState.Established -> {
                val signerKeyVersion = activeSignerKeyVersion(trustStore, localModuleId)
                if (signerKeyVersion <= 0L) {
                    return Profile01SignedFactSignerResolveResult.Unavailable("NO_ACTIVE_TRUST_BINDING")
                }
                val signer =
                    Profile01PersistedSignedFactSigner.fromPkcs8(
                        pkcs8PrivateKey = state.pkcs8PrivateKey,
                        signerModuleId = localModuleId,
                        signerKeyVersion = signerKeyVersion,
                    )
                        ?: return Profile01SignedFactSignerResolveResult.Unavailable("INVALID_SIGNER_MATERIAL")
                return Profile01SignedFactSignerResolveResult.Ready(signer)
            }
        }
    }

    companion object {
        internal fun activeSignerKeyVersion(
            trustStore: AcceptedLocalTrustStateStore,
            moduleId: String,
        ): Long {
            val snapshot = trustStore.currentSnapshot() ?: return 0L
            return snapshot.bindingsByKey.values
                .asSequence()
                .filter { binding ->
                    binding.moduleId == moduleId && binding.keyState == GenerationFactKeyState.ACTIVE
                }
                .maxOfOrNull { it.signerKeyVersion }
                ?: 0L
        }
    }
}
