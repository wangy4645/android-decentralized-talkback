package com.talkback.core.session.gbc.trust.group

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentKeyLookupResult
import com.talkback.core.conference.session.profile01.wire.Profile01ProfileBackedRecipientEstablishmentKeyLookup
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentAlgorithm
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileBinding
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionCanonicalCodec
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileTrustPayload
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentRevisionIdentity
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding
import com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot

/**
 * Phase 0: merge GKE-verified peer establishment binding into the same store Profile01 uses.
 * Does not replace PAP; greenfield requires [bootstrapLocalSnapshot] first.
 */
object GroupEstablishmentTrustMerger {
    sealed class Outcome {
        data object Applied : Outcome()

        data object AlreadyPresent : Outcome()

        data class Rejected(
            val reason: String,
        ) : Outcome()
    }

    fun bootstrapLocalSnapshot(
        store: AcceptedLocalEstablishmentTrustStateStore,
        localIdentity: Profile01LocalEstablishmentIdentitySnapshot,
        deploymentTrustDomainId: String,
    ) {
        if (store.currentSnapshot() != null) return
        val binding =
            moduleBinding(
                moduleId = localIdentity.moduleId,
                establishmentKeyVersion = localIdentity.establishmentKeyVersion,
                spki = localIdentity.publicKeySpki,
                activatedAtRevision = 1L,
            )
        val payload =
            EstablishmentProfileTrustPayload(
                taskProfileRevision = 1L,
                localModuleId = localIdentity.moduleId,
                establishmentBindings =
                    listOf(
                        establishmentProfileBinding(binding, activatedAtRevision = 1L),
                    ),
                deploymentTrustDomainId = deploymentTrustDomainId,
            )
        store.atomicReplace(snapshotFromPayload(payload, revision = 1L))
    }

    fun applyVerifiedPeerAdvertisement(
        store: AcceptedLocalEstablishmentTrustStateStore,
        localModuleId: String,
        deploymentTrustDomainId: String,
        macKey: ByteArray,
        advertisement: GroupMemberEstablishmentAdvertisement,
    ): Outcome {
        val peerModuleId = advertisement.moduleId.trim().uppercase()
        if (peerModuleId.isBlank()) return Outcome.Rejected("moduleId required")
        if (peerModuleId == localModuleId.trim().uppercase()) return Outcome.Rejected("self peer")
        if (!GroupMemberEstablishmentProof.verify(macKey, advertisement)) {
            return Outcome.Rejected("proof invalid")
        }
        if (!com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentSpkiValidator
                .isSupportedRsa3072(advertisement.establishmentPublicKeySpki)
        ) {
            return Outcome.Rejected("unsupported spki")
        }
        val snapshot = store.currentSnapshot() ?: return Outcome.Rejected("local snapshot missing")
        if (peerHasActiveBinding(snapshot, peerModuleId)) {
            return Outcome.AlreadyPresent
        }
        val nextRevision = snapshot.taskProfileRevision + 1L
        val peerBinding =
            moduleBinding(
                moduleId = peerModuleId,
                establishmentKeyVersion = advertisement.establishmentKeyVersion,
                spki = advertisement.establishmentPublicKeySpki,
                activatedAtRevision = nextRevision,
            )
        val bindings =
            snapshot.bindingsByKey.values.map { existing ->
                establishmentProfileBinding(existing, existing.activatedAtRevision)
            } + establishmentProfileBinding(peerBinding, nextRevision)
        val payload =
            EstablishmentProfileTrustPayload(
                taskProfileRevision = nextRevision,
                localModuleId = snapshot.localModuleId,
                establishmentBindings = bindings,
                deploymentTrustDomainId = deploymentTrustDomainId,
            )
        store.atomicReplace(snapshotFromPayload(payload, nextRevision))
        return Outcome.Applied
    }

    fun lookupPeer(
        store: AcceptedLocalEstablishmentTrustStateStore,
        peerModuleId: String,
    ): Profile01EstablishmentKeyLookupResult =
        Profile01ProfileBackedRecipientEstablishmentKeyLookup(store)
            .activeEstablishmentKey(peerModuleId.trim().uppercase())

    private fun peerHasActiveBinding(
        snapshot: AcceptedLocalEstablishmentTrustState,
        peerModuleId: String,
    ): Boolean =
        snapshot.bindingsByKey.values.any {
            it.moduleId == peerModuleId && it.keyState == GenerationFactKeyState.ACTIVE
        }

    private fun moduleBinding(
        moduleId: String,
        establishmentKeyVersion: Long,
        spki: ByteArray,
        activatedAtRevision: Long,
    ): ModuleEstablishmentBinding =
        ModuleEstablishmentBinding(
            moduleId = moduleId,
            establishmentKeyVersion = establishmentKeyVersion,
            keyState = GenerationFactKeyState.ACTIVE,
            establishmentPublicKeySpki = spki.copyOf(),
            activatedAtRevision = activatedAtRevision,
        )

    private fun establishmentProfileBinding(
        binding: ModuleEstablishmentBinding,
        activatedAtRevision: Long,
    ): EstablishmentProfileBinding =
        EstablishmentProfileBinding(
            moduleId = binding.moduleId,
            establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
            establishmentKeyVersion = binding.establishmentKeyVersion,
            keyState = binding.keyState,
            establishmentPublicKeySpki = binding.establishmentPublicKeySpki.copyOf(),
            activatedAtRevision = activatedAtRevision,
            retiredVerifyAtRevision = binding.retiredVerifyAtRevision,
        )

    private fun snapshotFromPayload(
        payload: EstablishmentProfileTrustPayload,
        revision: Long,
    ): AcceptedLocalEstablishmentTrustState {
        val canonicalBytes = EstablishmentProfileRevisionCanonicalCodec.encode(payload)
        val revisionIdentity = EstablishmentRevisionIdentity.fromProtectedBytes(canonicalBytes)
        return AcceptedLocalEstablishmentTrustState(
            taskProfileRevision = revision,
            revisionIdentity = revisionIdentity.copyOf(),
            localModuleId = payload.localModuleId,
            bindingsByKey =
                payload.establishmentBindings
                    .map { b ->
                        ModuleEstablishmentBinding(
                            moduleId = b.moduleId,
                            establishmentKeyVersion = b.establishmentKeyVersion,
                            keyState = b.keyState,
                            establishmentPublicKeySpki = b.establishmentPublicKeySpki.copyOf(),
                            activatedAtRevision = b.activatedAtRevision,
                            retiredVerifyAtRevision = b.retiredVerifyAtRevision,
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
