package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding

/**
 * Reads only [AcceptedLocalEstablishmentTrustStateStore] — never signing bindings.
 */
class Profile01ProfileBackedRecipientEstablishmentKeyLookup(
    private val establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
) : Profile01RecipientEstablishmentKeyLookup {
    override fun lookupEstablishmentKey(
        moduleId: String,
        establishmentKeyVersion: Long,
    ): Profile01EstablishmentKeyLookupResult {
        val snapshot = establishmentStore.currentSnapshot() ?: return Profile01EstablishmentKeyLookupResult.UnknownModule
        return lookup(snapshot, moduleId, establishmentKeyVersion)
    }

    override fun activeEstablishmentKey(recipientModuleId: String): Profile01EstablishmentKeyLookupResult {
        val snapshot = establishmentStore.currentSnapshot() ?: return Profile01EstablishmentKeyLookupResult.UnknownModule
        val active =
            snapshot.bindingsByKey.values
                .asSequence()
                .filter { binding ->
                    binding.moduleId == recipientModuleId &&
                        binding.keyState == GenerationFactKeyState.ACTIVE
                }
                .maxByOrNull { it.establishmentKeyVersion }
                ?: return if (snapshot.bindingsByKey.keys.any { it.moduleId == recipientModuleId }) {
                    Profile01EstablishmentKeyLookupResult.KeyNotUsable
                } else {
                    Profile01EstablishmentKeyLookupResult.UnknownModule
                }
        return lookup(snapshot, recipientModuleId, active.establishmentKeyVersion)
    }

    companion object {
        fun lookup(
            snapshot: AcceptedLocalEstablishmentTrustState,
            moduleId: String,
            establishmentKeyVersion: Long,
        ): Profile01EstablishmentKeyLookupResult {
            val binding =
                snapshot.bindingsByKey[
                    AcceptedLocalEstablishmentTrustState.BindingKey(moduleId, establishmentKeyVersion),
                ]
                    ?: return if (snapshot.bindingsByKey.keys.any { it.moduleId == moduleId }) {
                        Profile01EstablishmentKeyLookupResult.UnknownKeyVersion
                    } else {
                        Profile01EstablishmentKeyLookupResult.UnknownModule
                    }
            if (!isUsable(binding)) return Profile01EstablishmentKeyLookupResult.KeyNotUsable
            return Profile01EstablishmentKeyLookupResult.Found(
                Profile01EstablishmentKeyRef(
                    recipientModuleId = binding.moduleId,
                    establishmentKeyVersion = binding.establishmentKeyVersion,
                    establishmentPublicKeySpki = binding.establishmentPublicKeySpki.copyOf(),
                ),
            )
        }

        private fun isUsable(binding: ModuleEstablishmentBinding): Boolean =
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
