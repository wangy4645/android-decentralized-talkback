package com.talkback.core.session

import java.util.concurrent.atomic.AtomicReference

/**
 * B2-1: process-wide native SRD realization lease ([IA-001] AUTH-1).
 *
 * Commit 1 — lease bookkeeping only. No WebRTC, timeout, or mutex integration.
 * Logical containment states (STUCK / QUARANTINED) are defined here for later commits;
 * native execution is never implied terminated on release.
 */
class ConferenceNativeExecutionDomain {

    enum class LeaseState {
        /** Holder may execute under domain gate. */
        ACTIVE,
        /** Holder native non-return observed; slot not reusable. */
        STUCK,
        /** Domain blocks new grants until policy clears. */
        QUARANTINED,
    }

    enum class ReleaseReason {
        NORMAL,
        EDGE_LOCAL_FAILURE,
        STUCK_CONTAINMENT,
    }

    sealed class RequestResult {
        data object Granted : RequestResult()
        data class Busy(val holderEdgeKey: String) : RequestResult()
        data object Quarantined : RequestResult()
    }

    data class HolderSnapshot(
        val edgeKey: String,
        val state: LeaseState,
    )

    private data class Slot(
        val edgeKey: String,
        val state: LeaseState,
    )

    private val slot = AtomicReference<Slot?>(null)

    fun requestLease(edgeKey: String): RequestResult {
        require(edgeKey.isNotBlank()) { "edgeKey required" }
        while (true) {
            val current = slot.get()
            when {
                current == null -> {
                    if (slot.compareAndSet(null, Slot(edgeKey, LeaseState.ACTIVE))) {
                        return RequestResult.Granted
                    }
                    continue
                }
                current.state == LeaseState.QUARANTINED -> return RequestResult.Quarantined
                current.edgeKey == edgeKey && current.state == LeaseState.ACTIVE ->
                    return RequestResult.Granted
                current.state == LeaseState.ACTIVE || current.state == LeaseState.STUCK ->
                    return RequestResult.Busy(current.edgeKey)
                else -> {
                    if (slot.compareAndSet(current, Slot(edgeKey, LeaseState.ACTIVE))) {
                        return RequestResult.Granted
                    }
                }
            }
        }
    }

    fun releaseLease(edgeKey: String, @Suppress("UNUSED_PARAMETER") reason: ReleaseReason = ReleaseReason.NORMAL): Boolean {
        val current = slot.get() ?: return false
        if (current.edgeKey != edgeKey) return false
        if (current.state != LeaseState.ACTIVE) return false
        return slot.compareAndSet(current, null)
    }

    fun currentHolder(): String? = slot.get()?.edgeKey

    fun currentSnapshot(): HolderSnapshot? =
        slot.get()?.let { HolderSnapshot(it.edgeKey, it.state) }

    /**
     * Logical containment only — does not stop in-flight native work (AUTH-4).
     * Commit 4 wires watchdog; API present from commit 1.
     */
    fun markStuck(edgeKey: String): Boolean {
        val current = slot.get() ?: return false
        if (current.edgeKey != edgeKey || current.state != LeaseState.ACTIVE) return false
        return slot.compareAndSet(current, current.copy(state = LeaseState.STUCK))
    }

    /**
     * Blocks new grants while native state may still be unknown (AUTH-4).
     */
    fun quarantine(edgeKey: String): Boolean {
        val current = slot.get() ?: return false
        if (current.edgeKey != edgeKey) return false
        if (current.state != LeaseState.STUCK && current.state != LeaseState.ACTIVE) return false
        return slot.compareAndSet(current, current.copy(state = LeaseState.QUARANTINED))
    }

    /** Test / policy hook — not used until containment policy is defined. */
    fun clearQuarantine(): Boolean {
        val current = slot.get() ?: return false
        if (current.state != LeaseState.QUARANTINED) return false
        return slot.compareAndSet(current, null)
    }
}
