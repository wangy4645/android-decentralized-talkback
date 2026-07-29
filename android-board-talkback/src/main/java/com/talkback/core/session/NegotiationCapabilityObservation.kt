package com.talkback.core.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B3 capability **observation ledger** (not a capability cache / truth).
 *
 * Truth remains [probeIceRestartGate] / [IceRestartGateProbe.executable] (INV-NEG-012).
 * This ledger only remembers the last **observed** executable value so rising-edge
 * detection (INV-NEG-011) can see unavailable -> available.
 *
 * INV-NEG-015: deferred negotiation intents must establish a false baseline before waiting
 * for [WakeupSourceType.NEGOTIATION_CAN_EXECUTE].
 *
 * INV-NEG-019 / INV-NEG-020: each baseline / recompute advances a monotonic observation seq
 * so drain can reject pre-baseline (stale) capability events.
 */
internal class NegotiationCapabilityObservation {
    private val lastObservedExecutableByEdge = ConcurrentHashMap<ConferenceEdgeKey, Boolean>()
    private val observationSeqByEdge = ConcurrentHashMap<ConferenceEdgeKey, AtomicLong>()

    /**
     * Admission-defer baseline: this intent waits because capability is unavailable.
     * Must not be called from bare probe queries (probe is side-effect free).
     * @return observation seq stamped at DEFER_ADMISSION (intent consume floor).
     */
    fun establishDeferredBaseline(sessionId: String, remoteModuleId: String): Long {
        val edge = ConferenceEdgeKey(sessionId, remoteModuleId)
        lastObservedExecutableByEdge[edge] = false
        return nextSeq(edge)
    }

    data class RecomputeResult(
        val previous: Boolean?,
        /** True iff unavailable->available (`previous != true && executable`). */
        val risingEdge: Boolean,
        /** Monotonic observation seq for this recompute (INV-NEG-019 freshness). */
        val observationSeq: Long
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
        val observationSeq = nextSeq(edge)
        return RecomputeResult(
            previous = previous,
            risingEdge = executable && previous != true,
            observationSeq = observationSeq
        )
    }

    fun lastObserved(sessionId: String, remoteModuleId: String): Boolean? =
        lastObservedExecutableByEdge[ConferenceEdgeKey(sessionId, remoteModuleId)]

    fun clearEdge(sessionId: String, remoteModuleId: String) {
        val edge = ConferenceEdgeKey(sessionId, remoteModuleId)
        lastObservedExecutableByEdge.remove(edge)
        observationSeqByEdge.remove(edge)
    }

    fun clearSession(sessionId: String) {
        val keys = lastObservedExecutableByEdge.keys.filter { it.sessionId == sessionId }
        keys.forEach { clearEdge(it.sessionId, it.remoteModuleId) }
    }

    fun clearAll() {
        lastObservedExecutableByEdge.clear()
        observationSeqByEdge.clear()
    }

    private fun nextSeq(edge: ConferenceEdgeKey): Long =
        observationSeqByEdge
            .computeIfAbsent(edge) { AtomicLong(0L) }
            .incrementAndGet()
}