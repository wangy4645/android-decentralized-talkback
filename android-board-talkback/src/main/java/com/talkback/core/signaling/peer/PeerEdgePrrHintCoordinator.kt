package com.talkback.core.signaling.peer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * ADR-0022 Q6: peer-scoped PRR hint after PeerEdgeSignalingLost, with per-peer debounce.
 * MUST NOT advance global signaling generation / call qualification repair.
 */
class PeerEdgePrrHintCoordinator(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scheduler: ScheduledExecutorService? = null,
    private val isStillNotReady: (remoteModuleId: String) -> Boolean,
    private val announcePeer: (remoteModuleId: String) -> Unit
) {
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val lastHintAtMs = ConcurrentHashMap<String, Long>()

    fun onPeerEdgeSignalingLost(event: PeerEdgeSignalingLost) {
        if (event.reason != PeerEdgeSignalingNotReadyReason.FRESHNESS_EXPIRED &&
            event.reason != PeerEdgeSignalingNotReadyReason.GENERATION_MISMATCH
        ) {
            // Generation invalidate is expected; inbound must re-prove. Do not fan-out global PRR here.
            if (event.reason == PeerEdgeSignalingNotReadyReason.GENERATION_INVALIDATED) return
        }
        if (event.reason != PeerEdgeSignalingNotReadyReason.FRESHNESS_EXPIRED) return
        scheduleHint(event.remoteModuleId)
    }

    private fun scheduleHint(remoteModuleId: String) {
        val existing = pending.remove(remoteModuleId)
        existing?.cancel(false)
        val sched = scheduler
        if (sched == null || debounceMs <= 0L) {
            fireIfStillNotReady(remoteModuleId)
            return
        }
        pending[remoteModuleId] = sched.schedule(
            {
                pending.remove(remoteModuleId)
                fireIfStillNotReady(remoteModuleId)
            },
            debounceMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun fireIfStillNotReady(remoteModuleId: String) {
        if (!isStillNotReady(remoteModuleId)) return
        lastHintAtMs[remoteModuleId] = clock()
        announcePeer(remoteModuleId)
    }

    fun cancel(remoteModuleId: String) {
        pending.remove(remoteModuleId)?.cancel(false)
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 1_000L
    }
}