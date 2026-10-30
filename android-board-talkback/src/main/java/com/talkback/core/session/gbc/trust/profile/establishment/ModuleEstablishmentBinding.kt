package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import java.util.concurrent.CopyOnWriteArrayList

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

fun interface AcceptedEstablishmentTrustStateListener {
    fun onSnapshotReplaced(
        previous: AcceptedLocalEstablishmentTrustState?,
        next: AcceptedLocalEstablishmentTrustState?,
        newlyMaterializedPeerModuleIds: Set<String>,
    )
}

object AcceptedEstablishmentTrustStateDiff {
    fun newlyMaterializedPeerModuleIds(
        previous: AcceptedLocalEstablishmentTrustState?,
        next: AcceptedLocalEstablishmentTrustState?,
    ): Set<String> {
        if (next == null) return emptySet()
        val prevKeys = usableBindingKeys(previous)
        val nextKeys = usableBindingKeys(next)
        return (nextKeys - prevKeys).map { it.moduleId }.toSet()
    }

    private fun usableBindingKeys(
        state: AcceptedLocalEstablishmentTrustState?,
    ): Set<AcceptedLocalEstablishmentTrustState.BindingKey> {
        if (state == null) return emptySet()
        return state.bindingsByKey
            .filter { (_, binding) -> binding.keyState == GenerationFactKeyState.ACTIVE }
            .keys
    }
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

    private val listeners = CopyOnWriteArrayList<AcceptedEstablishmentTrustStateListener>()

    /** Desk / test harness hook; production should prefer [addListener]. */
    @Volatile
    var onPeerEstablishmentModulesMaterialized: ((Set<String>) -> Unit)? = null

    fun currentSnapshot(): AcceptedLocalEstablishmentTrustState? = snapshotRef.get()

    fun addListener(listener: AcceptedEstablishmentTrustStateListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: AcceptedEstablishmentTrustStateListener) {
        listeners.remove(listener)
    }

    fun atomicReplace(next: AcceptedLocalEstablishmentTrustState) {
        val previous = snapshotRef.get()
        persistence?.save(next)
        snapshotRef.set(next)
        dispatchSnapshotReplaced(previous, next)
    }

    fun reloadFromPersistence() {
        val previous = snapshotRef.get()
        val loaded = persistence?.load()
        snapshotRef.set(loaded)
        dispatchSnapshotReplaced(previous, loaded)
    }

    fun clear() {
        val previous = snapshotRef.get()
        snapshotRef.set(null)
        dispatchSnapshotReplaced(previous, null)
    }

    private fun dispatchSnapshotReplaced(
        previous: AcceptedLocalEstablishmentTrustState?,
        next: AcceptedLocalEstablishmentTrustState?,
    ) {
        val materialized = AcceptedEstablishmentTrustStateDiff.newlyMaterializedPeerModuleIds(previous, next)
        onPeerEstablishmentModulesMaterialized?.invoke(materialized)
        listeners.forEach { listener ->
            try {
                listener.onSnapshotReplaced(previous, next, materialized)
            } catch (_: Throwable) {
                // Snapshot is committed; listener failures must not roll back or block peers.
            }
        }
    }
}
