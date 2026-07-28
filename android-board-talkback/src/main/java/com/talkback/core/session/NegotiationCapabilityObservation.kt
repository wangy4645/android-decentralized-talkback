package com.talkback.core.session

import java.util.concurrent.ConcurrentHashMap

/**
 * B3 capability **observation ledger** (not a capability cache / truth).
 *
 * Truth remains [probeIceRestartGate] / [IceRestartGateProbe.executable] (INV-NEG-012).
 * This ledger only remembers the last **observed** executable value so rising-edge
 * detection (INV-NEG-011) can see unavailable → available.
 *
 * INV-NEG-015: deferred negotiation intents must establish a false baseline before waiting
 * for [WakeupSourceType.NEGOTIATION_CAN_EXECUTE].
 */
internal class NegotiationCapabilityObservation {
    private val lastObservedExecutableByEdge = ConcurrentHashMap<ConferenceEdgeKey, Boolean>()

    /**
     * Admission-defer baseline: this intent waits because capability is unavailable.
     * Must not be called from bare probe queries (probe is side-effect free).
     */
    fun establishDeferredBaseline(sessionId: String, remoteModuleId: String) {
        lastObservedExecutableByEdge[ConferenceEdgeKey(sessionId, remoteModuleId)] = false
    }

    data class RecomputeResult(
        val previous: Boolean?,
        /** True iff unavailable→available (`previous != true && executable`). */
        val risingEdge: Boolean
    )

    /**
     * Record a recompute observation for rising-edge detection (INV-NEG-011).
     */
    fun observeRecompute(
        sessionId: String,
        remoteModuleId: String,
        executable: Boolean
    ): RecomputeResult {
        val edge = ConferenceEdgeKey(sessionId, remoteModuleId)
        val previous = lastObservedExecutableByEdge.put(edge, executable)
        return RecomputeResult(
            previous = previous,
            risingEdge = executable && previous != true
        )
    }

    fun lastObserved(sessionId: String, remoteModuleId: String): Boolean? =
        lastObservedExecutableByEdge[ConferenceEdgeKey(sessionId, remoteModuleId)]

    fun clearEdge(sessionId: String, remoteModuleId: String) {
        lastObservedExecutableByEdge.remove(ConferenceEdgeKey(sessionId, remoteModuleId))
    }

    fun clearSession(sessionId: String) {
        val keys = lastObservedExecutableByEdge.keys.filter { it.sessionId == sessionId }
        keys.forEach { lastObservedExecutableByEdge.remove(it) }
    }

    fun clearAll() {
        lastObservedExecutableByEdge.clear()
    }
}
