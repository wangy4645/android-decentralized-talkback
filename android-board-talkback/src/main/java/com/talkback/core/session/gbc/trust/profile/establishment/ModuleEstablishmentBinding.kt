package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.GenerationFactKeyState

/**
 * Task Profile key-establishment binding — logically separate from [com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding].
 *
 * SigningIdentity != EstablishmentIdentity even when underlying key material is temporarily shared in test harnesses.
 */
data class ModuleEstablishmentBinding(
    val moduleId: String,
    val establishmentKeyVersion: Long,
    val keyState: GenerationFactKeyState,
    val establishmentPublicKeySpki: ByteArray,
    val activatedAtRevision: Long,
    val retiredVerifyAtRevision: Long? = null,
)

data class AcceptedLocalEstablishmentTrustState(
    val taskProfileRevision: Long,
    val revisionIdentity: ByteArray,
    val localModuleId: String,
    val bindingsByKey: Map<BindingKey, ModuleEstablishmentBinding>,
    val deploymentTrustDomainId: String? = null,
) {
    data class BindingKey(
        val moduleId: String,
        val establishmentKeyVersion: Long,
    )
}

interface AcceptedEstablishmentTrustStatePersistence {
    fun save(state: AcceptedLocalEstablishmentTrustState)

    fun load(): AcceptedLocalEstablishmentTrustState?
}

/**
 * Accepted establishment trust snapshot (P1-A / EP production wiring).
 *
 * Production may start empty until PAP-authenticated establishment profile revisions are accepted.
 */
class AcceptedLocalEstablishmentTrustStateStore(
    private val persistence: AcceptedEstablishmentTrustStatePersistence? = null,
) {
    private val snapshotRef =
        java.util.concurrent.atomic.AtomicReference<AcceptedLocalEstablishmentTrustState?>(persistence?.load())

    fun currentSnapshot(): AcceptedLocalEstablishmentTrustState? = snapshotRef.get()

    fun atomicReplace(next: AcceptedLocalEstablishmentTrustState) {
        persistence?.save(next)
        snapshotRef.set(next)
    }

    fun reloadFromPersistence() {
        snapshotRef.set(persistence?.load())
    }

    fun clear() {
        snapshotRef.set(null)
    }
}
