package com.talkback.core.session.gbc.trust.group

import com.talkback.core.session.gbc.trust.mesh.MeshHelloPeerTrustSupplement
import com.talkback.core.session.gbc.trust.mesh.MeshPeerTrustHelloDigest
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreProvisionResult
import com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot
import java.util.Locale
import java.util.UUID

/**
 * Phase 0: HELLO-carried GKE establishment proof + signing supplement (no establishment TOFU).
 */
class GroupEstablishmentHelloTrustService(
    private val signingTrustStore: AcceptedLocalTrustStateStore,
    private val establishmentTrustStore: AcceptedLocalEstablishmentTrustStateStore,
    private val keystoreStore: LocalEstablishmentKeystoreIdentityStore,
    private val localModuleId: String,
    private val deploymentTrustDomainId: String,
    private val sharedSecret: String,
    private val localEstablishmentKeyVersion: Long = 1L,
    private val onLog: (String) -> Unit = {},
) {
    private val helloNonceHex: String = UUID.randomUUID().toString().replace("-", "")

    private val meshSigningSupplement =
        MeshHelloPeerTrustSupplement(
            signingTrustStore = signingTrustStore,
            establishmentTrustStore = establishmentTrustStore,
            localModuleId = localModuleId,
            deploymentTrustDomainId = deploymentTrustDomainId,
            allowUntrustedEstablishmentHelloExtension = false,
            onLog = onLog,
        )

    sealed class HelloApplyOutcome {
        data object Inactive : HelloApplyOutcome()

        data object NoGkeMaterial : HelloApplyOutcome()

        data class GkeApplied(val detail: String) : HelloApplyOutcome()

        data class GkeRejected(val reason: String) : HelloApplyOutcome()

        data object GkeAlreadyPresent : HelloApplyOutcome()

        data class SigningExtended(
            val supplement: MeshHelloPeerTrustSupplement.ApplyResult,
        ) : HelloApplyOutcome()
    }

    fun onRuntimeAttached() {
        if (sharedSecret.isBlank()) {
            onLog("GKE_HELLO inactive reason=blank_shared_secret")
            return
        }
        when (val boot = ensureLocalGreenfieldBootstrap()) {
            is BootstrapOutcome.Ready -> onLog("GKE_HELLO bootstrap=ready module=${boot.identity.moduleId}")
            is BootstrapOutcome.Failed -> onLog("GKE_HELLO bootstrap=failed reason=${boot.reason}")
            BootstrapOutcome.Inactive -> Unit
        }
    }

    fun buildLocalHelloDigest(): MeshPeerTrustHelloDigest? {
        if (sharedSecret.isBlank()) return meshSigningSupplement.buildLocalHelloDigest()
        val identity =
            when (val boot = ensureLocalGreenfieldBootstrap()) {
                is BootstrapOutcome.Ready -> boot.identity
                else -> return meshSigningSupplement.buildLocalHelloDigest()
            }
        val signingDigest = meshSigningSupplement.buildLocalHelloDigest()
        val spki = identity.publicKeySpki
        val macKey = GroupEstablishmentPsk.macKey(sharedSecret, deploymentTrustDomainId)
        val proof =
            GroupMemberEstablishmentProof.sign(
                macKey = macKey,
                moduleId = identity.moduleId,
                establishmentKeyVersion = identity.establishmentKeyVersion,
                establishmentPublicKeySpki = spki,
                nonceHex = helloNonceHex,
            )
        return MeshPeerTrustHelloDigest(
            signingPublicKeySpki = signingDigest?.signingPublicKeySpki?.copyOf(),
            signingKeyVersion = signingDigest?.signingKeyVersion ?: 0L,
            establishmentPublicKeySpki = spki.copyOf(),
            establishmentKeyVersion = identity.establishmentKeyVersion,
            gkeNonceHex = helloNonceHex,
            gkeProofHex = proof,
        )
    }

    fun applyFromHello(
        peerModuleId: String,
        digest: MeshPeerTrustHelloDigest?,
    ): HelloApplyOutcome {
        if (sharedSecret.isBlank()) return HelloApplyOutcome.Inactive
        if (digest == null || !digest.hasGkeEstablishmentProof()) {
            val supplement = meshSigningSupplement.applyFromHello(peerModuleId, digest)
            return HelloApplyOutcome.SigningExtended(supplement)
        }
        when (ensureLocalGreenfieldBootstrap()) {
            is BootstrapOutcome.Failed -> return HelloApplyOutcome.GkeRejected("local bootstrap failed")
            BootstrapOutcome.Inactive -> return HelloApplyOutcome.Inactive
            is BootstrapOutcome.Ready -> Unit
        }
        val spki = digest.establishmentPublicKeySpki ?: return HelloApplyOutcome.GkeRejected("spki missing")
        val advertisement =
            GroupMemberEstablishmentAdvertisement(
                moduleId = peerModuleId,
                establishmentKeyVersion = digest.establishmentKeyVersion,
                establishmentPublicKeySpki = spki.copyOf(),
                nonceHex = digest.gkeNonceHex.orEmpty(),
                proofHex = digest.gkeProofHex.orEmpty(),
            )
        val macKey = GroupEstablishmentPsk.macKey(sharedSecret, deploymentTrustDomainId)
        val gkeOutcome =
            GroupEstablishmentTrustMerger.applyVerifiedPeerAdvertisement(
                store = establishmentTrustStore,
                localModuleId = localModuleId,
                deploymentTrustDomainId = deploymentTrustDomainId,
                macKey = macKey,
                advertisement = advertisement,
            )
        val gkeDetail =
            when (gkeOutcome) {
                GroupEstablishmentTrustMerger.Outcome.Applied -> {
                    onLog("GKE_HELLO applied peer=$peerModuleId")
                    HelloApplyOutcome.GkeApplied("applied")
                }
                GroupEstablishmentTrustMerger.Outcome.AlreadyPresent -> HelloApplyOutcome.GkeAlreadyPresent
                is GroupEstablishmentTrustMerger.Outcome.Rejected -> {
                    onLog("GKE_HELLO rejected peer=$peerModuleId reason=${gkeOutcome.reason}")
                    HelloApplyOutcome.GkeRejected(gkeOutcome.reason)
                }
            }
        val supplement = meshSigningSupplement.applyFromHello(peerModuleId, digest)
        return if (supplement is MeshHelloPeerTrustSupplement.ApplyResult.Extended &&
            supplement.signingAdded
        ) {
            HelloApplyOutcome.SigningExtended(supplement)
        } else {
            gkeDetail
        }
    }

    private sealed class BootstrapOutcome {
        data object Inactive : BootstrapOutcome()

        data class Ready(
            val identity: Profile01LocalEstablishmentIdentitySnapshot,
        ) : BootstrapOutcome()

        data class Failed(
            val reason: String,
        ) : BootstrapOutcome()
    }

    private fun ensureLocalGreenfieldBootstrap(): BootstrapOutcome {
        if (sharedSecret.isBlank()) return BootstrapOutcome.Inactive
        val normalized = localModuleId.trim().uppercase(Locale.US)
        if (normalized.isBlank()) return BootstrapOutcome.Failed("moduleId required")
        val identity =
            when (
                val provision =
                    keystoreStore.provisionFirstIdentity(normalized, localEstablishmentKeyVersion)
            ) {
                is LocalEstablishmentKeystoreProvisionResult.Created -> provision.identity
                is LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned -> provision.identity
                is LocalEstablishmentKeystoreProvisionResult.Failed ->
                    return BootstrapOutcome.Failed(provision.reason)
            }
        if (establishmentTrustStore.currentSnapshot() == null) {
            GroupEstablishmentTrustMerger.bootstrapLocalSnapshot(
                establishmentTrustStore,
                identity,
                deploymentTrustDomainId,
            )
        }
        return BootstrapOutcome.Ready(identity)
    }
}
