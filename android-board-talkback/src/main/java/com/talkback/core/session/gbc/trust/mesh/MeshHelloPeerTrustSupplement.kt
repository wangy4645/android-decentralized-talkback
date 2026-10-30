package com.talkback.core.session.gbc.trust.mesh

import com.talkback.core.session.gbc.issuance.GenerationFactSignerFingerprint
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.GenerationFactProfileTrustPayload
import com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionCanonicalCodec
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionIdentity
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentAlgorithm
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileBinding
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionCanonicalCodec
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileTrustPayload
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentRevisionIdentity
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding

/**
 * Late mesh join: extend local signing / establishment snapshots for any peer module
 * missing from Profile01 trust lookups (e.g. roster grew after FTPH profile batch).
 */
class MeshHelloPeerTrustSupplement(
    private val signingTrustStore: AcceptedLocalTrustStateStore,
    private val establishmentTrustStore: AcceptedLocalEstablishmentTrustStateStore,
    private val localModuleId: String,
    private val deploymentTrustDomainId: String,
    private val onLog: (String) -> Unit = {},
) {
    sealed class ApplyResult {
        data object NoOp : ApplyResult()

        data class Extended(
            val signingAdded: Boolean,
            val establishmentAdded: Boolean,
        ) : ApplyResult()

        data class Failed(
            val reason: String,
        ) : ApplyResult()
    }

    fun buildLocalHelloDigest(): MeshPeerTrustHelloDigest? {
        val signing = localActiveSigningBinding() ?: return null
        val establishment = localActiveEstablishmentBinding()
        return MeshPeerTrustHelloDigest(
            signingPublicKeySpki = signing.publicKeySpki.copyOf(),
            signingKeyVersion = signing.signerKeyVersion,
            establishmentPublicKeySpki = establishment?.establishmentPublicKeySpki?.copyOf(),
            establishmentKeyVersion = establishment?.establishmentKeyVersion ?: 0L,
        )
    }

    fun applyFromHello(
        peerModuleId: String,
        digest: MeshPeerTrustHelloDigest?,
    ): ApplyResult =
        runCatching {
            if (peerModuleId.isBlank() || peerModuleId == localModuleId) return ApplyResult.NoOp
            var signingAdded = false
            var establishmentAdded = false
            if (!peerHasSigningBinding(peerModuleId)) {
                signingAdded = extendSigning(peerModuleId, digest)
            }
            if (!peerHasEstablishmentBinding(peerModuleId)) {
                establishmentAdded = extendEstablishment(peerModuleId, digest)
            }
            if (signingAdded || establishmentAdded) {
                ApplyResult.Extended(signingAdded, establishmentAdded)
            } else {
                ApplyResult.NoOp
            }
        }.getOrElse { error ->
            onLog("MESH_TRUST_SUPPLEMENT failed peer=$peerModuleId reason=${error.message}")
            ApplyResult.Failed(error.message ?: error.javaClass.simpleName)
        }

    private fun extendSigning(
        peerModuleId: String,
        digest: MeshPeerTrustHelloDigest?,
    ): Boolean {
        val snapshot = signingTrustStore.currentSnapshot() ?: return false
        val spkiAndVersion =
            resolveSigningSpki(snapshot, digest)
                ?: return false
        val (spki, version) = spkiAndVersion
        val nextRevision = snapshot.taskProfileRevision + 1L
        val domain = snapshot.deploymentTrustDomainId ?: deploymentTrustDomainId
        val moduleBindings =
            snapshot.bindingsByKey.values.toMutableList() +
                ModuleSigningBinding(
                    moduleId = peerModuleId,
                    signerKeyVersion = version,
                    keyState = GenerationFactKeyState.ACTIVE,
                    publicKeySpki = spki.copyOf(),
                    activatedAtRevision = nextRevision,
                )
        val payload =
            GenerationFactProfileTrustPayload(
                taskProfileRevision = nextRevision,
                localModuleId = snapshot.localModuleId,
                moduleBindings = moduleBindings,
                deploymentTrustDomainId = domain,
            )
        val canonicalBytes = ProfileRevisionCanonicalCodec.encode(payload)
        val revisionIdentity = ProfileRevisionIdentity.fromProtectedBytes(canonicalBytes)
        val next =
            AcceptedLocalTrustState(
                taskProfileRevision = nextRevision,
                revisionIdentity = revisionIdentity.copyOf(),
                localModuleId = snapshot.localModuleId,
                bindingsByKey =
                    moduleBindings.associateBy {
                        AcceptedLocalTrustState.BindingKey(it.moduleId, it.signerKeyVersion)
                    },
                deploymentTrustDomainId = domain,
            )
        signingTrustStore.atomicReplace(next)
        onLog(
            "MESH_TRUST_SUPPLEMENT signing peer=$peerModuleId keyVersion=$version " +
                "revision=$nextRevision",
        )
        return true
    }

    private fun extendEstablishment(
        peerModuleId: String,
        digest: MeshPeerTrustHelloDigest?,
    ): Boolean {
        if (digest == null || !digest.hasEstablishmentMaterial()) return false
        val snapshot = establishmentTrustStore.currentSnapshot() ?: return false
        val spki = digest.establishmentPublicKeySpki ?: return false
        val version = digest.establishmentKeyVersion
        val nextRevision = snapshot.taskProfileRevision + 1L
        val domain = snapshot.deploymentTrustDomainId ?: deploymentTrustDomainId
        val establishmentBindings =
            snapshot.bindingsByKey.values.map { binding ->
                EstablishmentProfileBinding(
                    moduleId = binding.moduleId,
                    establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
                    establishmentKeyVersion = binding.establishmentKeyVersion,
                    keyState = binding.keyState,
                    establishmentPublicKeySpki = binding.establishmentPublicKeySpki.copyOf(),
                    activatedAtRevision = binding.activatedAtRevision,
                    retiredVerifyAtRevision = binding.retiredVerifyAtRevision,
                )
            } +
                EstablishmentProfileBinding(
                    moduleId = peerModuleId,
                    establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
                    establishmentKeyVersion = version,
                    keyState = GenerationFactKeyState.ACTIVE,
                    establishmentPublicKeySpki = spki.copyOf(),
                    activatedAtRevision = nextRevision,
                )
        val payload =
            EstablishmentProfileTrustPayload(
                taskProfileRevision = nextRevision,
                localModuleId = snapshot.localModuleId,
                establishmentBindings = establishmentBindings,
                deploymentTrustDomainId = domain,
            )
        val canonicalBytes = EstablishmentProfileRevisionCanonicalCodec.encode(payload)
        val revisionIdentity = EstablishmentRevisionIdentity.fromProtectedBytes(canonicalBytes)
        val next =
            AcceptedLocalEstablishmentTrustState(
                taskProfileRevision = nextRevision,
                revisionIdentity = revisionIdentity.copyOf(),
                localModuleId = snapshot.localModuleId,
                bindingsByKey =
                    establishmentBindings.map { binding ->
                        ModuleEstablishmentBinding(
                            moduleId = binding.moduleId,
                            establishmentKeyVersion = binding.establishmentKeyVersion,
                            keyState = binding.keyState,
                            establishmentPublicKeySpki = binding.establishmentPublicKeySpki.copyOf(),
                            activatedAtRevision = binding.activatedAtRevision,
                            retiredVerifyAtRevision = binding.retiredVerifyAtRevision,
                        )
                    }.associateBy {
                        AcceptedLocalEstablishmentTrustState.BindingKey(
                            it.moduleId,
                            it.establishmentKeyVersion,
                        )
                    },
                deploymentTrustDomainId = domain,
            )
        establishmentTrustStore.atomicReplace(next)
        onLog(
            "MESH_TRUST_SUPPLEMENT establishment peer=$peerModuleId keyVersion=$version " +
                "revision=$nextRevision",
        )
        return true
    }

    private fun resolveSigningSpki(
        snapshot: AcceptedLocalTrustState,
        digest: MeshPeerTrustHelloDigest?,
    ): Pair<ByteArray, Long>? {
        if (digest != null && digest.hasSigningMaterial()) {
            val spki = digest.signingPublicKeySpki ?: return null
            return spki to digest.signingKeyVersion
        }
        if (!isHomogeneousSigningSnapshot(snapshot)) return null
        val template =
            snapshot.bindingsByKey.values.firstOrNull {
                it.keyState == GenerationFactKeyState.ACTIVE
            } ?: return null
        return template.publicKeySpki.copyOf() to template.signerKeyVersion
    }

    private fun isHomogeneousSigningSnapshot(snapshot: AcceptedLocalTrustState): Boolean {
        val active =
            snapshot.bindingsByKey.values.filter { it.keyState == GenerationFactKeyState.ACTIVE }
        if (active.isEmpty()) return false
        val fingerprints =
            active.map { GenerationFactSignerFingerprint.spkiSha256Hex(it.publicKeySpki) }.distinct()
        return fingerprints.size == 1
    }

    private fun peerHasSigningBinding(peerModuleId: String): Boolean {
        val snapshot = signingTrustStore.currentSnapshot() ?: return false
        return snapshot.bindingsByKey.values.any {
            it.moduleId == peerModuleId && it.keyState == GenerationFactKeyState.ACTIVE
        }
    }

    private fun peerHasEstablishmentBinding(peerModuleId: String): Boolean {
        val snapshot = establishmentTrustStore.currentSnapshot() ?: return false
        return snapshot.bindingsByKey.values.any {
            it.moduleId == peerModuleId && it.keyState == GenerationFactKeyState.ACTIVE
        }
    }

    private fun localActiveSigningBinding(): ModuleSigningBinding? {
        val snapshot = signingTrustStore.currentSnapshot() ?: return null
        return snapshot.bindingsByKey.values
            .filter { it.moduleId == localModuleId && it.keyState == GenerationFactKeyState.ACTIVE }
            .maxByOrNull { it.signerKeyVersion }
    }

    private fun localActiveEstablishmentBinding(): ModuleEstablishmentBinding? {
        val snapshot = establishmentTrustStore.currentSnapshot() ?: return null
        return snapshot.bindingsByKey.values
            .filter { it.moduleId == localModuleId && it.keyState == GenerationFactKeyState.ACTIVE }
            .maxByOrNull { it.establishmentKeyVersion }
    }
}
