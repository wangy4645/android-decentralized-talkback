package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot

/**
 * Local key-establishment identity helpers — independent from ECDSA signer key version.
 *
 * Store-backed version lookup (P1-A) and Keystore snapshot metadata (PR-EP-2) remain separate.
 */
object Profile01LocalEstablishmentIdentity {
    fun activeEstablishmentKeyVersion(
        establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
        localModuleId: String,
    ): Long {
        val snapshot = establishmentStore.currentSnapshot() ?: return 0L
        return snapshot.bindingsByKey.values
            .asSequence()
            .filter { binding ->
                binding.moduleId == localModuleId &&
                    binding.keyState == GenerationFactKeyState.ACTIVE
            }
            .maxOfOrNull { it.establishmentKeyVersion }
            ?: 0L
    }

    fun snapshotFromKeystore(
        identity: Profile01LocalEstablishmentIdentitySnapshot,
    ): Profile01LocalEstablishmentIdentitySnapshot = identity
}
