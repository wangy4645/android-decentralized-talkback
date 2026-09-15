package com.talkback.core.session.gbc.wiring

import com.talkback.core.session.gbc.FactDeliveryOrchestrator
import com.talkback.core.session.gbc.GroupBootstrapConvergenceWiring
import com.talkback.core.session.gbc.VerificationBoundary
import com.talkback.core.session.gbc.crypto.ProductionGenerationFactVerifier
import com.talkback.core.session.gbc.issuance.DurableGenerationFactIssuanceStore
import com.talkback.core.session.gbc.issuance.GbcFieldEstablishedSignerSupport
import com.talkback.core.session.gbc.issuance.FileSnapshotIssuancePersistence
import com.talkback.core.session.gbc.issuance.GenerationFactIssuanceKey
import com.talkback.core.session.gbc.issuance.GenerationFactIssuancePublishGate
import com.talkback.core.session.gbc.issuance.GenerationFactIssuanceWriter
import com.talkback.core.session.gbc.issuance.GenerationFactOriginEmissionSeam
import com.talkback.core.session.gbc.issuance.PersistedGenerationFactIssuanceSigner
import com.talkback.core.session.gbc.origin.ChannelGenesisGenerationIdentityAllocator
import com.talkback.core.session.gbc.origin.ColdStartOriginOrchestrator
import com.talkback.core.session.gbc.origin.FileChannelGenesisIdentityPersistence
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustState
import com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding
import java.nio.file.Path

/**
 * ADR-0057 CSO — PV-2 origin production composition (narrow cut).
 */
object GbcOriginComposition {
    data class GbcOriginRuntime(
        val issuanceKey: GenerationFactIssuanceKey,
        val issuanceStore: DurableGenerationFactIssuanceStore,
        val originEmission: GenerationFactOriginEmissionSeam,
        val genesisAllocator: ChannelGenesisGenerationIdentityAllocator,
        val trustBindingRevision: Long,
    ) {
        fun createOrchestrator(
            wiring: GroupBootstrapConvergenceWiring,
            factDeliveryOrchestrator: FactDeliveryOrchestrator,
            verificationBoundary: VerificationBoundary,
        ): ColdStartOriginOrchestrator =
            ColdStartOriginOrchestrator(
                wiring = wiring,
                factDeliveryOrchestrator = factDeliveryOrchestrator,
                verificationBoundary = verificationBoundary,
                originEmission = originEmission,
                issuanceStore = issuanceStore,
                issuanceKey = issuanceKey,
                genesisAllocator = genesisAllocator,
                trustBindingRevision = trustBindingRevision,
            )
    }

    fun createProductionOriginRuntime(
        trustDir: Path,
        localModuleId: String,
        trustSnapshot: AcceptedLocalTrustState?,
    ): GbcOriginRuntime? {
        val signer =
            GbcFieldEstablishedSignerSupport.loadSigner(trustDir.toFile())
                ?: return null
        val binding = resolveActiveBinding(trustSnapshot, localModuleId) ?: return null
        val issuanceKey =
            GenerationFactIssuanceKey(
                moduleId = binding.moduleId,
                signerKeyVersion = binding.signerKeyVersion,
            )
        val issuanceStore =
            DurableGenerationFactIssuanceStore(
                FileSnapshotIssuancePersistence(trustDir.resolve("issuance-snapshots")),
            )
        issuanceStore.reloadFromPersistence(issuanceKey)
        val writer = GenerationFactIssuanceWriter(issuanceStore, signer)
        val gate = GenerationFactIssuancePublishGate(issuanceStore)
        val originEmission = GenerationFactOriginEmissionSeam(writer, gate)
        val genesisAllocator =
            ChannelGenesisGenerationIdentityAllocator(
                FileChannelGenesisIdentityPersistence(trustDir.resolve("channel-genesis")),
            )
        return GbcOriginRuntime(
            issuanceKey = issuanceKey,
            issuanceStore = issuanceStore,
            originEmission = originEmission,
            genesisAllocator = genesisAllocator,
            trustBindingRevision = binding.activatedAtRevision,
        )
    }

    fun createTestOriginRuntime(
        localModuleId: String,
        signer: com.talkback.core.session.gbc.issuance.GenerationFactIssuanceSigner,
        signerKeyVersion: Long = 1L,
        trustBindingRevision: Long = 10L,
        persistenceRoot: Path? = null,
    ): GbcOriginRuntime {
        val issuanceKey = GenerationFactIssuanceKey(localModuleId, signerKeyVersion)
        val issuanceStore =
            DurableGenerationFactIssuanceStore(
                persistenceRoot?.let { FileSnapshotIssuancePersistence(it.resolve("issuance-snapshots")) },
            )
        if (persistenceRoot != null) {
            issuanceStore.reloadFromPersistence(issuanceKey)
        }
        val writer = GenerationFactIssuanceWriter(issuanceStore, signer)
        val gate = GenerationFactIssuancePublishGate(issuanceStore)
        val originEmission = GenerationFactOriginEmissionSeam(writer, gate)
        val genesisAllocator =
            ChannelGenesisGenerationIdentityAllocator(
                if (persistenceRoot != null) {
                    FileChannelGenesisIdentityPersistence(persistenceRoot.resolve("channel-genesis"))
                } else {
                    InMemoryChannelGenesisIdentityPersistence()
                },
            )
        return GbcOriginRuntime(
            issuanceKey = issuanceKey,
            issuanceStore = issuanceStore,
            originEmission = originEmission,
            genesisAllocator = genesisAllocator,
            trustBindingRevision = trustBindingRevision,
        )
    }

    private fun resolveActiveBinding(
        snapshot: AcceptedLocalTrustState?,
        localModuleId: String,
    ): ModuleSigningBinding? {
        snapshot ?: return null
        return snapshot.bindingsByKey.values.firstOrNull { binding ->
            binding.moduleId == localModuleId &&
                binding.keyState == GenerationFactKeyState.ACTIVE
        }
    }
}

/** Test-only in-memory genesis persistence. */
class InMemoryChannelGenesisIdentityPersistence :
    com.talkback.core.session.gbc.origin.ChannelGenesisIdentityPersistence {
    private val values = linkedMapOf<String, String>()

    override fun load(channelId: String): String? = values[channelId]

    override fun save(
        channelId: String,
        generationIdentity: String,
    ) {
        values.putIfAbsent(channelId, generationIdentity)
    }
}
