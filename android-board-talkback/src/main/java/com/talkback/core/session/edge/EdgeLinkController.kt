package com.talkback.core.session.edge

import com.talkback.core.qos.IceConnectivity

/**
 * Per-node owner of every mesh edge lifecycle (see docs/analysis/EDGE-LINK-CONTROLLER-001.md).
 *
 * Pure state machine: facts in, actions out, one deadline per edge, one escalation ladder:
 * retransmit → ICE restart (only when legal) → PeerConnection rebuild → FAILED with backoff.
 * Callers own threading (coordinator thread) and all I/O.
 */
class EdgeLinkController(
    private val config: EdgeLinkConfig = EdgeLinkConfig(),
    private val clock: () -> Long,
    private val onTransition: (EdgeTransition) -> Unit = {},
) {
    private class Record(val key: EdgeKey) {
        var role: EdgeRole? = null
        var state: EdgeLinkState = EdgeLinkState.IDLE
        var generation: Long = UNKNOWN_GENERATION
        var iceState: String? = null
        /** Peer signaling-reachable (fresh HELLO / dialable). Unreachable edges park in IDLE. */
        var reachable: Boolean = true
        var restartLegal: Boolean = true
        var pendingLineage: String? = null
        var offerRetransmits: Int = 0
        var restarts: Int = 0
        var rebuilds: Int = 0
        var reofferRequests: Int = 0
        var backoffMs: Long = 0L
        var deadline: EdgeDeadline = EdgeDeadline.NONE
        var deadlineAtMs: Long = 0L
    }

    private val records = linkedMapOf<EdgeKey, Record>()

    fun state(key: EdgeKey): EdgeLinkState? = records[key]?.state

    fun generation(key: EdgeKey): Long? = records[key]?.generation

    fun snapshot(): Map<EdgeKey, EdgeLinkState> = records.mapValues { it.value.state }

    /** Edges currently non-CONNECTED for longer than [thresholdMs] — the single field KPI. */
    fun stalledEdges(thresholdMs: Long, now: Long = clock()): List<EdgeKey> =
        records.values
            .filter { it.state != EdgeLinkState.CONNECTED && it.state != EdgeLinkState.FAILED }
            .filter { it.deadline != EdgeDeadline.NONE && now - (it.deadlineAtMs - deadlineLength(it)) >= thresholdMs }
            .map { it.key }

    fun onFact(key: EdgeKey, fact: EdgeFact): List<EdgeAction> {
        val now = clock()
        val out = mutableListOf<EdgeAction>()
        when (fact) {
            is EdgeFact.Desired -> onDesired(key, fact.role, now, out)
            EdgeFact.Undesired -> {
                if (records.remove(key) != null) out += EdgeAction.Release(key)
            }
            is EdgeFact.PeerReachability -> onReachability(key, fact.reachable, now, out)
            is EdgeFact.OfferSent -> withGeneration(key, fact.generation, now, out) { r ->
                r.pendingLineage = fact.lineageId
                when (r.state) {
                    EdgeLinkState.IDLE, EdgeLinkState.OFFER_SENT, EdgeLinkState.AWAITING_OFFER ->
                        transition(r, EdgeLinkState.OFFER_SENT, "OFFER_SENT", now, EdgeDeadline.ANSWER)
                    EdgeLinkState.RESTARTING -> if (fact.iceRestart) {
                        arm(r, EdgeDeadline.ICE, now)
                    } else {
                        transition(r, EdgeLinkState.OFFER_SENT, "OFFER_SENT", now, EdgeDeadline.ANSWER)
                    }
                    else -> Unit
                }
            }
            is EdgeFact.RemoteOfferApplied -> withGeneration(key, fact.generation, now, out) { r ->
                if (r.state != EdgeLinkState.CONNECTED) {
                    transition(r, EdgeLinkState.ICE_CHECKING, "REMOTE_OFFER_APPLIED", now, EdgeDeadline.ICE)
                }
            }
            is EdgeFact.AnswerSent -> withGeneration(key, fact.generation, now, out) { r ->
                if (r.state != EdgeLinkState.CONNECTED && r.state != EdgeLinkState.ICE_CHECKING) {
                    transition(r, EdgeLinkState.ICE_CHECKING, "ANSWER_SENT", now, EdgeDeadline.ICE)
                }
            }
            is EdgeFact.AnswerApplied -> withGeneration(key, fact.generation, now, out) { r ->
                r.pendingLineage = null
                r.offerRetransmits = 0
                if (r.state != EdgeLinkState.CONNECTED) {
                    transition(r, EdgeLinkState.ICE_CHECKING, "ANSWER_APPLIED", now, EdgeDeadline.ICE)
                }
            }
            is EdgeFact.RestartCapability -> withGeneration(key, fact.generation, now, out) { r ->
                r.restartLegal = fact.legal
            }
            is EdgeFact.IceState -> withGeneration(key, fact.generation, now, out) { r ->
                onIce(r, fact.state, now, out)
            }
            is EdgeFact.ReofferRequested -> withGeneration(key, fact.generation, now, out) { r ->
                if (r.role != EdgeRole.OFFERER) return@withGeneration
                when (r.state) {
                    EdgeLinkState.CONNECTED,
                    EdgeLinkState.RESTARTING,
                    EdgeLinkState.REBUILDING,
                    EdgeLinkState.FAILED,
                    EdgeLinkState.AWAITING_OFFER -> Unit
                    EdgeLinkState.IDLE -> fillSlots(now, out)
                    EdgeLinkState.OFFER_SENT -> {
                        // Answerer missed the offer: same-lineage retransmit, do not burn restart budget.
                        arm(r, EdgeDeadline.ANSWER, now)
                        out += EdgeAction.SendOffer(
                            r.key,
                            r.generation,
                            iceRestart = false,
                            retransmitOfLineage = r.pendingLineage,
                        )
                        emit(r, r.state, "REOFFER_REQUESTED_RETRANSMIT", now)
                    }
                    EdgeLinkState.ICE_CHECKING, EdgeLinkState.DEGRADED ->
                        escalateOfferer(r, "REOFFER_REQUESTED", now, out)
                }
            }
            is EdgeFact.RebuildCompleted -> {
                val r = records[key] ?: return out
                if (fact.newGeneration <= r.generation && r.generation != UNKNOWN_GENERATION) return out
                adoptGeneration(r, fact.newGeneration, "REBUILD_COMPLETED", now, out)
            }
        }
        return out
    }

    /** Expire deadlines, then fill free negotiation slots. Call periodically (≈1s) and after facts. */
    fun tick(now: Long = clock()): List<EdgeAction> {
        val out = mutableListOf<EdgeAction>()
        records.values.sortedBy { it.key.peerModuleId }.forEach { r ->
            if (r.deadline == EdgeDeadline.NONE || now < r.deadlineAtMs) return@forEach
            onDeadline(r, now, out)
        }
        fillSlots(now, out)
        return out
    }

    // ---- fact handlers -------------------------------------------------------------------

    private fun onDesired(key: EdgeKey, role: EdgeRole, now: Long, out: MutableList<EdgeAction>) {
        val r = records.getOrPut(key) { Record(key) }
        if (r.role != null && r.role != role) {
            r.role = role
            resetBudgets(r)
            transition(r, EdgeLinkState.IDLE, "ROLE_CHANGED", now, EdgeDeadline.NONE)
        }
        r.role = role
        if (r.state == EdgeLinkState.IDLE && r.reachable) {
            if (role == EdgeRole.ANSWERER) {
                transition(r, EdgeLinkState.AWAITING_OFFER, "DESIRED", now, EdgeDeadline.OFFER)
            } else {
                fillSlots(now, out)
            }
        }
    }

    /**
     * Reachability is a precondition for every negotiation step: against a peer that has gone
     * silent (no HELLO, not dialable) retransmit/restart/rebuild only burn budget and end in a
     * long backoff that then delays the real reconnect (11-06 M04 kill test: 40 s lost).
     */
    private fun onReachability(key: EdgeKey, reachable: Boolean, now: Long, out: MutableList<EdgeAction>) {
        // May precede Desired (coordinator feeds reachability first) so the first slot grant
        // already honours it; a role-less record is inert until Desired arrives.
        val r = records.getOrPut(key) { Record(key) }
        val was = r.reachable
        r.reachable = reachable
        if (!reachable) {
            if (was && r.state != EdgeLinkState.CONNECTED && r.state != EdgeLinkState.DEGRADED &&
                r.state != EdgeLinkState.IDLE
            ) {
                resetBudgets(r)
                r.backoffMs = 0L
                transition(r, EdgeLinkState.IDLE, "PEER_UNREACHABLE", now, EdgeDeadline.NONE)
            }
            return
        }
        when {
            r.state == EdgeLinkState.FAILED ->
                // Peer came back (HELLO) — do not wait out the remaining backoff.
                leaveFailed(r, "PEER_REACHABLE", now, out)
            !was && r.state == EdgeLinkState.IDLE && r.role != null ->
                leaveFailed(r, "PEER_REACHABLE", now, out)
        }
    }

    private fun onIce(r: Record, ice: String, now: Long, out: MutableList<EdgeAction>) {
        r.iceState = ice
        when {
            IceConnectivity.isConnected(ice) -> {
                if (r.state != EdgeLinkState.FAILED && r.state != EdgeLinkState.CONNECTED) {
                    resetBudgets(r)
                    r.backoffMs = 0L
                    transition(r, EdgeLinkState.CONNECTED, "ICE_$ice", now, EdgeDeadline.NONE)
                }
            }
            ice == "CHECKING" || ice == "NEW" -> {
                if (r.state == EdgeLinkState.CONNECTED || r.state == EdgeLinkState.DEGRADED) {
                    transition(r, EdgeLinkState.ICE_CHECKING, "ICE_$ice", now, EdgeDeadline.ICE)
                }
            }
            ice == "DISCONNECTED" -> {
                if (r.state == EdgeLinkState.CONNECTED) {
                    transition(r, EdgeLinkState.DEGRADED, "ICE_DISCONNECTED", now, EdgeDeadline.DEGRADED_GRACE)
                }
            }
            ice == "FAILED" || ice == "CLOSED" -> {
                if (r.state != EdgeLinkState.FAILED && r.state != EdgeLinkState.REBUILDING && r.state != EdgeLinkState.IDLE) {
                    escalate(r, "ICE_$ice", now, out)
                }
            }
        }
    }

    private fun onDeadline(r: Record, now: Long, out: MutableList<EdgeAction>) {
        val kind = r.deadline
        r.deadline = EdgeDeadline.NONE
        when (kind) {
            EdgeDeadline.ANSWER -> {
                if (r.offerRetransmits < config.answerRetransmitMax) {
                    r.offerRetransmits++
                    arm(r, EdgeDeadline.ANSWER, now)
                    out += EdgeAction.SendOffer(r.key, r.generation, iceRestart = false, retransmitOfLineage = r.pendingLineage)
                    emit(r, r.state, "ANSWER_TIMEOUT_RETRANSMIT", now)
                } else {
                    escalateOfferer(r, "ANSWER_TIMEOUT", now, out)
                }
            }
            EdgeDeadline.OFFER -> escalateAnswerer(r, "OFFER_TIMEOUT", now, out)
            EdgeDeadline.ICE -> escalate(r, "ICE_TIMEOUT", now, out)
            EdgeDeadline.DEGRADED_GRACE -> escalate(r, "DEGRADED_TIMEOUT", now, out)
            EdgeDeadline.REBUILD -> rebuildOrFail(r, "REBUILD_TIMEOUT", now, out)
            EdgeDeadline.BACKOFF -> leaveFailed(r, "BACKOFF_ELAPSED", now, out)
            EdgeDeadline.NONE -> Unit
        }
    }

    // ---- escalation ladder ---------------------------------------------------------------

    private fun escalate(r: Record, cause: String, now: Long, out: MutableList<EdgeAction>) {
        if (!r.reachable) {
            resetBudgets(r)
            transition(r, EdgeLinkState.IDLE, "${cause}_PEER_UNREACHABLE", now, EdgeDeadline.NONE)
            return
        }
        when (r.role) {
            EdgeRole.OFFERER -> escalateOfferer(r, cause, now, out)
            EdgeRole.ANSWERER -> escalateAnswerer(r, cause, now, out)
            null -> Unit
        }
    }

    /** Offerer: ICE restart (if legal, bounded) → rebuild → FAILED. */
    private fun escalateOfferer(r: Record, cause: String, now: Long, out: MutableList<EdgeAction>) {
        if (r.restarts < config.restartMax && r.restartLegal && r.generation != UNKNOWN_GENERATION) {
            r.restarts++
            transition(r, EdgeLinkState.RESTARTING, cause, now, EdgeDeadline.ICE)
            out += EdgeAction.SendOffer(r.key, r.generation, iceRestart = true, retransmitOfLineage = null)
            return
        }
        rebuildOrFail(r, cause, now, out)
    }

    /** Answerer: ask the offerer to re-offer (bounded) → rebuild own PC → FAILED. */
    private fun escalateAnswerer(r: Record, cause: String, now: Long, out: MutableList<EdgeAction>) {
        if (r.reofferRequests < config.reofferRequestMax) {
            r.reofferRequests++
            val next = if (r.state == EdgeLinkState.ICE_CHECKING || r.state == EdgeLinkState.RESTARTING ||
                r.state == EdgeLinkState.CONNECTED || r.state == EdgeLinkState.DEGRADED
            ) {
                EdgeLinkState.RESTARTING
            } else {
                EdgeLinkState.AWAITING_OFFER
            }
            transition(r, next, cause, now, EdgeDeadline.OFFER)
            out += EdgeAction.RequestReoffer(r.key, r.generation)
            return
        }
        rebuildOrFail(r, cause, now, out)
    }

    private fun rebuildOrFail(r: Record, cause: String, now: Long, out: MutableList<EdgeAction>) {
        if (r.rebuilds < config.rebuildMax) {
            r.rebuilds++
            r.restarts = 0
            r.reofferRequests = 0
            r.offerRetransmits = 0
            r.pendingLineage = null
            transition(r, EdgeLinkState.REBUILDING, cause, now, EdgeDeadline.REBUILD)
            out += EdgeAction.RebuildPeerConnection(r.key, r.generation)
            return
        }
        r.backoffMs = if (r.backoffMs <= 0L) config.backoffInitialMs else minOf(config.backoffMaxMs, r.backoffMs * 2)
        transition(r, EdgeLinkState.FAILED, cause, now, EdgeDeadline.BACKOFF)
        out += EdgeAction.MarkFailed(r.key, cause, r.deadlineAtMs)
    }

    private fun leaveFailed(r: Record, cause: String, now: Long, out: MutableList<EdgeAction>) {
        resetBudgets(r)
        transition(r, EdgeLinkState.IDLE, cause, now, EdgeDeadline.NONE)
        if (r.role == EdgeRole.ANSWERER) {
            transition(r, EdgeLinkState.AWAITING_OFFER, cause, now, EdgeDeadline.OFFER)
        } else {
            fillSlots(now, out)
        }
    }

    private fun adoptGeneration(r: Record, generation: Long, cause: String, now: Long, out: MutableList<EdgeAction>) {
        r.generation = generation
        r.restartLegal = true
        r.restarts = 0
        r.iceState = null
        r.pendingLineage = null
        when (r.role) {
            EdgeRole.ANSWERER -> {
                transition(r, EdgeLinkState.AWAITING_OFFER, cause, now, EdgeDeadline.OFFER)
                out += EdgeAction.RequestReoffer(r.key, r.generation)
            }
            EdgeRole.OFFERER -> {
                // Fresh PC on a stuck edge gets priority over queued cold starts.
                transition(r, EdgeLinkState.OFFER_SENT, cause, now, EdgeDeadline.ANSWER)
                out += EdgeAction.SendOffer(r.key, r.generation, iceRestart = false, retransmitOfLineage = null)
            }
            null -> transition(r, EdgeLinkState.IDLE, cause, now, EdgeDeadline.NONE)
        }
    }

    // ---- concurrency cap (8–10 node mesh) ------------------------------------------------

    private fun activeNegotiations(): Int =
        records.values.count {
            it.state == EdgeLinkState.OFFER_SENT ||
                it.state == EdgeLinkState.RESTARTING ||
                it.state == EdgeLinkState.REBUILDING
        }

    private fun fillSlots(now: Long, out: MutableList<EdgeAction>) {
        var free = config.maxConcurrentNegotiations - activeNegotiations()
        if (free <= 0) return
        records.values
            .filter { it.role == EdgeRole.OFFERER && it.state == EdgeLinkState.IDLE && it.reachable }
            .sortedBy { it.key.peerModuleId }
            .forEach { r ->
                if (free <= 0) return
                free--
                transition(r, EdgeLinkState.OFFER_SENT, "SLOT_GRANTED", now, EdgeDeadline.ANSWER)
                out += EdgeAction.SendOffer(r.key, r.generation, iceRestart = false, retransmitOfLineage = null)
            }
    }

    // ---- helpers -------------------------------------------------------------------------

    private inline fun withGeneration(
        key: EdgeKey,
        generation: Long,
        now: Long,
        out: MutableList<EdgeAction>,
        block: (Record) -> Unit,
    ) {
        val r = records[key] ?: return
        when {
            r.generation == UNKNOWN_GENERATION -> r.generation = generation
            generation < r.generation -> return
            generation > r.generation -> {
                if (r.state == EdgeLinkState.REBUILDING) {
                    adoptGeneration(r, generation, "GENERATION_ADVANCED", now, out)
                    return block(r)
                }
                r.generation = generation
                r.restarts = 0
                r.restartLegal = true
            }
        }
        block(r)
    }

    private fun resetBudgets(r: Record) {
        r.offerRetransmits = 0
        r.restarts = 0
        r.rebuilds = 0
        r.reofferRequests = 0
        r.pendingLineage = null
    }

    private fun deadlineLength(r: Record): Long = when (r.deadline) {
        EdgeDeadline.ANSWER -> config.answerTimeoutMs
        EdgeDeadline.OFFER -> config.offerTimeoutMs
        EdgeDeadline.ICE -> config.iceCheckingTimeoutMs
        EdgeDeadline.DEGRADED_GRACE -> config.degradedGraceMs
        EdgeDeadline.REBUILD -> config.rebuildTimeoutMs
        EdgeDeadline.BACKOFF -> r.backoffMs
        EdgeDeadline.NONE -> 0L
    }

    private fun arm(r: Record, deadline: EdgeDeadline, now: Long) {
        r.deadline = deadline
        r.deadlineAtMs = if (deadline == EdgeDeadline.NONE) 0L else now + deadlineLength(r)
    }

    private fun transition(r: Record, to: EdgeLinkState, cause: String, now: Long, deadline: EdgeDeadline) {
        val from = r.state
        r.state = to
        arm(r, deadline, now)
        emit(r, from, cause, now)
    }

    private fun emit(r: Record, from: EdgeLinkState, cause: String, now: Long) {
        onTransition(
            EdgeTransition(
                key = r.key,
                generation = r.generation,
                role = r.role,
                from = from,
                to = r.state,
                cause = cause,
                iceState = r.iceState,
                deadline = r.deadline,
                deadlineAtMs = r.deadlineAtMs,
                offerRetransmits = r.offerRetransmits,
                restarts = r.restarts,
                rebuilds = r.rebuilds,
                reofferRequests = r.reofferRequests,
            )
        )
    }

    companion object {
        const val UNKNOWN_GENERATION = -1L

        fun formatTransition(t: EdgeTransition): String =
            "EDGE_LINK ch=${t.key.channelId} peer=${t.key.peerModuleId} gen=${t.generation} " +
                "role=${t.role ?: "UNKNOWN"} ${t.from}->${t.to} cause=${t.cause} " +
                "ice=${t.iceState ?: "UNKNOWN"} deadline=${t.deadline}@${t.deadlineAtMs} " +
                "retx=${t.offerRetransmits} restarts=${t.restarts} rebuilds=${t.rebuilds} reoffers=${t.reofferRequests}"
    }
}
