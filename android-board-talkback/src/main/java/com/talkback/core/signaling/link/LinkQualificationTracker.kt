package com.talkback.core.signaling.link

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * R28-L.1: aggregates transport link facts into qualification state.
 * Owned by transport layer only; recovery reads snapshot later (R28-L.1.3).
 */
class LinkQualificationTracker(
    private val inboundTimeoutMs: Long = DEFAULT_INBOUND_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scheduler: ScheduledExecutorService? = SHARED_SCHEDULER
) : LinkQualificationFactSink, SignalingGenerationAuthority {

    @Volatile
    private var state: LinkQualificationState = LinkQualificationState.UNKNOWN

    @Volatile
    private var socketId: Long = 0L

    @Volatile
    private var rebindGeneration: Long = 0L

    @Volatile
    private var networkId: String = "none"

    @Volatile
    private var hasOutboundAfterRebind: Boolean = false

    @Volatile
    private var hasInboundAfterRebind: Boolean = false

    @Volatile
    private var qualificationRetryRequested: Boolean = false

    @Volatile
    private var timeoutFuture: ScheduledFuture<*>? = null

    @Volatile
    var onQualificationStateChanged: ((LinkQualificationState, LinkQualificationState) -> Unit)? = null

    @Volatile
    var onQualificationRepairHandoff: ((socketId: Long, rebindGeneration: Long) -> Unit)? = null

    /**
     * INV-SIG-001/004: fired synchronously inside [advanceRebindGeneration] before callers
     * continue to announce / onSocketBound / PRR. Used to invalidate peer-edge readiness.
     */
    @Volatile
    var onSignalingGenerationAdvanced: ((priorGeneration: Long, newGeneration: Long) -> Unit)? = null

    @Volatile
    private var repairObservationGeneration: Long? = null

    @Volatile
    private var repairOutboundObservedGeneration: Long? = null

    @Volatile
    private var repairInboundObservedGeneration: Long? = null

    fun markRepairObservationGeneration(rebindGeneration: Long) {
        repairObservationGeneration = rebindGeneration
        repairOutboundObservedGeneration = null
        repairInboundObservedGeneration = null
    }

    fun clearRepairObservationGeneration() {
        repairObservationGeneration = null
        repairOutboundObservedGeneration = null
        repairInboundObservedGeneration = null
    }

    fun snapshot(): TransportCapabilitySnapshot = TransportCapabilitySnapshot(
        linkQualification = state,
        socketId = socketId,
        rebindGeneration = rebindGeneration,
        networkId = networkId,
        hasOutboundAfterRebind = hasOutboundAfterRebind,
        hasInboundAfterRebind = hasInboundAfterRebind,
        qualificationRetryRequested = qualificationRetryRequested
    )

    override fun currentRebindGeneration(): Long = rebindGeneration

    /**
     * Sole writer for signaling generation (INV-SIG-001).
     * Synchronously notifies [onSignalingGenerationAdvanced] before returning (Q3/C4).
     */
    override fun advanceRebindGeneration(): Long {
        val prior = rebindGeneration
        val next = prior + 1L
        rebindGeneration = next
        onSignalingGenerationAdvanced?.invoke(prior, next)
        return next
    }

    fun enterQualificationRepairing() {
        if (state != LinkQualificationState.UNQUALIFIED &&
            state != LinkQualificationState.QUALIFICATION_REPAIRING
        ) {
            return
        }
        transitionTo(LinkQualificationState.QUALIFICATION_REPAIRING, LinkQualificationReasons.QUALIFICATION_REPAIR_STARTED)
    }

    fun enterUnqualifiedStable() {
        qualificationRetryRequested = false
        transitionTo(LinkQualificationState.UNQUALIFIED_STABLE, LinkQualificationReasons.REPAIR_EXHAUSTED)
    }

    fun resetFromStableRestart(reason: String) {
        if (state != LinkQualificationState.UNQUALIFIED_STABLE) return
        qualificationRetryRequested = false
        transitionTo(LinkQualificationState.UNQUALIFIED, reason)
    }

    /**
     * Facts for an epoch newer than the current one (producer thread raced ahead of [onSocketBound]).
     * Replayed when that epoch is bound; anything else is discarded. Field 2026-10-27: FIRST_OUTBOUND for
     * (socket 2, gen 2) arrived 11 ms before SOCKET_BOUND and was dropped, freezing the link in RECEIVE_READY.
     */
    private data class EarlyEpochFacts(
        val socketId: Long,
        val rebindGeneration: Long,
        var receiveLoopStarted: Boolean = false,
        var firstOutbound: Boolean = false,
        var firstInbound: Boolean = false
    )

    @Volatile
    private var earlyEpochFacts: EarlyEpochFacts? = null

    @Synchronized
    override fun onSocketBound(socketId: Long, rebindGeneration: Long, networkId: String) {
        recordFact(LinkQualificationFacts.SOCKET_BOUND, socketId, rebindGeneration, accepted = true)
        cancelInboundTimeout()
        this.socketId = socketId
        this.rebindGeneration = rebindGeneration
        this.networkId = networkId
        hasOutboundAfterRebind = false
        hasInboundAfterRebind = false
        qualificationRetryRequested = false
        transitionTo(LinkQualificationState.BOUND, LinkQualificationReasons.SOCKET_BOUND)
        replayEarlyEpochFacts()
    }

    @Synchronized
    override fun onReceiveLoopStarted(socketId: Long, rebindGeneration: Long) {
        val accepted = sameEpoch(socketId, rebindGeneration)
        recordFact(LinkQualificationFacts.RECEIVE_LOOP_STARTED, socketId, rebindGeneration, accepted)
        if (!accepted) {
            stashEarlyFact(socketId, rebindGeneration) { it.receiveLoopStarted = true }
            return
        }
        applyReceiveLoopStarted()
    }

    @Synchronized
    override fun onFirstOutboundAfterRebind(socketId: Long, rebindGeneration: Long) {
        val accepted = sameEpoch(socketId, rebindGeneration)
        recordFact(LinkQualificationFacts.FIRST_OUTBOUND_AFTER_REBIND, socketId, rebindGeneration, accepted)
        if (!accepted) {
            stashEarlyFact(socketId, rebindGeneration) { it.firstOutbound = true }
            return
        }
        applyFirstOutbound()
    }

    @Synchronized
    override fun onFirstInboundAfterRebind(socketId: Long, rebindGeneration: Long) {
        val accepted = sameEpoch(socketId, rebindGeneration)
        recordFact(LinkQualificationFacts.FIRST_INBOUND_AFTER_REBIND, socketId, rebindGeneration, accepted)
        if (!accepted) {
            stashEarlyFact(socketId, rebindGeneration) { it.firstInbound = true }
            return
        }
        applyFirstInbound()
    }

    private fun applyReceiveLoopStarted() {
        if (state == LinkQualificationState.BOUND || state == LinkQualificationState.QUALIFICATION_REPAIRING) {
            transitionTo(LinkQualificationState.RECEIVE_READY, LinkQualificationReasons.RECEIVE_LOOP_STARTED)
            // Packets may already have flowed on this epoch (replayed early facts or outbound before the
            // receive loop reported in); re-evaluate instead of waiting for a "first" that will not recur.
            maybePromoteBidirectional(LinkQualificationReasons.RECEIVE_LOOP_STARTED)
            scheduleInboundTimeoutIfNeeded()
        }
    }

    private fun applyFirstOutbound() {
        hasOutboundAfterRebind = true
        maybeEmitRepairPacketObservation(rebindGeneration, outbound = true)
        maybePromoteBidirectional(LinkQualificationReasons.FIRST_OUTBOUND_AFTER_REBIND)
        scheduleInboundTimeoutIfNeeded()
    }

    private fun applyFirstInbound() {
        hasInboundAfterRebind = true
        cancelInboundTimeout()
        maybeEmitRepairPacketObservation(rebindGeneration, outbound = false)
        maybePromoteBidirectional(LinkQualificationReasons.FIRST_INBOUND_AFTER_REBIND)
    }

    private fun stashEarlyFact(socketId: Long, rebindGeneration: Long, mark: (EarlyEpochFacts) -> Unit) {
        // Only epochs strictly newer than the bound one can legitimately arrive early.
        if (rebindGeneration <= this.rebindGeneration && socketId <= this.socketId) return
        val current = earlyEpochFacts
        val target =
            if (current != null && current.socketId == socketId && current.rebindGeneration == rebindGeneration) {
                current
            } else {
                EarlyEpochFacts(socketId, rebindGeneration).also { earlyEpochFacts = it }
            }
        mark(target)
        LinkQualificationTrace.linkFactReceived(
            fact = "EARLY_EPOCH_FACT_STASHED",
            socketId = socketId,
            rebindGeneration = rebindGeneration,
            networkId = networkId,
            accepted = true
        )
    }

    private fun replayEarlyEpochFacts() {
        val early = earlyEpochFacts ?: return
        earlyEpochFacts = null
        if (early.socketId != socketId || early.rebindGeneration != rebindGeneration) return
        LinkQualificationTrace.linkFactReceived(
            fact = "EARLY_EPOCH_FACT_REPLAYED",
            socketId = socketId,
            rebindGeneration = rebindGeneration,
            networkId = networkId,
            accepted = true
        )
        if (early.receiveLoopStarted) applyReceiveLoopStarted()
        if (early.firstOutbound) applyFirstOutbound()
        if (early.firstInbound) applyFirstInbound()
    }

    override fun onNetworkLost() {
        recordFact(LinkQualificationFacts.NETWORK_LOST, socketId, rebindGeneration, accepted = true)
        cancelInboundTimeout()
        socketId = 0L
        rebindGeneration = 0L
        networkId = "none"
        hasOutboundAfterRebind = false
        hasInboundAfterRebind = false
        qualificationRetryRequested = false
        clearRepairObservationGeneration()
        transitionTo(LinkQualificationState.UNKNOWN, LinkQualificationReasons.NETWORK_LOST)
    }

    override fun onQualificationTimeout() {
        recordFact(LinkQualificationFacts.QUALIFICATION_TIMEOUT, socketId, rebindGeneration, accepted = true)
        if (state == LinkQualificationState.UNQUALIFIED_STABLE) return
        if (state != LinkQualificationState.BOUND && state != LinkQualificationState.RECEIVE_READY) return
        if (!hasOutboundAfterRebind || hasInboundAfterRebind) return
        qualificationRetryRequested = true
        val timedOutSocketId = socketId
        val timedOutGeneration = rebindGeneration
        transitionTo(LinkQualificationState.UNQUALIFIED, LinkQualificationReasons.QUALIFICATION_TIMEOUT)
        onQualificationRepairHandoff?.invoke(timedOutSocketId, timedOutGeneration)
    }

    private fun maybeEmitRepairPacketObservation(rebindGeneration: Long, outbound: Boolean) {
        val repairGeneration = repairObservationGeneration ?: return
        if (repairGeneration != rebindGeneration) return
        if (outbound) {
            if (repairOutboundObservedGeneration == rebindGeneration) return
            repairOutboundObservedGeneration = rebindGeneration
            LinkQualificationTrace.linkFirstPacketAfterRepair(
                direction = "OUTBOUND",
                socketId = socketId,
                rebindGeneration = rebindGeneration,
                networkId = networkId
            )
        } else {
            if (repairInboundObservedGeneration == rebindGeneration) return
            repairInboundObservedGeneration = rebindGeneration
            LinkQualificationTrace.linkFirstPacketAfterRepair(
                direction = "INBOUND",
                socketId = socketId,
                rebindGeneration = rebindGeneration,
                networkId = networkId
            )
        }
    }

    private fun maybePromoteBidirectional(trigger: String) {
        if (state != LinkQualificationState.RECEIVE_READY) return
        if (!hasOutboundAfterRebind || !hasInboundAfterRebind) return
        transitionTo(LinkQualificationState.BIDIRECTIONAL_READY, trigger)
    }

    private fun scheduleInboundTimeoutIfNeeded() {
        if (scheduler == null || inboundTimeoutMs <= 0L) return
        if (state != LinkQualificationState.RECEIVE_READY) return
        if (!hasOutboundAfterRebind || hasInboundAfterRebind) return
        if (timeoutFuture?.isDone == false) return
        timeoutFuture = scheduler.schedule(
            { onQualificationTimeout() },
            inboundTimeoutMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun cancelInboundTimeout() {
        timeoutFuture?.cancel(false)
        timeoutFuture = null
    }

    private fun recordFact(fact: String, socketId: Long, rebindGeneration: Long, accepted: Boolean) {
        LinkQualificationTrace.linkFactReceived(
            fact = fact,
            socketId = socketId,
            rebindGeneration = rebindGeneration,
            networkId = networkId,
            accepted = accepted
        )
    }

    private fun sameEpoch(socketId: Long, rebindGeneration: Long): Boolean {
        return this.socketId == socketId && this.rebindGeneration == rebindGeneration
    }

    private fun transitionTo(newState: LinkQualificationState, reason: String) {
        val oldState = state
        if (oldState == newState) return
        state = newState
        if (newState == LinkQualificationState.BIDIRECTIONAL_READY ||
            newState == LinkQualificationState.UNQUALIFIED ||
            newState == LinkQualificationState.UNQUALIFIED_STABLE
        ) {
            cancelInboundTimeout()
            if (newState == LinkQualificationState.BIDIRECTIONAL_READY ||
                newState == LinkQualificationState.UNQUALIFIED_STABLE
            ) {
                clearRepairObservationGeneration()
            }
        }
        LinkQualificationTrace.linkQualificationStateChanged(
            oldState = oldState,
            newState = newState,
            reason = reason,
            socketId = socketId,
            rebindGeneration = rebindGeneration,
            networkId = networkId
        )
        onQualificationStateChanged?.invoke(oldState, newState)
    }

    companion object {
        const val DEFAULT_INBOUND_TIMEOUT_MS = 30_000L
        private val SHARED_SCHEDULER: ScheduledExecutorService? = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "link-qualification-timeout").apply { isDaemon = true }
        }
    }
}

object LinkQualificationFacts {
    const val SOCKET_BOUND = "SOCKET_BOUND"
    const val RECEIVE_LOOP_STARTED = "RECEIVE_LOOP_STARTED"
    const val FIRST_OUTBOUND_AFTER_REBIND = "FIRST_OUTBOUND_AFTER_REBIND"
    const val FIRST_INBOUND_AFTER_REBIND = "FIRST_INBOUND_AFTER_REBIND"
    const val NETWORK_LOST = "NETWORK_LOST"
    const val QUALIFICATION_TIMEOUT = "QUALIFICATION_TIMEOUT"
}

object LinkQualificationReasons {
    const val SOCKET_BOUND = "SOCKET_BOUND"
    const val RECEIVE_LOOP_STARTED = "RECEIVE_LOOP_STARTED"
    const val FIRST_OUTBOUND_AFTER_REBIND = "FIRST_OUTBOUND_AFTER_REBIND"
    const val FIRST_INBOUND_AFTER_REBIND = "FIRST_INBOUND_AFTER_REBIND"
    const val NETWORK_LOST = "NETWORK_LOST"
    const val NETWORK_CHANGED = "NETWORK_CHANGED"
    const val QUALIFICATION_TIMEOUT = "QUALIFICATION_TIMEOUT"
    const val QUALIFICATION_REPAIR_STARTED = "QUALIFICATION_REPAIR_STARTED"
    const val REPAIR_EXHAUSTED = "REPAIR_EXHAUSTED"
}
