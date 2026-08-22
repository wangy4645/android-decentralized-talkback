package com.talkback.core.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * P0.1d: per-edge SRD apply lifecycle.
 *
 * JNI that never returns cannot be awaited. Timeout is a media-edge fact only:
 * APPLYING → FAILED. Does not admit Ready / ICE CONNECTED / recovery.
 */
class ConferenceEdgeSdpApplyGate {
    enum class State { APPLYING, SETTLED, FAILED }

    private val states = ConcurrentHashMap<String, AtomicReference<State>>()

    fun begin(edgeKey: String) {
        states[edgeKey] = AtomicReference(State.APPLYING)
    }

    fun isApplying(edgeKey: String): Boolean =
        states[edgeKey]?.get() == State.APPLYING

    fun tryTimeout(edgeKey: String): Boolean =
        states[edgeKey]?.compareAndSet(State.APPLYING, State.FAILED) == true

    /** True if this completion still owns the edge (not already SRD_TIMEOUT). */
    fun trySettle(edgeKey: String): Boolean {
        val ref = states[edgeKey] ?: return false
        val won = ref.compareAndSet(State.APPLYING, State.SETTLED)
        if (won) states.remove(edgeKey)
        return won
    }

    fun clear(edgeKey: String) {
        states.remove(edgeKey)
    }

    companion object {
        /** Same budget as [com.talkback.core.webrtc.PendingSdpWait]; not a conference enlarge. */
        const val TIMEOUT_MS = 3_000L
    }
}
