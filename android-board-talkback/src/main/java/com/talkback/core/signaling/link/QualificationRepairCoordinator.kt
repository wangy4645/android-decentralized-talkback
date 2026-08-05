package com.talkback.core.signaling.link

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * R28-L.1.4 + L.1.5: idempotent qualification repair with cap/backoff.
 * L.1.5: [QUALIFICATION_WAIT] + [QualificationFailureReason.QUALIFICATION_TIMEOUT] admits the next
 * attempt within cap (genuine requal failure); REQUESTED/IN_PROGRESS same-gen remains reject (jitter).
 * Owned by [com.talkback.core.signaling.SignalingTransportManager]; does not mutate recovery lineage.
 */
class QualificationRepairCoordinator(
    private val repairCap: Int = DEFAULT_REPAIR_CAP,
    private val repairBackoffMs: LongArray = DEFAULT_REPAIR_BACKOFF_MS,
    private val scheduler: ScheduledExecutorService? = SHARED_SCHEDULER
) : TransportRepairRequester {

    @Volatile
    private var repairState: TransportRepairState = TransportRepairState.IDLE

    @Volatile
    private var repairAttempt: Int = 0

    @Volatile
    private var activeGeneration: Long = -1L

    @Volatile
    private var activeSocketId: Long = -1L

    @Volatile
    private var scheduledFuture: ScheduledFuture<*>? = null

    var rebindSignaling: ((networkId: String, reason: String) -> Unit)? = null

    var tracker: LinkQualificationTracker? = null

    var currentNetworkId: () -> String = { "none" }

    /**
     * L.1.5 rollback: when false, QUALIFICATION_WAIT + timeout rejects as L.1.4 duplicate.
     * Default true after AUTHORIZE L.1.5 RETRY POLICY.
     */
    var admitNextAttemptWhileQualificationWait: Boolean = Companion.admitNextAttemptWhileQualificationWait

    override fun requestQualificationRepair(reason: QualificationFailureReason) {
        val snapshot = tracker?.snapshot() ?: return
        handleRepairRequest(reason, snapshot.socketId, snapshot.rebindGeneration)
    }

    internal fun onQualificationTimeout(socketId: Long, rebindGeneration: Long) {
        handleRepairRequest(QualificationFailureReason.QUALIFICATION_TIMEOUT, socketId, rebindGeneration)
    }

    internal fun onQualificationStateChanged(oldState: LinkQualificationState, newState: LinkQualificationState) {
        if (newState == LinkQualificationState.BIDIRECTIONAL_READY &&
            (repairState == TransportRepairState.QUALIFICATION_WAIT || repairAttempt > 0)
        ) {
            val snapshot = tracker?.snapshot() ?: return
            LinkQualificationTrace.linkQualificationRepairSucceeded(
                reason = QualificationFailureReason.QUALIFICATION_TIMEOUT,
                oldSocketId = activeSocketId,
                newSocketId = snapshot.socketId,
                qualificationGeneration = snapshot.rebindGeneration,
                repairAttempt = repairAttempt
            )
            resetRepairCycle()
        }
    }

    internal fun onNetworkAvailable(networkId: String) {
        if (repairState == TransportRepairState.REPAIR_EXHAUSTED ||
            tracker?.snapshot()?.linkQualification == LinkQualificationState.UNQUALIFIED_STABLE
        ) {
            resetRepairCycle()
            tracker?.resetFromStableRestart(LinkQualificationReasons.NETWORK_CHANGED)
        } else {
            cancelScheduledRepair()
            repairState = TransportRepairState.IDLE
        }
    }

    internal fun enrichSnapshot(base: TransportCapabilitySnapshot): TransportCapabilitySnapshot =
        base.copy(
            repairAttempt = repairAttempt,
            transportRepairState = repairState,
            repairStable = base.linkQualification == LinkQualificationState.UNQUALIFIED_STABLE
        )

    internal fun repairStateForTest(): TransportRepairState = repairState

    internal fun repairAttemptForTest(): Int = repairAttempt

    private fun handleRepairRequest(
        reason: QualificationFailureReason,
        socketId: Long,
        rebindGeneration: Long
    ) {
        if (reason == QualificationFailureReason.NETWORK_CHANGED ||
            reason == QualificationFailureReason.MANUAL_RECONNECT
        ) {
            resetRepairCycle()
            return
        }
        if (repairState == TransportRepairState.REPAIR_EXHAUSTED) {
            LinkQualificationTrace.linkQualificationRepairDuplicateRejected(
                reason = reason,
                socketId = socketId,
                qualificationGeneration = rebindGeneration,
                repairAttempt = repairAttempt,
                repairState = repairState
            )
            return
        }
        if (isDuplicateInFlight(rebindGeneration, reason)) {
            LinkQualificationTrace.linkQualificationRepairDuplicateRejected(
                reason = reason,
                socketId = socketId,
                qualificationGeneration = rebindGeneration,
                repairAttempt = repairAttempt,
                repairState = repairState
            )
            return
        }
        if (repairAttempt >= repairCap) {
            enterExhausted(socketId, rebindGeneration, reason)
            return
        }
        val nextAttempt = repairAttempt + 1
        val backoffMs = repairBackoffMs.getOrElse(repairAttempt) { repairBackoffMs.last() }
        activeSocketId = socketId
        activeGeneration = rebindGeneration
        repairState = TransportRepairState.REPAIR_REQUESTED
        LinkQualificationTrace.linkQualificationRepairRequested(
            reason = reason,
            oldSocketId = socketId,
            newSocketId = socketId,
            qualificationGeneration = rebindGeneration,
            repairAttempt = nextAttempt
        )
        scheduleRepair(backoffMs, socketId, rebindGeneration, reason, nextAttempt)
    }

    /**
     * L.1.4: same-gen while REQUESTED/IN_PROGRESS → duplicate reject (jitter).
     * L.1.5: QUALIFICATION_WAIT + QUALIFICATION_TIMEOUT → not duplicate (admit next attempt).
     */
    private fun isDuplicateInFlight(generation: Long, reason: QualificationFailureReason): Boolean {
        if (generation != activeGeneration) return false
        return when (repairState) {
            TransportRepairState.REPAIR_REQUESTED,
            TransportRepairState.REPAIR_IN_PROGRESS -> true
            TransportRepairState.QUALIFICATION_WAIT -> {
                if (admitNextAttemptWhileQualificationWait &&
                    reason == QualificationFailureReason.QUALIFICATION_TIMEOUT
                ) {
                    false
                } else {
                    true
                }
            }
            else -> false
        }
    }

    private fun scheduleRepair(
        delayMs: Long,
        socketId: Long,
        generation: Long,
        reason: QualificationFailureReason,
        attempt: Int
    ) {
        cancelScheduledRepair()
        val exec = scheduler
        if (exec == null) {
            executeRepair(socketId, generation, reason, attempt)
            return
        }
        scheduledFuture = exec.schedule(
            { executeRepair(socketId, generation, reason, attempt) },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun executeRepair(
        oldSocketId: Long,
        generation: Long,
        reason: QualificationFailureReason,
        attempt: Int
    ) {
        if (repairState != TransportRepairState.REPAIR_REQUESTED) return
        repairState = TransportRepairState.REPAIR_IN_PROGRESS
        repairAttempt = attempt
        val beforeSnapshot = tracker?.snapshot()
        LinkQualificationTrace.linkRepairSocketContext(
            phase = "BEFORE_REBIND",
            repairReason = reason,
            repairAttempt = attempt,
            beforeSocketId = beforeSnapshot?.socketId ?: oldSocketId,
            afterSocketId = beforeSnapshot?.socketId ?: oldSocketId,
            rebindGeneration = beforeSnapshot?.rebindGeneration ?: generation,
            networkId = beforeSnapshot?.networkId ?: currentNetworkId()
        )
        tracker?.enterQualificationRepairing()
        rebindSignaling?.invoke(currentNetworkId(), "qualification_repair")
        val afterSnapshot = tracker?.snapshot()
        val newSocketId = afterSnapshot?.socketId ?: oldSocketId
        val newGeneration = afterSnapshot?.rebindGeneration ?: generation
        tracker?.markRepairObservationGeneration(newGeneration)
        LinkQualificationTrace.linkRepairSocketContext(
            phase = "AFTER_REBIND",
            repairReason = reason,
            repairAttempt = attempt,
            beforeSocketId = oldSocketId,
            afterSocketId = newSocketId,
            rebindGeneration = newGeneration,
            networkId = afterSnapshot?.networkId ?: currentNetworkId()
        )
        LinkQualificationTrace.linkQualificationRepairStarted(
            reason = reason,
            oldSocketId = oldSocketId,
            newSocketId = newSocketId,
            qualificationGeneration = newGeneration,
            repairAttempt = attempt
        )
        repairState = TransportRepairState.QUALIFICATION_WAIT
        activeSocketId = newSocketId
        activeGeneration = newGeneration
    }

    private fun enterExhausted(socketId: Long, generation: Long, reason: QualificationFailureReason) {
        cancelScheduledRepair()
        repairState = TransportRepairState.REPAIR_EXHAUSTED
        tracker?.enterUnqualifiedStable()
        LinkQualificationTrace.linkQualificationRepairExhausted(
            reason = reason,
            oldSocketId = socketId,
            newSocketId = socketId,
            qualificationGeneration = generation,
            repairAttempt = repairAttempt
        )
    }

    private fun resetRepairCycle() {
        cancelScheduledRepair()
        repairState = TransportRepairState.IDLE
        repairAttempt = 0
        activeGeneration = -1L
        activeSocketId = -1L
        tracker?.clearRepairObservationGeneration()
    }

    private fun cancelScheduledRepair() {
        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }

    companion object {
        const val DEFAULT_REPAIR_CAP = 3
        val DEFAULT_REPAIR_BACKOFF_MS = longArrayOf(1_000L, 5_000L, 15_000L)

        /**
         * Process-wide L.1.5 default; instance may override via [admitNextAttemptWhileQualificationWait].
         * Set false to roll back to L.1.4 WAIT duplicate-reject.
         */
        @Volatile
        var admitNextAttemptWhileQualificationWait: Boolean = true

        private val SHARED_SCHEDULER: ScheduledExecutorService? = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "link-qualification-repair").apply { isDaemon = true }
        }
    }
}
