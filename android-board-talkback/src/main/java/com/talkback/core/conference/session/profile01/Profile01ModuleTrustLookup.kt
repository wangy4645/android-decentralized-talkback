package com.talkback.core.conference.session.profile01

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding

sealed class Profile01ModuleTrustLookupResult {
    data class Found(val publicKeySpki: ByteArray) : Profile01ModuleTrustLookupResult()

    data object UnknownModule : Profile01ModuleTrustLookupResult()

    data object UnknownKeyVersion : Profile01ModuleTrustLookupResult()

    data object KeyNotVerifiable : Profile01ModuleTrustLookupResult()
}

fun interface Profile01ModuleTrustLookup {
    fun lookupSigningKey(
        moduleId: String,
        signerKeyVersion: Long,
    ): Profile01ModuleTrustLookupResult
}

/**
 * Reuses ADR-0057 accepted local trust snapshot for Profile 01 ECDSA verification.
 */
class Profile01ProfileBackedModuleTrustLookup(
    private val store: AcceptedLocalTrustStateStore,
) : Profile01ModuleTrustLookup {
    override fun lookupSigningKey(
        moduleId: String,
        signerKeyVersion: Long,
    ): Profile01ModuleTrustLookupResult {
        val snapshot = store.currentSnapshot() ?: return Profile01ModuleTrustLookupResult.UnknownModule
        return lookup(snapshot, moduleId, signerKeyVersion)
    }

    companion object {
        fun lookup(
            snapshot: AcceptedLocalTrustState,
            moduleId: String,
            signerKeyVersion: Long,
        ): Profile01ModuleTrustLookupResult {
            val binding =
                snapshot.bindingsByKey[AcceptedLocalTrustState.BindingKey(moduleId, signerKeyVersion)]
                    ?: return if (snapshot.bindingsByKey.keys.any { it.moduleId == moduleId }) {
                        Profile01ModuleTrustLookupResult.UnknownKeyVersion
                    } else {
                        Profile01ModuleTrustLookupResult.UnknownModule
                    }
            if (!isVerifiable(binding)) return Profile01ModuleTrustLookupResult.KeyNotVerifiable
            return Profile01ModuleTrustLookupResult.Found(binding.publicKeySpki.copyOf())
        }

        private fun isVerifiable(binding: ModuleSigningBinding): Boolean =
            when (binding.keyState) {
                GenerationFactKeyState.ACTIVE,
                GenerationFactKeyState.RETIRED_VERIFY,
                -> true
                GenerationFactKeyState.PREPARED,
                GenerationFactKeyState.RETIRED_OFF,
                GenerationFactKeyState.REVOKED,
                -> false
            }
    }
}
