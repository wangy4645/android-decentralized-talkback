package com.talkback.core.signaling.peer

import com.talkback.core.signaling.link.LinkQualificationState
import com.talkback.core.signaling.link.TransportCapabilitySnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0022 Q5: derived projection for PEER_EDGE_SIGNALING_READY(edge).
 *
 * Hard rails:
 * - MUST NOT call repair / rebind / generation++
 * - Stores facts only (lastObserved + generation), never a sticky ready bit
 */
class PeerEdgeSignalingReadiness(
    private val moduleStaleMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val localSnapshot: () -> TransportCapabilitySnapshot
) {
    private data class EdgeFact(
        val observedGeneration: Long,
        val lastPeerInboundObservedAtMs: Long
    )

    private val facts = ConcurrentHashMap<String, EdgeFact>()
    private val lostEmittedForStale = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var onPeerEdgeSignalingLost: ((PeerEdgeSignalingLost) -> Unit)? = null

    fun onPeerInboundObserved(fact: PeerInboundObserved) {
        val snapshot = localSnapshot()
        if (fact.receiveGeneration != snapshot.rebindGeneration) return
        facts[fact.remoteModuleId] = EdgeFact(
            observedGeneration = fact.receiveGeneration,
            lastPeerInboundObservedAtMs = fact.observedAtMs
        )
        lostEmittedForStale.remove(fact.remoteModuleId)
    }

    /**
     * Q3/C4: synchronous epoch wipe for all peer facts from priorGeneration.
     * Must run on the same call stack as generation++ before PRR/announce listeners.
     */
    fun invalidateGeneration(priorGeneration: Long) {
        val now = clock()
        val removed = mutableListOf<String>()
        facts.entries.removeIf { (peer, fact) ->
            if (fact.observedGeneration == priorGeneration) {
                removed.add(peer)
                true
            } else {
                false
            }
        }
        removed.forEach { peer ->
            lostEmittedForStale.remove(peer)
            onPeerEdgeSignalingLost?.invoke(
                PeerEdgeSignalingLost(
                    remoteModuleId = peer,
                    generation = priorGeneration,
                    reason = PeerEdgeSignalingNotReadyReason.GENERATION_INVALIDATED,
                    lostAtMs = now
                )
            )
        }
    }

    fun isReady(remoteModuleId: String): Boolean = snapshot(remoteModuleId).ready

    fun snapshot(remoteModuleId: String): PeerEdgeSignalingSnapshot {
        val local = localSnapshot()
        val now = clock()
        if (local.linkQualification != LinkQualificationState.BIDIRECTIONAL_READY) {
            return PeerEdgeSignalingSnapshot(
                remoteModuleId = remoteModuleId,
                ready = false,
                observedGeneration = facts[remoteModuleId]?.observedGeneration,
                lastPeerInboundObservedAtMs = facts[remoteModuleId]?.lastPeerInboundObservedAtMs,
                reason = PeerEdgeSignalingNotReadyReason.LOCAL_NOT_BIDIRECTIONAL
            )
        }
        val fact = facts[remoteModuleId]
            ?: return PeerEdgeSignalingSnapshot(
                remoteModuleId = remoteModuleId,
                ready = false,
                observedGeneration = null,
                lastPeerInboundObservedAtMs = null,
                reason = PeerEdgeSignalingNotReadyReason.NEVER_OBSERVED
            )
        if (fact.observedGeneration != local.rebindGeneration) {
            return PeerEdgeSignalingSnapshot(
                remoteModuleId = remoteModuleId,
                ready = false,
                observedGeneration = fact.observedGeneration,
                lastPeerInboundObservedAtMs = fact.lastPeerInboundObservedAtMs,
                reason = PeerEdgeSignalingNotReadyReason.GENERATION_MISMATCH
            )
        }
        if (now - fact.lastPeerInboundObservedAtMs > moduleStaleMs) {
            return PeerEdgeSignalingSnapshot(
                remoteModuleId = remoteModuleId,
                ready = false,
                observedGeneration = fact.observedGeneration,
                lastPeerInboundObservedAtMs = fact.lastPeerInboundObservedAtMs,
                reason = PeerEdgeSignalingNotReadyReason.FRESHNESS_EXPIRED
            )
        }
        return PeerEdgeSignalingSnapshot(
            remoteModuleId = remoteModuleId,
            ready = true,
            observedGeneration = fact.observedGeneration,
            lastPeerInboundObservedAtMs = fact.lastPeerInboundObservedAtMs,
            reason = null
        )
    }

    /** Soft-expire scan within current epoch (Q5). Does not advance generation. */
    fun evaluateFreshness() {
        val local = localSnapshot()
        if (local.linkQualification != LinkQualificationState.BIDIRECTIONAL_READY) return
        val now = clock()
        facts.keys.toList().forEach { peer ->
            val snap = snapshot(peer)
            if (snap.reason == PeerEdgeSignalingNotReadyReason.FRESHNESS_EXPIRED &&
                lostEmittedForStale.add(peer)
            ) {
                onPeerEdgeSignalingLost?.invoke(
                    PeerEdgeSignalingLost(
                        remoteModuleId = peer,
                        generation = local.rebindGeneration,
                        reason = PeerEdgeSignalingNotReadyReason.FRESHNESS_EXPIRED,
                        lostAtMs = now
                    )
                )
            }
        }
    }

    /** Network / transport loss: wipe all peer facts (no sticky ready across epochs). */
    fun clearAll() {
        facts.clear()
        lostEmittedForStale.clear()
    }
}

data class PeerEdgeSignalingSnapshot(
    val remoteModuleId: String,
    val ready: Boolean,
    val observedGeneration: Long?,
    val lastPeerInboundObservedAtMs: Long?,
    val reason: PeerEdgeSignalingNotReadyReason?
)