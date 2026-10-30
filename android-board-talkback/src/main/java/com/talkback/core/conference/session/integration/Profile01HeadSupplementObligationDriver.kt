package com.talkback.core.conference.session.integration

import kotlin.random.Random

class Profile01HeadSupplementObligationDriver(
    private val ledger: Profile01HeadSupplementObligationLedger,
    private val clockMs: () -> Long,
    private val schedule: (delayMs: Long, run: () -> Unit) -> Unit,
    private val deliver: (
        id: Profile01HeadObligationId,
        snapshot: Profile01AuthoritativeHeadSnapshot,
    ) -> DeliverOutcome,
    private val liveSnapshot: (sessionId: String) -> Profile01AuthoritativeHeadSnapshot?,
    private val onLog: (String) -> Unit = {},
    private val random: Random = Random.Default,
    private val faultAfterMs: Long = FAULT_AFTER_MS,
) {
    data class DeliverOutcome(
        val sendApiResult: Boolean,
        val failure: String?,
        val liveHeadDigest: String?,
    )

    /**
     * Keep an un-ACK'd current-head obligation on its existing retry cadence.
     * Opens one if missing. Does not extra-deliver when OPEN/DELIVERING/FAULT
     * already covers [snapshot.headDigest].
     */
    fun ensureUnackedCurrentHeadKicking(
        snapshot: Profile01AuthoritativeHeadSnapshot,
        peerModuleId: String,
        localModuleId: String,
    ) {
        if (peerModuleId == localModuleId) return
        val current = ledger.currentForPeer(snapshot.sessionId, peerModuleId)
        if (current?.state == Profile01HeadObligationState.ACKED) return
        if (current != null &&
            current.id.headDigest == snapshot.headDigest &&
            (
                current.state == Profile01HeadObligationState.OPEN ||
                    current.state == Profile01HeadObligationState.DELIVERING ||
                    current.state == Profile01HeadObligationState.FAULT
                )
        ) {
            return
        }
        openAndKick(
            snapshot = snapshot,
            peerModuleIds = setOf(peerModuleId),
            localModuleId = localModuleId,
        )
    }

    fun openAndKick(
        snapshot: Profile01AuthoritativeHeadSnapshot,
        peerModuleIds: Set<String>,
        localModuleId: String,
    ) {
        ledger.closeAbsentPeers(
            sessionId = snapshot.sessionId,
            remainingPeerIds = peerModuleIds.filter { it != localModuleId }.toSet(),
        )
        val opened =
            ledger.openForSnapshot(
                snapshot = snapshot,
                peerModuleIds = peerModuleIds,
                localModuleId = localModuleId,
                nowMs = clockMs(),
            )
        opened.forEach { record ->
            onLog(
                "PROFILE01_HEAD_OBLIGATION session=${record.id.sessionId} " +
                    "peer=${record.id.peerModuleId} head=${record.id.headDigest} state=OPEN",
            )
            schedule(0L) { attempt(record.id) }
        }
        ledger.unfinished(snapshot.sessionId)
            .filter { it.snapshot.headDigest == snapshot.headDigest }
            .filter { opened.none { o -> o.id == it.id } }
            .forEach { existing ->
                schedule(0L) { attempt(existing.id) }
            }
    }

    fun deliverNow(id: Profile01HeadObligationId) {
        schedule(0L) { attempt(id) }
    }

    fun onStaleHeadAck(
        sessionId: String,
        peerModuleId: String,
        ackHeadDigest: String,
    ) {
        val live = liveSnapshot(sessionId) ?: return
        if (live.headDigest == ackHeadDigest) return
        onLog(
            "PROFILE01_HEAD_ACK stale_resync session=$sessionId peer=$peerModuleId " +
                "ackHead=$ackHeadDigest liveHead=${live.headDigest}",
        )
        ensureUnackedCurrentHeadKicking(
            snapshot = live,
            peerModuleId = peerModuleId,
            localModuleId = "",
        )
        ledger.currentForPeer(sessionId, peerModuleId)?.let { deliverNow(it.id) }
    }

    fun onNack(
        sessionId: String,
        peerModuleId: String,
        mediaKeyEpoch: Long,
    ) {
        val record = ledger.currentForPeer(sessionId, peerModuleId) ?: return
        if (record.state == Profile01HeadObligationState.ACKED) return
        if (record.snapshot.mediaKeyEpoch != mediaKeyEpoch) return
        val live = liveSnapshot(sessionId) ?: return
        if (live.headDigest != record.id.headDigest) return
        deliverNow(record.id)
    }

    fun closeSession(sessionId: String) {
        ledger.closeSession(sessionId)
    }

    fun closePeer(sessionId: String, peerModuleId: String) {
        ledger.closePeer(sessionId, peerModuleId)
    }

    private fun attempt(id: Profile01HeadObligationId) {
        val current = ledger.record(id) ?: return
        if (current.state == Profile01HeadObligationState.ACKED ||
            current.state == Profile01HeadObligationState.CLOSED ||
            current.state == Profile01HeadObligationState.SUPERSEDED
        ) {
            return
        }
        val live = liveSnapshot(id.sessionId)
        if (live != null && live.headDigest != id.headDigest) {
            ledger.openForSnapshot(
                snapshot = live,
                peerModuleIds = setOf(id.peerModuleId),
                localModuleId = "",
                nowMs = clockMs(),
            )
            return
        }
        val outcome = deliver(id, current.snapshot)
        if (outcome.liveHeadDigest != null && outcome.liveHeadDigest != id.headDigest) {
            live?.let {
                ledger.openForSnapshot(
                    snapshot = it,
                    peerModuleIds = setOf(id.peerModuleId),
                    localModuleId = "",
                    nowMs = clockMs(),
                )
            }
            return
        }
        val updated =
            ledger.recordAttempt(
                id = id,
                nowMs = clockMs(),
                sendApiResult = outcome.sendApiResult,
                failure = outcome.failure,
                faultAfterMs = faultAfterMs,
            ) ?: return
        if (updated.state == Profile01HeadObligationState.FAULT && current.state != Profile01HeadObligationState.FAULT) {
            onLog(
                "PROFILE01_HEAD_OBLIGATION session=${id.sessionId} peer=${id.peerModuleId} " +
                    "head=${id.headDigest} state=FAULT attempts=${updated.attemptCount} " +
                    "sendApiResult=${updated.sendApiResult} lastFailure=${updated.lastFailure ?: "-"} " +
                    "durationMs=${updated.faultAtMs?.minus(updated.openedAtMs) ?: 0}",
            )
        }
        if (updated.state == Profile01HeadObligationState.ACKED ||
            updated.state == Profile01HeadObligationState.CLOSED ||
            updated.state == Profile01HeadObligationState.SUPERSEDED
        ) {
            return
        }
        val delay = nextDelayMs(updated.attemptCount, updated.state)
        schedule(delay) { attempt(id) }
    }

    private fun nextDelayMs(attemptCount: Int, state: Profile01HeadObligationState): Long {
        val base =
            if (state == Profile01HeadObligationState.FAULT) {
                FAULT_RETRY_MS
            } else {
                BACKOFF_MS[attemptCount.coerceIn(0, BACKOFF_MS.lastIndex)]
            }
        if (base <= 0L) return 0L
        val jitter = (base * JITTER_FRACTION * random.nextDouble()).toLong()
        return base + jitter
    }

    companion object {
        const val FAULT_AFTER_MS = 30_000L
        const val FAULT_RETRY_MS = 4_000L
        private val BACKOFF_MS = longArrayOf(0L, 250L, 500L, 1_000L, 2_000L, 4_000L)
        private const val JITTER_FRACTION = 0.2
    }
}
