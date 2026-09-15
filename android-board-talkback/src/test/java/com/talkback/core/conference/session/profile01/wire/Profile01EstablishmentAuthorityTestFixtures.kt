package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding

internal object Profile01EstablishmentAuthorityTestFixtures {
    const val LOCAL_MODULE_ID = "M01"
    const val PEER_MODULE_ID = "M02"
    const val SIGNER_KEY_VERSION = 1L
    const val ESTABLISHMENT_KEY_VERSION = 4L

    val establishmentPublicKeySpki: ByteArray =
        Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki()

    fun establishmentSnapshot(
        localModuleId: String = LOCAL_MODULE_ID,
        peerModuleId: String = PEER_MODULE_ID,
        establishmentKeyVersion: Long = ESTABLISHMENT_KEY_VERSION,
    ): AcceptedLocalEstablishmentTrustState {
        val peerBinding =
            ModuleEstablishmentBinding(
                moduleId = peerModuleId,
                establishmentKeyVersion = establishmentKeyVersion,
                keyState = GenerationFactKeyState.ACTIVE,
                establishmentPublicKeySpki = establishmentPublicKeySpki.copyOf(),
                activatedAtRevision = 10L,
            )
        val localBinding =
            ModuleEstablishmentBinding(
                moduleId = localModuleId,
                establishmentKeyVersion = establishmentKeyVersion,
                keyState = GenerationFactKeyState.ACTIVE,
                establishmentPublicKeySpki = establishmentPublicKeySpki.copyOf(),
                activatedAtRevision = 10L,
            )
        return AcceptedLocalEstablishmentTrustState(
            taskProfileRevision = 10L,
            revisionIdentity = ByteArray(32),
            localModuleId = localModuleId,
            bindingsByKey =
                listOf(peerBinding, localBinding).associateBy {
                    AcceptedLocalEstablishmentTrustState.BindingKey(
                        it.moduleId,
                        it.establishmentKeyVersion,
                    )
                },
        )
    }

    fun signingOnlySnapshot(
        localModuleId: String = LOCAL_MODULE_ID,
        peerModuleId: String = PEER_MODULE_ID,
        signerKeyVersion: Long = SIGNER_KEY_VERSION,
        signerSpki: ByteArray = byteArrayOf(0x01, 0x02, 0x03),
    ): AcceptedLocalTrustState {
        val binding =
            ModuleSigningBinding(
                moduleId = peerModuleId,
                signerKeyVersion = signerKeyVersion,
                keyState = GenerationFactKeyState.ACTIVE,
                publicKeySpki = signerSpki.copyOf(),
                activatedAtRevision = 10L,
            )
        return AcceptedLocalTrustState(
            taskProfileRevision = 10L,
            revisionIdentity = byteArrayOf(1, 2, 3) + ByteArray(29),
            localModuleId = localModuleId,
            bindingsByKey =
                mapOf(
                    AcceptedLocalTrustState.BindingKey(peerModuleId, signerKeyVersion) to binding,
                ),
        )
    }

    fun populateSigningStore(
        store: AcceptedLocalTrustStateStore,
        snapshot: AcceptedLocalTrustState = signingOnlySnapshot(),
    ) {
        store.atomicReplace(snapshot)
    }

    fun populateEstablishmentStore(
        store: AcceptedLocalEstablishmentTrustStateStore,
        snapshot: AcceptedLocalEstablishmentTrustState = establishmentSnapshot(),
    ) {
        store.atomicReplace(snapshot)
    }
}
