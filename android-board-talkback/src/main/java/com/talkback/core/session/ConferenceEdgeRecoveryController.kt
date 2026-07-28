package com.talkback.core.session

import com.talkback.core.qos.IceConnectivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-edge conference recovery policy and state (ADR-0021 R4–R18).
 * Control-plane reattach precedes bounded media ICE restart; termination cancels all edges.
 */
class ConferenceEdgeRecoveryController(
  private val localModuleId: String = "LOCAL",
    private val debounceMs: Long = 3_000L,
    private val iceRestartTimeoutMs: Long = 10_000L,
    private val attemptBudgetMs: Long = 15_000L,
    /**
     * Observation window after failed-media residency (ADR-0022 R28-H).
     * `obligationDeadlineAt = attemptTerminalAt + observationWindow`. Must be meaningfully
     * longer than the soak's ~4s premature prune; tests may inject a short window.
     */
    private val observationWindowMs: Long = 30_000L,
    private val tombstoneTtlMs: Long = 120_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scheduler: ScheduledExecutorService,
    private val onLog: (String) -> Unit = {},
    private val onRequestReattach: (
        sessionId: String,
        channelId: String,
        remoteModuleId: String
    ) -> ReattachDispatchOutcome,
    private val onIceRestart: (sessionId: String, remoteModuleId: String) -> Boolean,
    /**
     * Action gate for host ICE restart dispatch (ADR-0032 INV-REC-010).
     * When false, [resolveMediaActionOwner] defers until transport, discovery and peer
     * signaling allow initiation. MUST NOT require [EdgeReachabilitySnapshot.mediaRouteConnected].
     */
    private val canDispatchRecoveryMediaAction: (sessionId: String, remoteModuleId: String) -> Boolean =
        { _, _ -> true },
    /**
     * Negotiation Stabilization Gate (INV-NEG-006): execution admission probe.
     * Settling checked before signalingState. Injected by Coordinator;
     * Recovery MUST NOT read MeshEngine settling directly.
     * Step A-1: [IceRestartGateProbe.blockReason] splits ANSWERER_SETTLING vs SIGNALING_NOT_STABLE.
     */
    private val probeIceRestartGate: (sessionId: String, remoteModuleId: String) -> IceRestartGateProbe =
        { _, _ -> IceRestartGateProbe(executable = true) },
    /**
     * Probe current ICE connectedness after ACCEPTED / ICE restart (#83).
     * Coordinator wires qosMonitor; tests inject to cover already-CONNECTED soak gap.
     */
    private val isIceConnected: (sessionId: String, remoteModuleId: String) -> Boolean = { _, _ -> false },
    private val onRecoveryStateChanged: (sessionId: String) -> Unit = {},
    /**
     * Observe-only hook for attempt lineage snapshots (ADR-0022 completion causality).
     * [supersededFromAttempt] is set only on SUPERSEDE pathways.
     */
    private val onAttemptLineageObservation: (
        sessionId: String,
        remoteModuleId: String,
        trigger: String,
        supersededFromAttempt: Long?
    ) -> Unit = { _, _, _, _ -> }
) {
    private val edges = ConcurrentHashMap<ConferenceEdgeKey, EdgeRecoveryRecord>()
    private val debounceTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val watchdogTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val deadlineTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val cancelledSessions = ConcurrentHashMap<String, Long>()
    private val cancelledChannels = ConcurrentHashMap<String, Long>()
    private val pendingTransportNonce = ConcurrentHashMap<ConferenceEdgeKey, String>()
    private var attemptSeq = 0L
    /** Commit Seam Trace: monotonic ICE-restart deferred intent ids (R1, R2, …). */
    private val iceRestartIntentSeq = AtomicLong(0L)
    /** G-R28-M-5: suppress duplicate post-terminal facts (e.g. repeated HELLO). */
    private data class TerminalReevaluateKey(val attemptId: Long, val trigger: RecoveryReevaluateTrigger)
    private val terminalReevaluateDedup = ConcurrentHashMap<ConferenceEdgeKey, TerminalReevaluateKey>()

    fun factsForSession(sessionId: String): EdgeRecoveryFacts {
        val sessionEdges = edges.values.filter { it.key.sessionId == sessionId }
        val recovering = sessionEdges
            .filter { it.phase.isActivelyRecovering() }
            .map { it.key.remoteModuleId }
            .toSet()
        val failed = sessionEdges
            .filter { it.phase.isFailedMediaRecovery() }
            .map { it.key.remoteModuleId }
            .toSet()
        // ADR-0030: failed-media residency (e.g. FAILED_MEDIA_RECOVERY) == mediaUnavailable(P).
        return EdgeRecoveryFacts(
            recoveringRemoteModuleIds = recovering,
            anyRecovering = recovering.isNotEmpty(),
            failedRemoteModuleIds = failed,
            anyFailedMediaRecovery = failed.isNotEmpty(),
            mediaUnavailableRemoteModuleIds = failed
        )
    }

    /** Per-peer ADR-0030 fact: failed-media residency, not active recovery attempt. */
    fun isMediaUnavailable(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.phase.isFailedMediaRecovery()
    }

    fun isAnyEdgeRecovering(sessionId: String): Boolean = factsForSession(sessionId).anyRecovering

    /** True while edge (sessionId, remoteModuleId) is in an active recovery ownership window (R26). */
    fun isEdgeRecovering(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.phase.isActivelyRecovering()
    }

    /** Whether current attempt crossed the control-plane boundary (ADR-0022 R28-E). */
    fun isControlPlaneStarted(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.controlPlaneStarted()
    }

    /**
     * Recovery Edge Obligation OPEN (ADR-0022 R28-H).
     * OPEN until exclusive close stamp (including [ObligationCloseReason.OBLIGATION_DEADLINE]).
     */
    fun edgeObligationOpen(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.edgeObligationOpen()
    }

    /** True after controller stamped an exclusive close reason (ADR-0022 R28-H). */
    fun edgeObligationClosed(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.obligationClosedAtMs != null
    }

    fun obligationDeadlineAt(sessionId: String, remoteModuleId: String): Long? =
        edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.obligationDeadlineAtMs

    fun obligationCloseReason(sessionId: String, remoteModuleId: String): ObligationCloseReason? =
        edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.obligationCloseReason

    fun hasPendingCompletionDecision(sessionId: String, remoteModuleId: String): Boolean =
        edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.hasPendingCompletionDecision ?: false

    /**
     * Appendix C-3.2 (C-12): deferred attempt with [WakeupBinding] matching [trigger].
     * Used by coordinator materiality gate to force RECOVERY_REEVALUATE.
     */
    fun hasDeferredWakeupForTrigger(
        sessionId: String,
        remoteModuleId: String,
        trigger: RecoveryReevaluateTrigger
    ): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        if (!record.edgeObligationOpen() || !hasDeferredMediaAction(record)) return false
        val binding = record.wakeupBinding ?: return false
        return binding.matchesTrigger(trigger, sessionId, remoteModuleId)
    }

    /** Forensics snapshot for lifecycle trace (observe only). */
    fun pendingForensics(sessionId: String): List<String> {
        val actions = mutableListOf<String>()
        debounceTimers.keys.filter { it.sessionId == sessionId }.forEach { key ->
            actions.add("DEBOUNCE:${key.remoteModuleId}")
        }
        watchdogTimers.keys.filter { it.sessionId == sessionId }.forEach { key ->
            actions.add("WATCHDOG:${key.remoteModuleId}")
        }
        deadlineTimers.keys.filter { it.sessionId == sessionId }.forEach { key ->
            actions.add("DEADLINE:${key.remoteModuleId}")
        }
        edges.values.filter { it.key.sessionId == sessionId }.forEach { record ->
            if (record.hasPendingCompletionDecision) {
                actions.add("PENDING_COMPLETION:${record.key.remoteModuleId}")
            }
            if (record.edgeObligationOpen()) {
                actions.add("OBLIGATION_OPEN:${record.key.remoteModuleId}")
            }
        }
        if (cancelledSessions.containsKey(sessionId)) {
            actions.add("SESSION_CANCELLED")
        }
        return actions
    }

    fun edgePhaseSummary(sessionId: String): String =
        edges.values
            .filter { it.key.sessionId == sessionId }
            .joinToString(";") { record ->
                "${record.key.remoteModuleId}:${record.phase}@a${record.recoveryAttemptId}"
            }

    /** Read-only attempt lineage for ownership observation (no mutation). */
    fun attemptLineageObservation(sessionId: String, remoteModuleId: String): EdgeAttemptLineageRaw? {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return null
        return EdgeAttemptLineageRaw(
            attemptId = record.recoveryAttemptId,
            attemptStartedAtMs = record.recoveryStartedAtMs,
            phase = record.phase,
            mediaRestored = record.mediaRestored,
            obligationOpen = record.edgeObligationOpen(),
            pendingCompletion = record.hasPendingCompletionDecision,
            obligationGeneration = record.obligationGeneration
        )
    }

    fun obligationGeneration(sessionId: String, remoteModuleId: String): Long? =
        edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.obligationGeneration

    /** Lineage material for outbound REATTACH wire encoding (ADR-0022 Appendix D). */
    fun reattachDispatchLineage(sessionId: String, remoteModuleId: String): ReattachDispatchLineage? {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return null
        return ReattachDispatchLineage(
            attemptId = record.recoveryAttemptId,
            obligationGeneration = record.obligationGeneration
        )
    }

    /** Coordinator registers envelope nonce after transport send, before dispatch outcome apply. */
    fun registerReattachTransportNonce(sessionId: String, remoteModuleId: String, nonce: String) {
        pendingTransportNonce[ConferenceEdgeKey(sessionId, remoteModuleId)] = nonce
    }

    fun evaluateInboundReattachLineage(
        sessionId: String,
        remoteModuleId: String,
        senderAttemptId: Long,
        senderObligationGeneration: Long
    ): InboundReattachLineageVerdict {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return InboundReattachLineageVerdict.ACCEPT
        if (record.obligationClosedAtMs != null) return InboundReattachLineageVerdict.OBLIGATION_CLOSED
        if (senderObligationGeneration > 0L &&
            record.obligationGeneration > 0L &&
            senderObligationGeneration < record.obligationGeneration
        ) {
            return InboundReattachLineageVerdict.STALE_OBLIGATION_GENERATION
        }
        if (senderAttemptId > 0L &&
            record.recoveryAttemptId > 0L &&
            senderAttemptId < record.recoveryAttemptId
        ) {
            return InboundReattachLineageVerdict.STALE_OBLIGATION_GENERATION
        }
        return InboundReattachLineageVerdict.ACCEPT
    }

    fun onRecoveryReattachReceipt(
        sessionId: String,
        remoteModuleId: String,
        nonce: String,
        attemptId: Long,
        obligationGeneration: Long
    ): Boolean {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return false
        if (record.obligationClosedAtMs != null) return false
        if (obligationGeneration > 0L &&
            record.obligationGeneration > 0L &&
            obligationGeneration != record.obligationGeneration
        ) {
            onLog(
                "RECOVERY_REATTACH_RECEIPT_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "reason=stale_obligation_generation receiptGen=$obligationGeneration " +
                    "currentGen=${record.obligationGeneration} attempt=$attemptId"
            )
            return false
        }
        if (attemptId > 0L &&
            record.recoveryAttemptId > 0L &&
            attemptId != record.recoveryAttemptId
        ) {
            if (record.reattachNonce == nonce &&
                record.reattachDeliveryState == ReattachDeliveryState.REMOTE_RECEIPT_ACKED
            ) {
                return true
            }
            onLog(
                "RECOVERY_REATTACH_RECEIPT_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "reason=stale_attempt receiptAttempt=$attemptId currentAttempt=${record.recoveryAttemptId}"
            )
            return false
        }
        if (record.reattachNonce == nonce &&
            record.reattachDeliveryState == ReattachDeliveryState.REMOTE_RECEIPT_ACKED
        ) {
            return true
        }
        record.reattachDeliveryState = ReattachDeliveryState.REMOTE_RECEIPT_ACKED
        onLog(
            "RECOVERY_REATTACH_RECEIPT session=$sessionId remote=$remoteModuleId " +
                "nonce=$nonce attempt=$attemptId obligationGen=$obligationGeneration " +
                "deliveryState=REMOTE_RECEIPT_ACKED controlPlaneStarted=${record.controlPlaneStarted()}"
        )
        notifyChanged(sessionId)
        return true
    }

    fun onRecoveryReattachInboundReceived(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        senderAttemptId: Long,
        senderObligationGeneration: Long,
        nonce: String
    ): InboundReattachLineageVerdict {
        val verdict = evaluateInboundReattachLineage(
            sessionId,
            remoteModuleId,
            senderAttemptId,
            senderObligationGeneration
        )
        if (verdict != InboundReattachLineageVerdict.ACCEPT) {
            onLog(
                "RECOVERY_REATTACH_INBOUND_REJECTED session=$sessionId remote=$remoteModuleId " +
                    "reason=$verdict senderAttempt=$senderAttemptId senderObligationGen=$senderObligationGeneration " +
                    "nonce=$nonce"
            )
            return verdict
        }
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: upsertEdge(
            key,
            channelId,
            EdgeRecoveryPhase.RECOVERY_PENDING,
            initiatesReattach = false,
            newAttempt = true,
            attemptOpenTrigger = "INBOUND_REATTACH"
        )
        record.channelId = channelId
        record.reattachDeliveryState = ReattachDeliveryState.RECEIVED
        onLog(
            "RECOVERY_REATTACH_INBOUND session=$sessionId remote=$remoteModuleId " +
                "deliveryState=RECEIVED senderAttempt=$senderAttemptId " +
                "senderObligationGen=$senderObligationGeneration nonce=$nonce"
        )
        notifyChanged(sessionId)
        return InboundReattachLineageVerdict.ACCEPT
    }

    fun onRecoveryReattachInboundDeferred(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        reason: DeferredReason,
        trigger: String = "INBOUND_REATTACH"
    ) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: upsertEdge(
            key,
            channelId,
            EdgeRecoveryPhase.RECOVERY_PENDING,
            initiatesReattach = false,
            newAttempt = false,
            attemptOpenTrigger = trigger
        )
        record.channelId = channelId
        record.reattachDeliveryState = ReattachDeliveryState.DEFERRED
        recordMediaActionDeferred(
            record = record,
            owner = MediaActionOwner.HOST_RESTART,
            reason = reason,
            wakeupBinding = WakeupBinding(
                sourceType = WakeupSourceType.ROUTE_CONVERGED,
                sourceKey = edgeWakeupKey(sessionId, remoteModuleId)
            ),
            trigger = trigger
        )
        onLog(
            "RECOVERY_REATTACH_INBOUND_DEFERRED session=$sessionId remote=$remoteModuleId " +
                "deliveryState=DEFERRED deferredReason=$reason trigger=$trigger " +
                "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration}"
        )
        notifyChanged(sessionId)
    }

    private fun notifyAttemptLineageObservation(
        record: EdgeRecoveryRecord,
        trigger: String,
        supersededFromAttempt: Long? = null
    ) {
        onAttemptLineageObservation(
            record.key.sessionId,
            record.key.remoteModuleId,
            trigger,
            supersededFromAttempt
        )
    }

    private fun formatRecoveryAttemptOpenedLog(
        sessionId: String,
        remoteModuleId: String,
        attemptId: Long,
        initiator: String,
        policy: String,
        startedAt: Long,
        supersededFromAttempt: Long?,
        reason: String,
        previousAttempt: Long?,
        previousPhase: EdgeRecoveryPhase?,
        obligationOpen: Boolean,
        obligationGeneration: Long,
        pathway: String
    ): String =
        "RECOVERY_ATTEMPT_OPENED session=$sessionId remote=$remoteModuleId " +
            "attemptId=$attemptId initiator=$initiator policy=$policy startedAt=$startedAt " +
            "supersededFromAttempt=${supersededFromAttempt ?: "NONE"} reason=$reason " +
            "newAttempt=$attemptId previousAttempt=${previousAttempt ?: "NONE"} " +
            "previousPhase=${previousPhase ?: "NONE"} previousObligationOpen=$obligationOpen " +
            "obligationGen=$obligationGeneration pathway=$pathway"

    private fun logPhaseTransition(
        record: EdgeRecoveryRecord,
        oldPhase: EdgeRecoveryPhase?,
        newPhase: EdgeRecoveryPhase,
        trigger: String
    ) {
        if (oldPhase == newPhase) return
        onLog(
            "RECOVERY_TRANSITION session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
                "old=${oldPhase ?: "NONE"} new=$newPhase trigger=$trigger attempt=${record.recoveryAttemptId} " +
                "obligationGen=${record.obligationGeneration} " +
                "obligationOpen=${record.edgeObligationOpen()} " +
                "pendingCompletion=${record.hasPendingCompletionDecision}"
        )
    }

    /**
     * True when a new ICE failure must start a fresh obligation episode (P1).
     * Active recovery / failed-media residency continues the current episode.
     */
    private fun needsNewObligationEpisode(record: EdgeRecoveryRecord?): Boolean {
        if (record == null) return false
        if (record.phase == EdgeRecoveryPhase.RECOVERED) return true
        if (record.obligationClosedAtMs != null) return true
        return !record.edgeObligationOpen() &&
            !record.phase.isActivelyRecovering() &&
            !record.phase.isFailedMediaRecovery()
    }

    /**
     * Opens a new recovery obligation episode after a healthy edge failure (P1).
     * Does not reuse closed recovery identity or prior attempt context.
     */
    private fun openNewRecoveryObligation(
        key: ConferenceEdgeKey,
        channelId: String,
        phase: EdgeRecoveryPhase,
        initiatesReattach: Boolean,
        trigger: String
    ): EdgeRecoveryRecord {
        cancelDebounce(key)
        cancelWatchdog(key)
        cancelDeadline(key)
        val existing = edges[key]
        val now = clock()
        val newGeneration = (existing?.obligationGeneration ?: 0L) + 1L
        val previousAttempt = existing?.recoveryAttemptId
        val previousPhase = existing?.phase
        val record = EdgeRecoveryRecord(
            key = key,
            phase = phase,
            channelId = channelId.ifBlank { existing?.channelId ?: "" },
            recoveryAttemptId = ++attemptSeq,
            recoveryStartedAtMs = now,
            initiatesReattach = initiatesReattach,
            obligationGeneration = newGeneration,
            obligationOpenedAtMs = now,
            obligationDeadlineAtMs = null,
            obligationClosedAtMs = null,
            obligationCloseReason = null,
            hasPendingCompletionDecision = false
        )
        edges[key] = record
        onLog(
            "RECOVERY_OBLIGATION_OPENED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "obligationGen=$newGeneration attempt=${record.recoveryAttemptId} trigger=$trigger"
        )
        onLog(
            formatRecoveryAttemptOpenedLog(
                sessionId = key.sessionId,
                remoteModuleId = key.remoteModuleId,
                attemptId = record.recoveryAttemptId,
                initiator = resolveRecoveryInitiator(initiatesReattach),
                policy = resolveRecoveryPolicy(initiatesReattach),
                startedAt = record.recoveryStartedAtMs,
                supersededFromAttempt = null,
                reason = trigger,
                previousAttempt = previousAttempt,
                previousPhase = previousPhase,
                obligationOpen = true,
                obligationGeneration = newGeneration,
                pathway = "NEW_OBLIGATION_EPISODE"
            )
        )
        logPhaseTransition(record, previousPhase, phase, trigger)
        return record
    }

    private fun assignMediaActionOwner(
        record: EdgeRecoveryRecord,
        owner: MediaActionOwner,
        mediaActionOwnerModuleId: String = localModuleId,
        parentAttempt: Long? = null,
        supersededByModule: String? = null
    ) {
        if (record.mediaActionOwner.isAssigned() && owner != MediaActionOwner.ABORTED) {
            when {
                record.mediaActionOwner == owner && hasDeferredMediaAction(record) -> Unit
                record.mediaActionOwner == owner -> return
                else -> {
                    onLog(
                        "RECOVERY_MEDIA_OWNER_REJECTED session=${record.key.sessionId} " +
                            "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                            "existing=${record.mediaActionOwner.logLabel()} requested=${owner.logLabel()}"
                    )
                    return
                }
            }
        }
        record.mediaActionOwner = owner
        when (owner) {
            MediaActionOwner.ABORTED -> record.mediaActionDisposition = MediaActionDisposition.ABORTED
            MediaActionOwner.HOST_RESTART,
            MediaActionOwner.PARTICIPANT_REATTACH -> {
                record.mediaActionDisposition = MediaActionDisposition.ACTIVE
                record.deferredReason = null
                record.wakeupBinding = null
            }
            else -> Unit
        }
        onLog(
            "RECOVERY_MEDIA_OWNER_ASSIGNED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "owner=${owner.logLabel()} recoveryOwnerModuleId=$localModuleId " +
                "mediaActionOwnerModuleId=$mediaActionOwnerModuleId " +
                "parentAttempt=${parentAttempt ?: "NONE"} " +
                "supersededByModule=${supersededByModule ?: "NONE"}"
        )
    }

    private fun logHandoffToReattach(
        record: EdgeRecoveryRecord,
        supersededByModule: String,
        attempt: Long
    ) {
        onLog(
            "RECOVERY_HANDOFF_TO_REATTACH session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=$attempt " +
                "supersededByModule=$supersededByModule"
        )
        if (!record.mediaActionOwner.isAssigned()) {
            assignMediaActionOwner(
                record = record,
                owner = MediaActionOwner.PARTICIPANT_REATTACH,
                mediaActionOwnerModuleId = supersededByModule,
                parentAttempt = attempt,
                supersededByModule = supersededByModule
            )
        }
    }

    private fun hasParticipantHandoffPending(record: EdgeRecoveryRecord): Boolean =
        record.mediaActionOwner == MediaActionOwner.PARTICIPANT_REATTACH ||
            record.phase == EdgeRecoveryPhase.REATTACH_REQUESTED ||
            record.phase == EdgeRecoveryPhase.REATTACH_ACCEPTED

    private fun recordMediaActionDeferred(
        record: EdgeRecoveryRecord,
        owner: MediaActionOwner,
        reason: DeferredReason,
        wakeupBinding: WakeupBinding,
        trigger: String,
        mediaActionOwnerModuleId: String = localModuleId
    ) {
        record.mediaActionOwner = owner
        record.mediaActionDisposition = MediaActionDisposition.DEFERRED
        record.deferredReason = reason
        record.wakeupBinding = wakeupBinding
        onLog(
            "RECOVERY_MEDIA_OWNER_ASSIGNED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "owner=${owner.logLabel()} recoveryOwnerModuleId=$localModuleId " +
                "mediaActionOwnerModuleId=$mediaActionOwnerModuleId " +
                "parentAttempt=NONE supersededByModule=NONE"
        )
        onLog(
            "RECOVERY_MEDIA_ACTION_DEFERRED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "owner=${owner.logLabel()} disposition=DEFERRED " +
                "deferredReason=$reason trigger=$trigger " +
                "wakeupBinding=${wakeupBinding.logLabel()}"
        )
        onLog(
            "RECOVERY_WAKEUP_ARMED session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${wakeupBinding.sourceType} " +
                "wakeupBinding=${wakeupBinding.logLabel()} deferredReason=$reason"
        )
    }

    private fun clearMediaActionDeferral(record: EdgeRecoveryRecord) {
        record.mediaActionDisposition = MediaActionDisposition.UNASSIGNED
        record.deferredReason = null
        record.wakeupBinding = null
        record.deferredGateBlockReason = null
        record.iceRestartIntentId = null
    }

    private fun hasDeferredMediaAction(record: EdgeRecoveryRecord): Boolean =
        record.mediaActionDisposition == MediaActionDisposition.DEFERRED &&
            record.mediaActionOwner.isAssigned()

    private fun isDeferredIceRestartIntent(record: EdgeRecoveryRecord): Boolean =
        hasDeferredMediaAction(record) &&
            record.deferredReason == DeferredReason.NEGOTIATION_SETTLING &&
            record.wakeupBinding?.sourceType == WakeupSourceType.NEGOTIATION_CAN_EXECUTE

    /**
     * Commit Seam Trace: deferred ICE-restart intent id awaiting NEGOTIATION_CAN_EXECUTE, if any.
     * Coordinator stamps the same intentId on capability emission for lifecycle join.
     */
    fun pendingIceRestartIntentId(sessionId: String, remoteModuleId: String): String? {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return null
        if (!isDeferredIceRestartIntent(record)) return null
        return record.iceRestartIntentId
    }

    private fun allocateIceRestartIntentId(record: EdgeRecoveryRecord): String {
        val existing = record.iceRestartIntentId
        if (existing != null) return existing
        val id = "R${iceRestartIntentSeq.incrementAndGet()}"
        record.iceRestartIntentId = id
        return id
    }

    /**
     * INV-NEG-003: deferred ICE Restart Intent MUST NOT silently vanish across
     * SUPERSEDE / CLOSE / generation change — emit auditable EXPIRED → STALE_DISCARD.
     * Caller clears fields (or replaces the record) after this audit.
     */
    private fun expireDeferredIceRestartIntent(record: EdgeRecoveryRecord, cause: String) {
        if (!hasDeferredMediaAction(record)) return
        val binding = record.wakeupBinding
        val intentId = record.iceRestartIntentId ?: "NONE"
        if (binding != null) {
            onLog(
                "RECOVERY_WAKEUP_EXPIRED session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} intentId=$intentId trigger=${binding.sourceType} " +
                    "wakeupBinding=${binding.logLabel()} cause=$cause"
            )
        }
        if (!isDeferredIceRestartIntent(record)) return
        // Step A-1: STALE_DISCARD must name why — not a bare STALE (OBLIGATION_CLOSED |
        // SUPERSEDED | RELEASE_MISSING). RELEASE_MISSING = never got the bound release fact.
        val terminalReason = when {
            cause.startsWith("SUPERSEDE") || cause.startsWith("ADMIT_SUCCESSOR") -> "SUPERSEDED"
            cause.startsWith("OBLIGATION_CLOSE") || cause.startsWith("DRAIN_OBLIGATION") ->
                "OBLIGATION_CLOSED"
            cause.startsWith("DRAIN_STALE") || cause.startsWith("DRAIN_ALREADY") -> "RELEASE_MISSING"
            else -> "RELEASE_MISSING"
        }
        onLog(
            "RECOVERY_ICE_RESTART_INTENT_TERMINAL session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "intentId=$intentId obligationGen=${record.obligationGeneration} " +
                "terminal=STALE_DISCARD reason=$terminalReason expireCause=$cause " +
                "gateBlock=${record.deferredGateBlockReason ?: "UNKNOWN"} " +
                "wakeup=${record.wakeupBinding?.sourceType ?: "NONE"}"
        )
        onLog(
            "RECOVERY_ICE_RESTART_INTENT_EXPIRED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "intentId=$intentId obligationGen=${record.obligationGeneration} disposition=EXPIRED " +
                "terminal=STALE_DISCARD cause=$cause"
        )
    }

    /**
     * INV-NEG-005 / INV-REC-025: Coordinator routes NEGOTIATION_CAN_EXECUTE here after capability
     * rising-edge. Re-validates attempt/gen/obligation/intent/gate before dispatch.
     */
    fun drainPendingIceRestart(sessionId: String, remoteModuleId: String) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        if (!isDeferredIceRestartIntent(record)) return
        if (!record.edgeObligationOpen()) {
            expireDeferredIceRestartIntent(record, "DRAIN_OBLIGATION_CLOSED")
            clearMediaActionDeferral(record)
            return
        }
        if (record.iceRestartIssued) {
            expireDeferredIceRestartIntent(record, "DRAIN_ALREADY_ISSUED")
            clearMediaActionDeferral(record)
            return
        }
        val attemptId = record.recoveryAttemptId
        val obligationGen = record.obligationGeneration
        val intentId = record.iceRestartIntentId ?: "NONE"
        val probe = probeIceRestartGate(sessionId, remoteModuleId)
        if (!probe.executable) {
            onLog(
                "RECOVERY_ICE_RESTART_DRAIN_HELD session=$sessionId remote=$remoteModuleId " +
                    "attempt=$attemptId intentId=$intentId obligationGen=$obligationGen " +
                    "reason=gate_not_executable " +
                    "gateBlock=${probe.blockReason ?: "UNKNOWN"} " +
                    "signalingState=${probe.signalingState ?: "UNKNOWN"}"
            )
            return
        }
        onLog(
            "RECOVERY_WAKEUP_FIRED session=$sessionId edge=$remoteModuleId " +
                "attempt=$attemptId intentId=$intentId trigger=NEGOTIATION_CAN_EXECUTE " +
                "wakeupBinding=${record.wakeupBinding?.logLabel()}"
        )
        val still = edges[key] ?: return
        if (still.recoveryAttemptId != attemptId ||
            still.obligationGeneration != obligationGen ||
            !still.edgeObligationOpen() ||
            still.iceRestartIssued
        ) {
            expireDeferredIceRestartIntent(still, "DRAIN_STALE_LINEAGE")
            clearMediaActionDeferral(still)
            return
        }
        onLog(
            "RECOVERY_ICE_RESTART_INTENT_TERMINAL session=$sessionId remote=$remoteModuleId " +
                "attempt=$attemptId intentId=$intentId obligationGen=$obligationGen " +
                "terminal=EXECUTED reason=DRAIN_AFTER_NEGOTIATION_CAN_EXECUTE " +
                "gateBlock=${still.deferredGateBlockReason ?: "NONE"}"
        )
        clearMediaActionDeferral(still)
        // Keep intentId through DISPATCH audit, then drop (deferral fields already cleared).
        still.iceRestartIntentId = if (intentId == "NONE") null else intentId
        issueBoundedIceRestart(still, RecoveryReason.NETWORK_RECOVERY)
        still.iceRestartIntentId = null
    }

    /**
     * ADR-0022 R28-K / INV-REC-001: attempt failure timers MUST NOT run while required
     * recovery capability is unavailable (route, media dispatch gate, deferred action).
     */
    private fun isCapabilityBlockingAttemptClock(record: EdgeRecoveryRecord): Boolean {
        if (hasDeferredMediaAction(record)) {
            when (record.deferredReason) {
                DeferredReason.ROUTE_NOT_READY,
                DeferredReason.MEDIA_NOT_READY,
                DeferredReason.AUTHORITY_NOT_READY,
                DeferredReason.NEGOTIATION_SETTLING -> return true
                null -> Unit
            }
        }
        if (
            !record.initiatesReattach &&
            !record.iceRestartIssued &&
            (record.mediaActionOwner == MediaActionOwner.PENDING || hasDeferredMediaAction(record)) &&
            !canDispatchRecoveryMediaAction(record.key.sessionId, record.key.remoteModuleId)
        ) {
            return true
        }
        return false
    }

    /**
     * Appendix C-2: recovery authority claims media action when no participant handoff owns it.
     * Invoked after EDGE_STARTED and on material re-evaluate when still PENDING or DEFERRED.
     */
    private fun resolveMediaActionOwner(
        record: EdgeRecoveryRecord,
        recoveryReason: RecoveryReason,
        immediate: Boolean,
        trigger: String,
        mediaReady: Boolean? = null
    ) {
        if (record.initiatesReattach) return
        if (record.mediaActionOwner.isAssigned() && !hasDeferredMediaAction(record)) return
        if (!record.phase.isActivelyRecovering()) return
        val key = record.key
        if (hasParticipantHandoffPending(record)) {
            recordMediaActionDeferred(
                record = record,
                owner = MediaActionOwner.PARTICIPANT_REATTACH,
                reason = DeferredReason.MEDIA_NOT_READY,
                wakeupBinding = WakeupBinding(
                    sourceType = WakeupSourceType.ROUTE_CONVERGED,
                    sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                ),
                trigger = "PARTICIPANT_HANDOFF_PENDING:$trigger"
            )
            return
        }
        val dispatchReady = mediaReady ?: canDispatchRecoveryMediaAction(key.sessionId, key.remoteModuleId)
        if (!immediate && !dispatchReady) {
            recordMediaActionDeferred(
                record = record,
                owner = MediaActionOwner.HOST_RESTART,
                reason = DeferredReason.MEDIA_NOT_READY,
                wakeupBinding = WakeupBinding(
                    sourceType = WakeupSourceType.ROUTE_CONVERGED,
                    sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                ),
                trigger = trigger
            )
            return
        }
        onLog(
            "RECOVERY_MEDIA_ACTION_ASSIGNMENT session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} owner=HOST_RESTART trigger=$trigger"
        )
        issueBoundedIceRestart(record, recoveryReason)
    }

    private fun logObligationCloseRequested(
        record: EdgeRecoveryRecord,
        reason: ObligationCloseReason,
        closeEvidence: String?
    ) {
        val key = record.key
        val iceConnected = isIceConnected(key.sessionId, key.remoteModuleId)
        onLog(
            "RECOVERY_OBLIGATION_CLOSE_REQUESTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} reason=$reason phase=${record.phase} " +
                "evidence=${closeEvidence ?: "NONE"} mediaRestored=${record.mediaRestored} " +
                "mediaReady=${record.mediaRestored || iceConnected} iceConnected=$iceConnected " +
                "controlPlaneStarted=${record.controlPlaneStarted()}"
        )
    }

    private fun logCompletionEvidenceAccepted(
        record: EdgeRecoveryRecord,
        evidence: String,
        snapshot: EdgeReachabilitySnapshot? = null
    ) {
        val key = record.key
        val iceConnected = isIceConnected(key.sessionId, key.remoteModuleId)
        val mediaReady = record.mediaRestored || iceConnected
        val snapshotFields = snapshot?.let {
            " mediaRouteConnected=${it.mediaRouteConnected} authorityReachable=${it.authorityReachable}"
        } ?: ""
        onLog(
            "RECOVERY_COMPLETION_EVIDENCE_ACCEPTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} evidence=$evidence mediaReady=$mediaReady " +
                "mediaRestored=${record.mediaRestored} iceConnected=$iceConnected " +
                "controlPlaneStarted=${record.controlPlaneStarted()} phase=${record.phase}" +
                snapshotFields
        )
    }

    private fun completionEvidenceFromReachability(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        trigger: RecoveryReevaluateTrigger
    ): String = when {
        isIceConnected(record.key.sessionId, record.key.remoteModuleId) -> "ICE_CONNECTED"
        record.mediaRestored -> "MEDIA_RESTORED"
        snapshot.mediaRouteConnected && snapshot.authorityReachable -> "ROUTE_CONVERGED"
        else -> trigger.name
    }

    private fun closeObligation(
        record: EdgeRecoveryRecord,
        reason: ObligationCloseReason,
        closeEvidence: String? = null
    ) {
        if (record.obligationClosedAtMs != null) return
        logObligationCloseRequested(record, reason, closeEvidence)
        expireDeferredIceRestartIntent(record, "OBLIGATION_CLOSE:$reason")
        cancelDeadline(record.key)
        record.obligationClosedAtMs = clock()
        record.obligationCloseReason = reason
        record.hasPendingCompletionDecision = false
        onLog(
            "RECOVERY_OBLIGATION_CLOSED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} reason=$reason"
        )
    }

    /**
     * Enter failed-media residency: attempt terminal, obligation stays OPEN, stamp deadline.
     * When [explicitAbort] is true, emit EXPLICIT_RECOVERY_ABORT instead of FAILED_MEDIA_RECOVERY
     * (ADR-0022 Appendix C-1).
     */
    private fun enterFailedMediaResidency(
        record: EdgeRecoveryRecord,
        reason: String,
        explicitAbort: Boolean = false
    ) {
        val oldPhase = record.phase
        record.phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY
        logPhaseTransition(record, oldPhase, record.phase, if (explicitAbort) "EXPLICIT_ABORT:$reason" else "FAILED_MEDIA:$reason")
        val terminalAt = clock()
        record.obligationDeadlineAtMs = terminalAt + observationWindowMs
        if (explicitAbort) {
            onLog(
                "EXPLICIT_RECOVERY_ABORT session=${record.key.sessionId} " +
                    "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                    "reason=$reason deadlineAt=${record.obligationDeadlineAtMs}"
            )
            notifyAttemptLineageObservation(record, "explicit_recovery_abort")
        } else {
            onLog(
                "FAILED_MEDIA_RECOVERY session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} reason=$reason deadlineAt=${record.obligationDeadlineAtMs}"
            )
            notifyAttemptLineageObservation(record, "failed_media_recovery")
        }
        scheduleObligationDeadline(record)
    }

    private fun enterFailedRequiresUserAction(record: EdgeRecoveryRecord) {
        record.phase = EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION
        val terminalAt = clock()
        record.obligationDeadlineAtMs = terminalAt + observationWindowMs
        scheduleObligationDeadline(record)
    }

    private fun scheduleObligationDeadline(record: EdgeRecoveryRecord) {
        val key = record.key
        cancelDeadline(key)
        val deadlineAt = record.obligationDeadlineAtMs ?: return
        val delayMs = (deadlineAt - clock()).coerceAtLeast(0L)
        val future = scheduler.schedule({
            val current = edges[key] ?: return@schedule
            if (current.obligationClosedAtMs != null) return@schedule
            // Wall-clock delay already encodes observationWindow; do not re-gate on [clock]
            // so injected test clocks that do not advance still close on schedule.
            if (current.obligationDeadlineAtMs == null) return@schedule
            // Only close from failed-media residency. Active Attempt N+1 after SUPERSEDE
            // must not be killed by a stale timer from the prior failed entry.
            if (!current.phase.isFailedMediaRecovery()) return@schedule
            closeObligation(current, ObligationCloseReason.OBLIGATION_DEADLINE, "OBLIGATION_DEADLINE")
            notifyChanged(key.sessionId)
        }, delayMs, TimeUnit.MILLISECONDS)
        deadlineTimers[key] = future
    }

    private fun cancelDeadline(key: ConferenceEdgeKey) {
        deadlineTimers.remove(key)?.cancel(false)
    }

    /**
     * Capability materiality notification from Coordinator (ADR-0022 R28-G).
     * Fact writers MUST NOT call this — only [TalkbackCoordinator] after signature comparison.
     *
     * §13.2.4 Gap-2: when obligation is CLOSED, fresh [RecoveryResurrectionEvidence] may admit a
     * Successor Obligation Episode. OPEN path MUST NOT bump [obligationGeneration].
     */
    fun onRecoveryReachabilityChanged(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature,
        capabilityBefore: RecoveryCapabilitySignature?,
        trigger: RecoveryReevaluateTrigger,
        resurrectionEvidence: RecoveryResurrectionEvidence? = null
    ) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        if (resurrectionEvidence != null &&
            (trigger != RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED ||
                resurrectionEvidence.kind != RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED)
        ) {
            onLog(
                "RECOVERY_INVALID_EVIDENCE_BINDING session=$sessionId edge=$remoteModuleId " +
                    "trigger=$trigger evidenceKind=${resurrectionEvidence.kind} " +
                    "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration}"
            )
            return
        }
        if (record.edgeObligationOpen()) {
            reevaluateOpenObligation(
                record = record,
                snapshot = snapshot,
                signature = signature,
                capabilityBefore = capabilityBefore,
                trigger = trigger
            )
            return
        }
        if (isFreshRemoteModuleRecoveredEvidence(record, resurrectionEvidence)) {
            admitSuccessorObligationEpisode(
                record = record,
                channelId = channelId,
                signature = signature,
                evidence = resurrectionEvidence!!
            )
            return
        }
        onLog(
            "RECOVERY_REACHABILITY_IGNORED session=$sessionId edge=$remoteModuleId " +
                "trigger=$trigger reason=no_open_obligation_or_fresh_resurrection " +
                "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration}"
        )
    }

    /**
     * INV-REC-022: lineage / completion terminal authority is attempt-scoped within the current
     * obligation generation. Historical attempts / closed gens MUST NOT terminate successor state.
     * An already-closed obligation (e.g. [ObligationCloseReason.OBLIGATION_DEADLINE]) also rejects
     * late RECOVERED facts so they cannot rewrite [EdgeRecoveryPhase] and poison successor admission.
     */
    fun canMarkLineageRecovered(
        sessionId: String,
        remoteModuleId: String,
        factAttemptId: Long,
        factObligationGeneration: Long
    ): Boolean {
        val current = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        if (current.obligationClosedAtMs != null) return false
        return factAttemptId == current.recoveryAttemptId &&
            factObligationGeneration == current.obligationGeneration
    }

    /**
     * Test seam: apply [markRecovered] against the live edge record (same object a racing
     * completion callback would hold after exclusive close).
     */
    internal fun applyMarkRecoveredForTest(
        sessionId: String,
        remoteModuleId: String,
        evidence: String = "ICE_CONNECTED"
    ) {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return
        markRecovered(record, evidence)
    }

    private fun reevaluateOpenObligation(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature,
        capabilityBefore: RecoveryCapabilitySignature?,
        trigger: RecoveryReevaluateTrigger
    ) {
        val sessionId = record.key.sessionId
        val remoteModuleId = record.key.remoteModuleId
        if (hasDeferredWakeupForTrigger(sessionId, remoteModuleId, trigger)) {
            onLog(
                "RECOVERY_WAKEUP_FIRED session=$sessionId edge=$remoteModuleId " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                    "wakeupBinding=${record.wakeupBinding?.logLabel()}"
            )
        }
        val controlPlane = record.controlPlaneStarted()
        onLog(
            "RECOVERY_REEVALUATE session=$sessionId edge=$remoteModuleId " +
                "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                "capabilityBefore=${capabilityBefore?.formatCapabilityLabel() ?: "NONE"} " +
                "capabilityAfter=${signature.formatCapabilityLabel()} " +
                "controlPlaneStarted=$controlPlane"
        )
        runCompletionEvaluationStub(record, snapshot, signature, trigger)
        notifyChanged(sessionId)
    }

    private fun isFreshRemoteModuleRecoveredEvidence(
        record: EdgeRecoveryRecord,
        evidence: RecoveryResurrectionEvidence?
    ): Boolean {
        if (evidence == null) return false
        if (evidence.kind != RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED) return false
        val closedAt = record.obligationClosedAtMs ?: return false
        if (evidence.observedAtMs <= closedAt) return false
        // Edge Lifecycle ACTIVE == record present (caller resolved).
        // "unhealthy / no media-complete" == not RECOVERED. mediaRestored is attempt-scoped
        // media-plane evidence and MUST NOT block successor admission after OBLIGATION_DEADLINE.
        if (record.phase == EdgeRecoveryPhase.RECOVERED) return false
        return true
    }

    /**
     * ADR-0022 §13.2.4: admit Successor Obligation Episode (gen+1 + first attempt).
     * B-13.2.4-1: admission ≠ beginRecovery fusion — resolve/dispatch separately (M1, INV-REC-023).
     */
    private fun admitSuccessorObligationEpisode(
        record: EdgeRecoveryRecord,
        channelId: String,
        signature: RecoveryCapabilitySignature,
        evidence: RecoveryResurrectionEvidence
    ): SuccessorObligationAdmission {
        val key = record.key
        val initiatesReattach = record.initiatesReattach
        val previousGen = record.obligationGeneration
        val previousAttempt = record.recoveryAttemptId
        // INV-NEG-003: audit predecessor intent before record replacement (G2 does not inherit).
        expireDeferredIceRestartIntent(record, "ADMIT_SUCCESSOR")
        val admitted = openNewRecoveryObligation(
            key = key,
            channelId = channelId.ifBlank { record.channelId },
            phase = EdgeRecoveryPhase.RECOVERY_PENDING,
            initiatesReattach = initiatesReattach,
            trigger = "ADMIT_SUCCESSOR_OBLIGATION_EPISODE"
        )
        onLog(
            "ADMIT_SUCCESSOR_OBLIGATION_EPISODE session=${key.sessionId} remote=${key.remoteModuleId} " +
                "priorGen=$previousGen priorAttempt=$previousAttempt " +
                "obligationGen=${admitted.obligationGeneration} attempt=${admitted.recoveryAttemptId} " +
                "evidenceKind=${evidence.kind} observedAtMs=${evidence.observedAtMs}"
        )
        // M1: same resolve path as R1 / SUPERSEDE; immediate=false; watchdog only after dispatch.
        admitted.mediaActionOwner = MediaActionOwner.PENDING
        clearMediaActionDeferral(admitted)
        val recoveryReason = RecoveryReason.NETWORK_RECOVERY
        if (admitted.initiatesReattach) {
            if (
                RecoveryAction.DISPATCH_REATTACH in signature.permittedActions &&
                !admitted.controlPlaneStarted()
            ) {
                applyReattachDispatchOutcome(
                    record = admitted,
                    outcome = onRequestReattach(key.sessionId, admitted.channelId, key.remoteModuleId),
                    trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED
                )
                if (admitted.phase == EdgeRecoveryPhase.REATTACH_REQUESTED) {
                    scheduleWatchdog(admitted)
                }
            } else {
                recordMediaActionDeferred(
                    record = admitted,
                    owner = MediaActionOwner.PARTICIPANT_REATTACH,
                    reason = DeferredReason.MEDIA_NOT_READY,
                    wakeupBinding = WakeupBinding(
                        sourceType = WakeupSourceType.ROUTE_CONVERGED,
                        sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                    ),
                    trigger = "ADMIT_SUCCESSOR:${RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED}"
                )
            }
        } else {
            resolveMediaActionOwner(
                record = admitted,
                recoveryReason = recoveryReason,
                immediate = false,
                trigger = "ADMIT_SUCCESSOR:${RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED}"
            )
            // issueBoundedIceRestart schedules watchdog on dispatch; deferred must not (INV-REC-023).
        }
        notifyChanged(key.sessionId)
        return SuccessorObligationAdmission(
            obligationGeneration = admitted.obligationGeneration,
            recoveryAttemptId = admitted.recoveryAttemptId
        )
    }

    fun isChannelCancelled(channelId: String): Boolean {
        val expiresAt = cancelledChannels[channelId] ?: return false
        if (clock() > expiresAt) {
            cancelledChannels.remove(channelId)
            return false
        }
        return true
    }

    fun isSessionCancelled(sessionId: String): Boolean {
        val expiresAt = cancelledSessions[sessionId] ?: return false
        if (clock() > expiresAt) {
            cancelledSessions.remove(sessionId)
            return false
        }
        return true
    }

    fun onIceStateChanged(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        iceState: String,
        eligibility: EdgeRecoveryEligibility,
        initiatesReattach: Boolean
    ) {
        if (isSessionCancelled(sessionId)) {
            logRecoveryDecision(
                sessionId = sessionId,
                edge = remoteModuleId,
                trigger = RecoveryDecisionTrigger.SESSION_CANCELLED,
                recoveryReason = RecoveryReason.SESSION_CANCELLED,
                terminationReason = RecoveryTerminationReason.CONFERENCE_TERMINATED,
                policy = RecoveryDecisionPolicy.NO_RECOVERY,
                approved = false,
                rejectReason = "session_cancelled",
                attempt = edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.recoveryAttemptId
            )
            onLog("RECOVERY_EVENT_DROPPED session=$sessionId remote=$remoteModuleId reason=session_cancelled")
            return
        }
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        if (IceConnectivity.isConnected(iceState)) {
            // Always drop debounce suspicion on CONNECTED (R28-H.2); onIceConnected decides HEALTHY vs evaluation.
            cancelDebounce(key)
            onIceConnected(sessionId, remoteModuleId)
            return
        }
        if (iceState != "DISCONNECTED" && iceState != "FAILED") return

        val record = edges[key]
        record?.mediaRestored = false
        if (record?.phase == EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY ||
            record?.phase == EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION ||
            record?.phase == EdgeRecoveryPhase.FAILED_IDENTITY_MISMATCH ||
            record?.phase == EdgeRecoveryPhase.FAILED_STALE_LINEAGE
        ) {
            return
        }

        if (iceState == "FAILED") {
            cancelDebounce(key)
            val existing = edges[key]
            if (existing?.hasActiveAttempt() == true &&
                existing.phase != EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING
            ) {
                onLog(
                    "RECOVERY_EVENT_ATTACHED_EXISTING_ATTEMPT session=$sessionId remote=$remoteModuleId " +
                        "attempt=${existing.recoveryAttemptId} trigger=ICE_FAILED"
                )
                return
            }
            beginRecovery(
                key,
                channelId,
                eligibility,
                initiatesReattach,
                immediate = true,
                trigger = RecoveryDecisionTrigger.ICE_FAILED
            )
            return
        }

        val existing = edges[key]
        if (existing?.phase == EdgeRecoveryPhase.REATTACH_REQUESTED ||
            existing?.phase == EdgeRecoveryPhase.REATTACH_ACCEPTED ||
            existing?.phase == EdgeRecoveryPhase.ICE_RESTARTING
        ) {
            return
        }

        cancelDebounce(key)
        val existingBeforeDebounce = edges[key]
        if (needsNewObligationEpisode(existingBeforeDebounce)) {
            openNewRecoveryObligation(
                key,
                channelId,
                EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING,
                initiatesReattach,
                RecoveryDecisionTrigger.ICE_DISCONNECTED.name
            )
        } else {
            upsertEdge(
                key,
                channelId,
                EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING,
                initiatesReattach = initiatesReattach,
                attemptOpenTrigger = RecoveryDecisionTrigger.ICE_DISCONNECTED.name
            )
        }
        val debounce = scheduler.schedule({
            val current = edges[key]
            if (current?.hasActiveAttempt() == true &&
                current.phase != EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING
            ) {
                return@schedule
            }
            beginRecovery(
                key,
                channelId,
                eligibility,
                initiatesReattach,
                immediate = false,
                trigger = RecoveryDecisionTrigger.ICE_DISCONNECTED
            )
        }, debounceMs, TimeUnit.MILLISECONDS)
        debounceTimers[key] = debounce
    }

    /**
     * Connectivity-plane only. Callers from Membership / Join / Invite MUST NOT use this.
     * Illegal [RecoverySource] or [RecoveryReason] is rejected with NON_CONNECTIVITY_TRIGGER.
     */
    fun onRecoveryReattachAccepted(
        sessionId: String,
        remoteModuleId: String,
        recoveryReason: RecoveryReason = RecoveryReason.NETWORK_RECOVERY,
        source: RecoverySource = RecoverySource.ICE_MONITOR
    ) {
        if (!isConnectivityRecoverySource(source) || !isConnectivityRecoveryReason(recoveryReason)) {
            logRecoveryDecision(
                sessionId = sessionId,
                edge = remoteModuleId,
                trigger = RecoveryDecisionTrigger.REATTACH_ACCEPTED,
                recoveryReason = RecoveryReason.NON_CONNECTIVITY,
                terminationReason = RecoveryTerminationReason.UNKNOWN,
                policy = RecoveryDecisionPolicy.NO_RECOVERY,
                approved = false,
                rejectReason = "NON_CONNECTIVITY_TRIGGER",
                attempt = edges[ConferenceEdgeKey(sessionId, remoteModuleId)]?.recoveryAttemptId
            )
            return
        }
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val existing = edges[key]
        // Duplicate only when this attempt already accepted inbound reattach.
        // Host-owned ICE_RESTARTING without inbound accept MAY be superseded (ADR-0022 C-1.1 / #103003).
        if (existing?.recoveryViaInboundReattach == true &&
            existing.phase.isActivelyRecovering()
        ) {
            logRecoveryDecision(
                sessionId = sessionId,
                edge = remoteModuleId,
                trigger = RecoveryDecisionTrigger.REATTACH_ACCEPTED,
                recoveryReason = recoveryReason,
                terminationReason = RecoveryTerminationReason.UNKNOWN,
                policy = RecoveryDecisionPolicy.ICE_RESTART_ONLY,
                approved = false,
                rejectReason = "duplicate_reattach_accepted",
                attempt = existing.recoveryAttemptId
            )
            return
        }
        val record = existing ?: run {
            upsertEdge(
                key,
                channelId = "",
                phase = EdgeRecoveryPhase.REATTACH_ACCEPTED,
                initiatesReattach = false,
                attemptOpenTrigger = RecoveryDecisionTrigger.REATTACH_ACCEPTED.name
            )
            edges[key]!!
        }
        if (isSessionCancelled(sessionId)) {
            onLog("RECOVERY_EVENT_DROPPED session=$sessionId remote=$remoteModuleId reason=session_cancelled")
            return
        }
        cancelDebounce(key)
        // #79 / ADR-0022 P1: ACCEPTED supersedes the prior attempt and cancels its watchdog.
        // New attempt owns a fresh budget starting at ICE-restarting / accepted lifecycle.
        if (existing != null) {
            val priorAttempt = record.recoveryAttemptId
            logHandoffToReattach(record, remoteModuleId, priorAttempt)
            supersedeAttempt(
                record,
                trigger = "REATTACH_INBOUND",
                scheduleNewWatchdog = false
            )
            onLog(
                "RECOVERY_DECISION session=$sessionId edge=$remoteModuleId " +
                    "attempt=${record.recoveryAttemptId} priorAttempt=$priorAttempt " +
                    "trigger=${RecoveryDecisionTrigger.REATTACH_ACCEPTED} " +
                    "decision=SUPERSEDED approved=true"
            )
        }
        record.phase = EdgeRecoveryPhase.REATTACH_ACCEPTED
        record.recoveryViaInboundReattach = true
        record.reattachDeliveryState = ReattachDeliveryState.ACCEPTED
        logPhaseTransition(record, existing?.phase, record.phase, "REATTACH_ACCEPTED")
        logRecoveryDecision(
            sessionId = sessionId,
            edge = remoteModuleId,
            trigger = RecoveryDecisionTrigger.REATTACH_ACCEPTED,
            recoveryReason = recoveryReason,
            terminationReason = RecoveryTerminationReason.UNKNOWN,
            policy = RecoveryDecisionPolicy.ICE_RESTART_ONLY,
            approved = true,
            rejectReason = null,
            attempt = record.recoveryAttemptId
        )
        onLog(
            "RECOVERY_REATTACH_ACCEPTED session=$sessionId remote=$remoteModuleId " +
                "attempt=${record.recoveryAttemptId} recoveryReason=$recoveryReason source=$source"
        )
        issueBoundedIceRestart(record, recoveryReason)
        // Soak gap (#83): ICE may already be CONNECTED with no fresh CONNECTED event.
        // Probe and feed the media fact into completion evaluation — never shortcut RECOVERED.
        if (isIceConnected(sessionId, remoteModuleId)) {
            record.mediaRestored = true
            notifyAttemptLineageObservation(record, "transport_recovered_ice_connected")
            runIceRestorationCompletionEvaluation(record)
        }
        notifyChanged(sessionId)
    }

    @Deprecated("Use onRecoveryReattachAccepted — Membership must not call Recovery", ReplaceWith("onRecoveryReattachAccepted(sessionId, remoteModuleId, recoveryReason)"))
    fun onReattachAccepted(
        sessionId: String,
        remoteModuleId: String,
        recoveryReason: RecoveryReason = RecoveryReason.UNKNOWN
    ) {
        onRecoveryReattachAccepted(
            sessionId,
            remoteModuleId,
            recoveryReason,
            source = RecoverySource.JOIN_HANDLER
        )
    }

    fun onReattachRequested(sessionId: String, channelId: String, remoteModuleId: String) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        record.phase = EdgeRecoveryPhase.REATTACH_REQUESTED
        record.channelId = channelId
        onLog(
            "RECOVERY_REATTACH_REQUESTED session=$sessionId remote=$remoteModuleId " +
                "attempt=${record.recoveryAttemptId}"
        )
        notifyChanged(sessionId)
    }

    /**
     * Coordinator routing entry for outbound REATTACH reject payloads (ADR-0022 Appendix D / R28-L).
     * Parses [reasonPayload] and resolves lineage from the outbound dispatch snapshot on the edge.
     */
    fun onConferenceRecoveryReattachOutboundReject(
        sessionId: String,
        remoteModuleId: String,
        reasonPayload: String
    ): Boolean {
        val reason = OutboundReattachRejectReason.fromPayload(reasonPayload) ?: return false
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        val rejectedAttemptId = record.outboundDispatchAttemptId ?: record.recoveryAttemptId
        val rejectedObligationGeneration = record.outboundDispatchObligationGeneration
            ?: record.obligationGeneration
        return onRecoveryReattachOutboundRejected(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            rejectedAttemptId = rejectedAttemptId,
            rejectedObligationGeneration = rejectedObligationGeneration,
            reason = reason
        )
    }

    /**
     * Requester-side REATTACH reject (ADR-0022 R28-L INV-REC-007).
     * Reject is a reevaluate trigger — never direct [markRecovered].
     */
    fun onRecoveryReattachOutboundRejected(
        sessionId: String,
        remoteModuleId: String,
        rejectedAttemptId: Long,
        rejectedObligationGeneration: Long,
        reason: OutboundReattachRejectReason
    ): Boolean {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return false
        if (rejectedAttemptId != record.recoveryAttemptId ||
            rejectedObligationGeneration != record.obligationGeneration
        ) {
            onLog(
                "STALE_REATTACH_REJECT_IGNORED session=$sessionId remote=$remoteModuleId " +
                    "reason=$reason rejectedAttempt=$rejectedAttemptId " +
                    "rejectedObligationGen=$rejectedObligationGeneration " +
                    "currentAttempt=${record.recoveryAttemptId} " +
                    "currentObligationGen=${record.obligationGeneration}"
            )
            return true
        }
        when (reason) {
            OutboundReattachRejectReason.OBLIGATION_CLOSED -> {
                record.reattachDeliveryState = ReattachDeliveryState.REJECTED
                onLog(
                    "RECOVERY_REATTACH_OUTBOUND_REJECTED session=$sessionId remote=$remoteModuleId " +
                        "reason=$reason attempt=${record.recoveryAttemptId} " +
                        "obligationGen=${record.obligationGeneration}"
                )
                onLog(
                    "RECOVERY_REEVALUATE_REQUIRED session=$sessionId edge=$remoteModuleId " +
                        "attempt=${record.recoveryAttemptId} trigger=REATtach_OUTBOUND_REJECTED " +
                        "rejectReason=$reason"
                )
                record.hasPendingCompletionDecision = true
                notifyChanged(sessionId)
            }
        }
        return true
    }

    fun onReattachRejected(
        sessionId: String,
        remoteModuleId: String,
        reason: String,
        recoverable: Boolean
    ) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        cancelDebounce(key)
        cancelWatchdog(key)
        record.phase = when {
            recoverable && !record.epochRefreshUsed -> {
                record.epochRefreshUsed = true
                EdgeRecoveryPhase.RECOVERY_PENDING
            }
            reason.contains("ENDPOINT", ignoreCase = true) ->
                EdgeRecoveryPhase.FAILED_IDENTITY_MISMATCH
            reason.contains("EPOCH", ignoreCase = true) ->
                EdgeRecoveryPhase.FAILED_STALE_LINEAGE
            else -> {
                enterFailedRequiresUserAction(record)
                EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION
            }
        }
        onLog(
            "RECOVERY_REATTACH_REJECTED session=$sessionId remote=$remoteModuleId " +
                "reason=$reason phase=${record.phase}"
        )
        notifyChanged(sessionId)
    }

    fun onIceConnected(sessionId: String, remoteModuleId: String) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        cancelDebounce(key)
        // R28-H.2: debouncing is suspicion only — reconnect clears HEALTHY, never starts recovery / RECOVERED.
        if (record.phase == EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING) {
            clearDebouncingSuspicion(record)
            notifyChanged(sessionId)
            return
        }
        // Terminal monotonicity: late ICE after CLOSED(RECOVERED) must not reopen / rewrite phase
        // (soak gap2-casea: RECOVERED → controlPlaneStarted=false → ICE_RESTARTING poisoned UI).
        if (record.phase == EdgeRecoveryPhase.RECOVERED && !record.edgeObligationOpen()) {
            onLog(
                "IGNORE_LATE_ICE_AFTER_RECOVERED session=$sessionId remote=$remoteModuleId " +
                    "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration} " +
                    "closeReason=${record.obligationCloseReason}"
            )
            return
        }
        // No open recovery obligation: idle CONNECTED bookkeeping only.
        if (!record.edgeObligationOpen()) {
            record.phase = EdgeRecoveryPhase.CONNECTED
            return
        }
        // ADR-0022 R28-E: record media fact, then completion evaluation — never direct RECOVERED.
        record.mediaRestored = true
        notifyAttemptLineageObservation(record, "transport_recovered_on_ice_connected")
        runIceRestorationCompletionEvaluation(record)
    }

    /**
     * R28-H.2: ICE reconnects while still [EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING].
     * Clear suspicion → HEALTHY. MUST NOT beginRecovery / REATTACH / RECOVERED.
     */
    private fun clearDebouncingSuspicion(record: EdgeRecoveryRecord) {
        val key = record.key
        cancelDebounce(key)
        cancelWatchdog(key)
        cancelDeadline(key)
        record.phase = EdgeRecoveryPhase.CONNECTED
        record.mediaRestored = false
        record.iceRestartIssued = false
        record.obligationOpenedAtMs = null
        record.obligationDeadlineAtMs = null
        record.obligationClosedAtMs = null
        record.obligationCloseReason = null
        record.hasPendingCompletionDecision = false
        onLog(
            "RECOVERY_DEBOUNCE_CLEARED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "reason=ice_reconnected_before_attempt"
        )
    }

    /**
     * ICE restoration → completion evaluation (ADR-0022 R28-E / #83).
     * With [EdgeRecoveryRecord.controlPlaneStarted], ICE CONNECTED MAY yield RECOVERED.
     */
    private fun runIceRestorationCompletionEvaluation(record: EdgeRecoveryRecord) {
        val key = record.key
        val controlPlane = record.controlPlaneStarted()
        onLog(
            "RECOVERY_REEVALUATE session=${key.sessionId} edge=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "controlPlaneStarted=$controlPlane mediaRestored=${record.mediaRestored}"
        )
        if (!record.mediaRestored) {
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                    "decision=NO_ACTION approved=true"
            )
            return
        }
        if (record.phase.isFailedMediaRecovery() && record.edgeObligationOpen()) {
            reEvaluateContinuationAfterTerminal(record)
            return
        }
        // R28-E: before control-plane, keep the fact; do not complete the edge.
        // WAITING is not terminal — schedule control-plane continuation (ADR-0022).
        if (!controlPlane) {
            continueControlPlaneRecoveryAfterMediaRestored(record)
            return
        }
        if (!record.phase.isActivelyRecovering()) {
            if (record.phase.isFailedMediaRecovery() && record.edgeObligationOpen()) {
                reEvaluateContinuationAfterTerminal(record)
                return
            }
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                    "decision=NO_ACTION approved=true"
            )
            return
        }
        val evidence = if (isIceConnected(key.sessionId, key.remoteModuleId)) {
            "ICE_CONNECTED"
        } else {
            "MEDIA_RESTORED"
        }
        logCompletionEvidenceAccepted(record, evidence)
        markRecovered(record, evidence)
        onLog(
            "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "decision=RECOVERED approved=true"
        )
    }

    /**
     * Media path is restored but the attempt has not crossed the control-plane boundary.
     * MUST schedule a next action — never leave obligation OPEN with no owner (soak ea6466f1).
     *
     * REATTACH_THEN_ICE_RESTART (initiatesReattach): when E2 equivalent control-plane evidence
     * is already satisfied (reattach delivery + peer signaling path + media live), reuse the
     * same CONTROL_PLANE_BOUNDARY exit as ICE_RESTART_ONLY — do not wait forever for accept.
     */
    private fun continueControlPlaneRecoveryAfterMediaRestored(record: EdgeRecoveryRecord) {
        val key = record.key
        onLog(
            "RECOVERY_CONTROL_PLANE_REQUIRED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "initiatesReattach=${record.initiatesReattach}"
        )
        if (record.initiatesReattach) {
            if (reattachMediaAlreadyLiveEvidenceSatisfied(record)) {
                crossControlPlaneBoundary(
                    record = record,
                    reason = "REATTACH_MEDIA_ALREADY_LIVE"
                )
                return
            }
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                    "decision=WAIT_FOR_CONTROL_PLANE approved=true"
            )
            // Route / inbound handlers own reattach dispatch — do not duplicate here.
            scheduleWatchdog(record)
            notifyChanged(key.sessionId)
            return
        }
        // ICE_RESTART_ONLY participant edge: do not flap transport when ICE is already CONNECTED.
        if (isIceConnected(key.sessionId, key.remoteModuleId) && record.mediaRestored) {
            crossControlPlaneBoundary(
                record = record,
                reason = "media_path_active_without_restart"
            )
            return
        }
        onLog(
            "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "decision=WAIT_FOR_CONTROL_PLANE approved=true"
        )
        issueBoundedIceRestart(record, RecoveryReason.ICE_DISCONNECTED)
    }

    /**
     * E2 equivalent control-plane convergence for REATTACH_THEN_ICE_RESTART (ADR-0022 4.3-D).
     * Identity is implicit: [record] is the live edge map entry for the current attempt/gen.
     * MUST NOT treat ICE_CONNECTED / mediaRestored alone as control-plane started.
     */
    private fun reattachMediaAlreadyLiveEvidenceSatisfied(record: EdgeRecoveryRecord): Boolean {
        if (!record.edgeObligationOpen()) return false
        if (!record.initiatesReattach) return false
        if (!hasReattachDeliveryEvidence(record)) return false
        val key = record.key
        // Peer signaling path reachable (authority/peer plane via existing action gate).
        if (!canDispatchRecoveryMediaAction(key.sessionId, key.remoteModuleId)) return false
        if (!record.mediaRestored) return false
        if (!isIceConnected(key.sessionId, key.remoteModuleId)) return false
        return true
    }

    private fun hasReattachDeliveryEvidence(record: EdgeRecoveryRecord): Boolean =
        record.reattachDeliveryState == ReattachDeliveryState.TRANSPORT_SENT ||
            record.reattachDeliveryState == ReattachDeliveryState.REMOTE_RECEIPT_ACKED

    /** Shared CONTROL_PLANE_BOUNDARY exit (H1/H3): phase + log + existing completion evaluator. */
    private fun crossControlPlaneBoundary(record: EdgeRecoveryRecord, reason: String) {
        val key = record.key
        record.phase = EdgeRecoveryPhase.ICE_RESTARTING
        onLog(
            "RECOVERY_CONTROL_PLANE_BOUNDARY session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} reason=$reason"
        )
        runIceRestorationCompletionEvaluation(record)
    }

    private fun markRecovered(record: EdgeRecoveryRecord, closeEvidence: String = "EDGE_RECOVERED") {
        val key = record.key
        val current = edges[key] ?: return
        if (
            record.recoveryAttemptId != current.recoveryAttemptId ||
            record.obligationGeneration != current.obligationGeneration
        ) {
            onLog(
                "IGNORE_STALE_TERMINAL_FACT session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "factAttempt=${record.recoveryAttemptId} factGen=${record.obligationGeneration} " +
                    "currentAttempt=${current.recoveryAttemptId} currentGen=${current.obligationGeneration} " +
                    "evidence=$closeEvidence"
            )
            return
        }
        // Exclusive close already stamped (e.g. OBLIGATION_DEADLINE on scheduler thread).
        // MUST NOT rewrite phase→RECOVERED; that poisons successor freshness (phase==RECOVERED).
        if (current.obligationClosedAtMs != null) {
            onLog(
                "IGNORE_STALE_TERMINAL_FACT session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "factAttempt=${record.recoveryAttemptId} factGen=${record.obligationGeneration} " +
                    "reason=obligation_already_closed closeReason=${current.obligationCloseReason} " +
                    "evidence=$closeEvidence"
            )
            return
        }
        cancelDebounce(key)
        cancelWatchdog(key)
        cancelDeadline(key)
        val oldPhase = record.phase
        record.phase = EdgeRecoveryPhase.RECOVERED
        logPhaseTransition(record, oldPhase, record.phase, "EDGE_RECOVERED")
        closeObligation(record, ObligationCloseReason.RECOVERED, closeEvidence)
        val durationMs = clock() - record.recoveryStartedAtMs
        onLog(
            "RECOVERY_EDGE_RECOVERED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} durationMs=$durationMs"
        )
        notifyAttemptLineageObservation(record, "edge_recovered")
        notifyChanged(key.sessionId)
    }

    fun cancelSession(sessionId: String, reason: String) {
        cancelledSessions[sessionId] = clock() + tombstoneTtlMs
        edges.keys.filter { it.sessionId == sessionId }.forEach { cancelEdge(it, reason) }
        notifyChanged(sessionId)
    }

    fun cancelChannel(channelId: String, reason: String) {
        cancelledChannels[channelId] = clock() + tombstoneTtlMs
        edges.values.filter { it.channelId == channelId }.forEach { cancelEdge(it.key, reason) }
        edges.keys
            .filter { key -> edges[key]?.channelId == channelId }
            .forEach { cancelEdge(it, reason) }
    }

    fun cancelEdge(sessionId: String, remoteModuleId: String, reason: String) {
        cancelEdge(ConferenceEdgeKey(sessionId, remoteModuleId), reason)
    }

    fun clearAll() {
        debounceTimers.values.forEach { it.cancel(false) }
        debounceTimers.clear()
        watchdogTimers.values.forEach { it.cancel(false) }
        watchdogTimers.clear()
        deadlineTimers.values.forEach { it.cancel(false) }
        deadlineTimers.clear()
        edges.clear()
        terminalReevaluateDedup.clear()
        cancelledSessions.clear()
        cancelledChannels.clear()
    }

    private fun beginRecovery(
        key: ConferenceEdgeKey,
        channelId: String,
        eligibility: EdgeRecoveryEligibility,
        initiatesReattach: Boolean,
        immediate: Boolean,
        trigger: RecoveryDecisionTrigger
    ) {
        if (!eligibility.isEligible()) {
            val terminationReason = inferTerminationReason(eligibility, trigger)
            val recoveryReason = resolveRecoveryReason(trigger, initiatesReattach)
            val rejectReason = ineligibilityReason(eligibility)
            logRecoveryDecision(
                sessionId = key.sessionId,
                edge = key.remoteModuleId,
                trigger = trigger,
                recoveryReason = recoveryReason,
                terminationReason = terminationReason,
                policy = RecoveryDecisionPolicy.NO_RECOVERY,
                approved = false,
                rejectReason = rejectReason,
                attempt = edges[key]?.recoveryAttemptId
            )
            onLog(
                "RECOVERY_EDGE_SKIPPED session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "reason=ineligible immediate=$immediate"
            )
            return
        }
        val existing = edges[key]
        if (existing?.hasActiveAttempt() == true &&
            existing.phase != EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING
        ) {
            onLog(
                "RECOVERY_ATTEMPT_REUSED session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "attempt=${existing.recoveryAttemptId} trigger=$trigger " +
                    "phase=${existing.phase} existingOwnerRetained=true"
            )
            return
        }
        val record = when {
            needsNewObligationEpisode(existing) &&
                existing?.phase != EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING -> {
                openNewRecoveryObligation(
                    key,
                    channelId,
                    EdgeRecoveryPhase.RECOVERY_PENDING,
                    initiatesReattach,
                    trigger.name
                )
            }
            else -> {
                upsertEdge(
                    key,
                    channelId,
                    EdgeRecoveryPhase.RECOVERY_PENDING,
                    initiatesReattach = initiatesReattach,
                    newAttempt = existing == null,
                    attemptOpenTrigger = trigger.name
                )
            }
        }
        val policy = if (initiatesReattach) {
            RecoveryDecisionPolicy.REATTACH_THEN_ICE_RESTART
        } else {
            RecoveryDecisionPolicy.ICE_RESTART_ONLY
        }
        val recoveryReason = resolveRecoveryReason(trigger, initiatesReattach)
        logRecoveryDecision(
            sessionId = key.sessionId,
            edge = key.remoteModuleId,
            trigger = trigger,
            recoveryReason = recoveryReason,
            terminationReason = RecoveryTerminationReason.NETWORK_LOSS,
            policy = policy,
            approved = true,
            rejectReason = null,
            attempt = record.recoveryAttemptId
        )
        onLog(
            "RECOVERY_EDGE_STARTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} initiatesReattach=$initiatesReattach " +
                "immediate=$immediate recoveryReason=$recoveryReason"
        )
        record.mediaActionOwner = MediaActionOwner.PENDING
        clearMediaActionDeferral(record)
        if (initiatesReattach) {
            applyReattachDispatchOutcome(
                record = record,
                outcome = onRequestReattach(key.sessionId, channelId, key.remoteModuleId)
            )
        } else {
            resolveMediaActionOwner(
                record = record,
                recoveryReason = recoveryReason,
                immediate = immediate,
                trigger = trigger.name
            )
        }
        scheduleWatchdog(record)
        notifyChanged(key.sessionId)
    }

    private fun issueBoundedIceRestart(
        record: EdgeRecoveryRecord,
        recoveryReason: RecoveryReason = RecoveryReason.UNKNOWN
    ) {
        if (record.iceRestartIssued) {
            logRecoveryDecision(
                sessionId = record.key.sessionId,
                edge = record.key.remoteModuleId,
                trigger = RecoveryDecisionTrigger.ICE_RESTART,
                recoveryReason = recoveryReason,
                terminationReason = RecoveryTerminationReason.UNKNOWN,
                policy = RecoveryDecisionPolicy.ICE_RESTART_ONLY,
                approved = false,
                rejectReason = "duplicate_ice_restart",
                attempt = record.recoveryAttemptId
            )
            return
        }
        // Negotiation Stabilization Gate (INV-NEG-006): sole execution admission point.
        // DEFER keeps phase / iceRestartIssued / watchdog unchanged (INV-NEG-004).
        val probe = probeIceRestartGate(record.key.sessionId, record.key.remoteModuleId)
        if (!probe.executable) {
            val block = probe.blockReason ?: IceRestartGateBlockReason.SIGNALING_NOT_STABLE
            val intentId = allocateIceRestartIntentId(record)
            onLog(
                "ICE_RESTART_GATE_BLOCKED session=${record.key.sessionId} " +
                    "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                    "intentId=$intentId reason=$block " +
                    "signalingState=${probe.signalingState ?: "UNKNOWN"} " +
                    "localRole=${probe.localRole ?: "UNKNOWN"}"
            )
            recordMediaActionDeferred(
                record = record,
                owner = MediaActionOwner.HOST_RESTART,
                reason = DeferredReason.NEGOTIATION_SETTLING,
                wakeupBinding = WakeupBinding(
                    sourceType = WakeupSourceType.NEGOTIATION_CAN_EXECUTE,
                    sourceKey = edgeWakeupKey(record.key.sessionId, record.key.remoteModuleId)
                ),
                trigger = "NEGOTIATION_STABILIZATION_GATE:$block"
            )
            record.deferredGateBlockReason = block
            onLog(
                "ICE_RESTART_DEFERRED session=${record.key.sessionId} " +
                    "remote=${record.key.remoteModuleId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} gen=${record.obligationGeneration} " +
                    "intentId=$intentId reason=$block wakeup=NEGOTIATION_CAN_EXECUTE"
            )
            return
        }
        record.phase = EdgeRecoveryPhase.ICE_RESTARTING
        record.iceRestartIssued = true
        assignMediaActionOwner(record, MediaActionOwner.HOST_RESTART)
        onLog(
            "RECOVERY_ICE_RESTART_DISPATCHED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "intentId=${record.iceRestartIntentId ?: "NONE"}"
        )
        val restarted = onIceRestart(record.key.sessionId, record.key.remoteModuleId)
        if (!restarted) {
            // Restart API may fail while ICE is already CONNECTED (#83 soak). Keep the
            // attempt active so completion evaluation can still observe mediaRestored.
            if (isIceConnected(record.key.sessionId, record.key.remoteModuleId)) {
                record.mediaRestored = true
            } else {
                enterFailedMediaResidency(record, reason = "ice_restart_failed")
            }
        }
        scheduleWatchdog(record)
        notifyChanged(record.key.sessionId)
    }

    private fun scheduleWatchdog(record: EdgeRecoveryRecord) {
        val key = record.key
        val attemptId = record.recoveryAttemptId
        val obligationGen = record.obligationGeneration
        cancelWatchdog(key)
        if (isCapabilityBlockingAttemptClock(record)) {
            onLog(
                "RECOVERY_WATCHDOG_DEFERRED session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "obligationGen=$obligationGen attempt=$attemptId " +
                    "reason=CAPABILITY_UNAVAILABLE " +
                    "deferredReason=${record.deferredReason ?: "dispatch_gate"}"
            )
            return
        }
        val budgetMs = minOf(attemptBudgetMs, iceRestartTimeoutMs + debounceMs)
        onLog(
            "RECOVERY_WATCHDOG_STARTED session=${key.sessionId} edge=${key.remoteModuleId} " +
                "obligationGen=$obligationGen attempt=$attemptId budgetMs=$budgetMs"
        )
        val future = scheduler.schedule({
            val current = edges[key] ?: return@schedule
            if (current.recoveryAttemptId != attemptId) return@schedule
            if (current.obligationGeneration != obligationGen) return@schedule
            if (!current.phase.isActivelyRecovering()) return@schedule
            onLog(
                "RECOVERY_FINAL_EVALUATION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${current.recoveryAttemptId} obligationGen=${current.obligationGeneration} " +
                    "reason=ATTEMPT_TIMEOUT controlPlaneStarted=${current.controlPlaneStarted()}"
            )
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${current.recoveryAttemptId} obligationGen=${current.obligationGeneration} " +
                    "decision=ATTEMPT_TIMEOUT approved=false"
            )
            onLog(
                "RECOVERY_ATTEMPT_TIMEOUT session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "obligationGen=${current.obligationGeneration} attempt=${current.recoveryAttemptId}"
            )
            // Re-check after logging: ACCEPTED may have SUPERSEDED mid-callback (TOCTOU).
            val still = edges[key] ?: return@schedule
            if (still.recoveryAttemptId != attemptId) return@schedule
            if (still.obligationGeneration != obligationGen) return@schedule
            if (!still.phase.isActivelyRecovering()) return@schedule
            if (isCapabilityBlockingAttemptClock(still)) {
                onLog(
                    "RECOVERY_WATCHDOG_DEFERRED session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "obligationGen=${still.obligationGeneration} attempt=${still.recoveryAttemptId} " +
                        "reason=CAPABILITY_UNAVAILABLE_AT_FIRE " +
                        "deferredReason=${still.deferredReason ?: "dispatch_gate"}"
                )
                return@schedule
            }
            val abortReason = when {
                hasDeferredMediaAction(still) -> {
                    assignMediaActionOwner(still, MediaActionOwner.ABORTED)
                    "OWNER_BLOCKED"
                }
                !still.mediaActionOwner.isAssigned() -> {
                    assignMediaActionOwner(still, MediaActionOwner.ABORTED)
                    "NO_MEDIA_ACTION_OWNER"
                }
                else -> "attempt_timeout"
            }
            enterFailedMediaResidency(
                still,
                reason = abortReason,
                explicitAbort = abortReason == "NO_MEDIA_ACTION_OWNER" || abortReason == "OWNER_BLOCKED"
            )
            notifyChanged(key.sessionId)
        }, budgetMs, TimeUnit.MILLISECONDS)
        watchdogTimers[key] = future
    }

    private fun cancelEdge(key: ConferenceEdgeKey, reason: String) {
        cancelDebounce(key)
        cancelWatchdog(key)
        cancelDeadline(key)
        val record = edges[key] ?: return
        record.phase = EdgeRecoveryPhase.CANCELLED
        val closeReason = when {
            reason.contains("session_cancelled", ignoreCase = true) ||
                reason.contains("conference", ignoreCase = true) ||
                reason.contains("terminated", ignoreCase = true) ->
                ObligationCloseReason.CONFERENCE_TERMINATED
            else -> ObligationCloseReason.MEMBERSHIP_LEFT
        }
        closeObligation(record, closeReason, reason)
        onLog(
            "RECOVERY_EDGE_CANCELLED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "reason=$reason"
        )
        edges.remove(key)
    }

    private fun resolveRecoveryInitiator(initiatesReattach: Boolean): String =
        if (initiatesReattach) "PARTICIPANT" else "AUTHORITY"

    private fun resolveRecoveryPolicy(initiatesReattach: Boolean): String =
        if (initiatesReattach) {
            RecoveryDecisionPolicy.REATTACH_THEN_ICE_RESTART.name
        } else {
            RecoveryDecisionPolicy.ICE_RESTART_ONLY.name
        }

    private fun cancelDebounce(key: ConferenceEdgeKey) {
        debounceTimers.remove(key)?.cancel(false)
    }

    private fun cancelWatchdog(key: ConferenceEdgeKey) {
        watchdogTimers.remove(key)?.cancel(false)
    }

    private fun upsertEdge(
        key: ConferenceEdgeKey,
        channelId: String,
        phase: EdgeRecoveryPhase,
        initiatesReattach: Boolean,
        newAttempt: Boolean = false,
        attemptOpenTrigger: String? = null
    ): EdgeRecoveryRecord {
        val now = clock()
        val existing = edges[key]
        val record = if (existing == null || newAttempt) {
            val previousAttempt = existing?.recoveryAttemptId
            val previousPhase = existing?.phase
            val previousObligationOpen = existing?.obligationClosedAtMs == null &&
                existing?.obligationOpenedAtMs != null
            // While OPEN, preserve obligation facts across attempts. After CLOSED, a later
            // recovery cycle starts a new obligation (not a reopen of the closed one).
            val preserveOpen = existing != null && existing.obligationClosedAtMs == null
            val obligationGen = when {
                preserveOpen -> existing!!.obligationGeneration
                existing == null -> 1L
                else -> existing.obligationGeneration + 1L
            }
            EdgeRecoveryRecord(
                key = key,
                phase = phase,
                channelId = channelId,
                recoveryAttemptId = ++attemptSeq,
                recoveryStartedAtMs = now,
                initiatesReattach = initiatesReattach,
                obligationGeneration = obligationGen,
                obligationOpenedAtMs = if (preserveOpen) {
                    existing!!.obligationOpenedAtMs ?: now
                } else {
                    now
                },
                obligationDeadlineAtMs = if (preserveOpen) existing!!.obligationDeadlineAtMs else null,
                obligationClosedAtMs = null,
                obligationCloseReason = null,
                hasPendingCompletionDecision = if (preserveOpen) {
                    existing!!.hasPendingCompletionDecision
                } else {
                    false
                }
            ).also { created ->
                edges[key] = created
                val trigger = attemptOpenTrigger
                    ?: if (newAttempt) "NEW_ATTEMPT" else "UPSERT"
                val pathway = when {
                    newAttempt -> "BEGIN_RECOVERY"
                    existing == null -> "UPSERT_EDGE"
                    else -> "NEW_ATTEMPT"
                }
                onLog(
                    formatRecoveryAttemptOpenedLog(
                        sessionId = key.sessionId,
                        remoteModuleId = key.remoteModuleId,
                        attemptId = created.recoveryAttemptId,
                        initiator = resolveRecoveryInitiator(initiatesReattach),
                        policy = resolveRecoveryPolicy(initiatesReattach),
                        startedAt = created.recoveryStartedAtMs,
                        supersededFromAttempt = null,
                        reason = trigger,
                        previousAttempt = previousAttempt,
                        previousPhase = previousPhase,
                        obligationOpen = previousObligationOpen,
                        obligationGeneration = created.obligationGeneration,
                        pathway = pathway
                    )
                )
                logPhaseTransition(created, existing?.phase, created.phase, if (newAttempt) "NEW_ATTEMPT" else "UPSERT")
            }
        } else {
            existing.apply {
                val oldPhase = this.phase
                this.phase = phase
                if (oldPhase != phase) {
                    logPhaseTransition(this, oldPhase, phase, "UPSERT")
                }
                if (channelId.isNotBlank()) this.channelId = channelId
                this.initiatesReattach = initiatesReattach
                if (obligationOpenedAtMs == null) {
                    obligationOpenedAtMs = now
                    if (obligationGeneration == 0L) obligationGeneration = 1L
                }
            }
        }
        return record
    }

    private fun notifyChanged(sessionId: String) {
        onRecoveryStateChanged(sessionId)
    }

    private fun resolveRecoveryReason(
        trigger: RecoveryDecisionTrigger,
        initiatesReattach: Boolean
    ): RecoveryReason = when {
        initiatesReattach -> RecoveryReason.HOST_REATTACH
        trigger == RecoveryDecisionTrigger.ICE_FAILED -> RecoveryReason.ICE_FAILED
        trigger == RecoveryDecisionTrigger.ICE_DISCONNECTED -> RecoveryReason.ICE_DISCONNECTED
        else -> RecoveryReason.NETWORK_RECOVERY
    }

    private fun isConnectivityRecoverySource(source: RecoverySource): Boolean = when (source) {
        RecoverySource.ICE_MONITOR,
        RecoverySource.TRANSPORT_MONITOR,
        RecoverySource.RECOVERY_TIMER -> true
        RecoverySource.JOIN_HANDLER,
        RecoverySource.INVITE_HANDLER,
        RecoverySource.USER_ACTION -> false
    }

    private fun isConnectivityRecoveryReason(reason: RecoveryReason): Boolean = when (reason) {
        RecoveryReason.NETWORK_RECOVERY,
        RecoveryReason.HOST_REATTACH,
        RecoveryReason.ICE_FAILED,
        RecoveryReason.ICE_DISCONNECTED -> true
        RecoveryReason.SESSION_CANCELLED,
        RecoveryReason.NON_CONNECTIVITY,
        RecoveryReason.UNKNOWN -> false
    }

    private fun inferTerminationReason(
        eligibility: EdgeRecoveryEligibility,
        trigger: RecoveryDecisionTrigger
    ): RecoveryTerminationReason = when {
        eligibility.conferenceTerminated -> RecoveryTerminationReason.CONFERENCE_TERMINATED
        !eligibility.remoteJoined || !eligibility.localJoined ->
            RecoveryTerminationReason.USER_LEAVE
        !eligibility.lifecycleEstablished -> RecoveryTerminationReason.NOT_ESTABLISHED
        trigger == RecoveryDecisionTrigger.ICE_DISCONNECTED ||
            trigger == RecoveryDecisionTrigger.ICE_FAILED ->
            RecoveryTerminationReason.NETWORK_LOSS
        else -> RecoveryTerminationReason.UNKNOWN
    }

    private fun ineligibilityReason(eligibility: EdgeRecoveryEligibility): String = when {
        eligibility.conferenceTerminated -> "conference_terminated"
        !eligibility.lifecycleEstablished -> "lifecycle_not_established"
        !eligibility.localJoined -> "local_not_joined"
        !eligibility.remoteJoined -> "remote_not_joined"
        else -> "ineligible"
    }

    private fun logRecoveryDecision(
        sessionId: String,
        edge: String,
        trigger: RecoveryDecisionTrigger,
        recoveryReason: RecoveryReason,
        terminationReason: RecoveryTerminationReason,
        policy: RecoveryDecisionPolicy,
        approved: Boolean,
        rejectReason: String?,
        attempt: Long?
    ) {
        val attemptPart = attempt?.let { "attempt=$it" } ?: "attempt=-"
        val rejectPart = rejectReason?.let { " rejectReason=$it" } ?: ""
        onLog(
            "RECOVERY_DECISION session=$sessionId edge=$edge trigger=$trigger " +
                "recoveryReason=$recoveryReason terminationReason=$terminationReason " +
                "policy=$policy approved=$approved $attemptPart$rejectPart"
        )
    }

    /** P2-B re-evaluate completion evaluation (ADR-0022 R28-C/E). */
    private fun runCompletionEvaluationStub(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature,
        trigger: RecoveryReevaluateTrigger
    ) {
        if (record.controlPlaneStarted() && snapshot.canCompleteRecovery()) {
            val evidence = completionEvidenceFromReachability(record, snapshot, trigger)
            logCompletionEvidenceAccepted(record, evidence, snapshot)
            markRecovered(record, evidence)
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger decision=RECOVERED approved=true"
            )
            return
        }
        if (record.phase.isFailedMediaRecovery() && hasResurrectionEvidence(snapshot, trigger)) {
            if (!admitTerminalReevaluate(record, trigger)) {
                onLog(
                    "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId} trigger=$trigger decision=IGNORE " +
                        "approved=true rejectReason=duplicate_post_terminal_fact"
                )
                return
            }
            val priorAttempt = record.recoveryAttemptId
            supersedeFailedResidencyAndAdmit(record, trigger, snapshot, signature)
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} priorAttempt=$priorAttempt " +
                    "trigger=$trigger decision=SUPERSEDED approved=true"
            )
            notifyChanged(record.key.sessionId)
            return
        }
        if (record.phase.isFailedMediaRecovery() && signature.permittedActions.isNotEmpty()) {
            val priorAttempt = record.recoveryAttemptId
            supersedeFailedResidencyAndAdmit(record, trigger, snapshot, signature)
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} priorAttempt=$priorAttempt " +
                    "trigger=$trigger decision=SUPERSEDED approved=true"
            )
            notifyChanged(record.key.sessionId)
            return
        }
        signature.waitingReason?.let { reason ->
            onLog(
                "RECOVERY_WAITING session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "reason=$reason ${snapshot.formatProbeFields()}"
            )
        }
        if (RecoveryAction.DISPATCH_REATTACH in signature.permittedActions) {
            when {
                !record.initiatesReattach -> {
                    onLog(
                        "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                            "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                            "decision=WAIT_FOR_INBOUND approved=true"
                    )
                    notifyChanged(record.key.sessionId)
                    return
                }
                record.controlPlaneStarted() -> {
                    onLog(
                        "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                            "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                            "decision=DISPATCH_REATTACH approved=false rejectReason=control_plane_started"
                    )
                    return
                }
                record.phase == EdgeRecoveryPhase.REATTACH_REQUESTED -> {
                    onLog(
                        "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                            "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                            "decision=DISPATCH_REATTACH approved=false rejectReason=transport_in_flight"
                    )
                    return
                }
                else -> {
                    applyReattachDispatchOutcome(
                        record = record,
                        outcome = onRequestReattach(
                            record.key.sessionId,
                            record.channelId,
                            record.key.remoteModuleId
                        ),
                        trigger = trigger
                    )
                    notifyChanged(record.key.sessionId)
                    return
                }
            }
        }
        if (signature.permittedActions.isEmpty() && signature.waitingReason != null) {
            if (
                !record.initiatesReattach &&
                record.phase.isActivelyRecovering() &&
                (record.mediaActionOwner == MediaActionOwner.PENDING || hasDeferredMediaAction(record))
            ) {
                resolveMediaActionOwner(
                    record = record,
                    recoveryReason = RecoveryReason.NETWORK_RECOVERY,
                    immediate = false,
                    trigger = trigger.name
                )
                if (record.iceRestartIssued || record.mediaActionDisposition == MediaActionDisposition.ACTIVE) {
                    notifyChanged(record.key.sessionId)
                    return
                }
            }
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                    "decision=WAIT_FOR_INBOUND approved=true"
            )
            return
        }
        onLog(
            "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=$trigger decision=NO_ACTION approved=true"
        )
    }

    /**
     * FAILED is not terminal while obligation OPEN (ADR-0022).
     * CHECKING / discovery are early resurrection signals — CONNECTED is not required.
     */
    private fun hasResurrectionEvidence(
        snapshot: EdgeReachabilitySnapshot,
        trigger: RecoveryReevaluateTrigger
    ): Boolean {
        if (!snapshot.linkReady || !snapshot.peerDiscovered) return false
        return when (trigger) {
            RecoveryReevaluateTrigger.ICE_CHECKING,
            RecoveryReevaluateTrigger.PEER_DISCOVERED,
            RecoveryReevaluateTrigger.PEER_REACHABILITY_RESTORED,
            RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED -> true
            RecoveryReevaluateTrigger.ROUTE_CONVERGED -> snapshot.mediaRouteConnected
            RecoveryReevaluateTrigger.AUTHORITY_REACHABLE -> snapshot.authorityReachable
            RecoveryReevaluateTrigger.ICE_RESTORED -> snapshot.linkReady && snapshot.peerDiscovered
            else -> false
        }
    }

    /**
     * R28-M: FAILED + obligation OPEN — route ICE restoration through continuation re-evaluate,
     * not direct supersede / beginRecovery.
     */
    private fun reEvaluateContinuationAfterTerminal(record: EdgeRecoveryRecord) {
        val key = record.key
        val trigger = RecoveryReevaluateTrigger.ICE_RESTORED
        // ADR-0032 § 9: ICE may only populate the media plane. Reached only after
        // mediaRestored, so the non-media planes are known-good for this continuation.
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = isIceConnected(key.sessionId, key.remoteModuleId),
            authorityReachable = false
        )
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = record.initiatesReattach,
            controlPlaneStarted = record.controlPlaneStarted()
        )
        runCompletionEvaluationStub(record, snapshot, signature, trigger)
    }

    private fun admitTerminalReevaluate(
        record: EdgeRecoveryRecord,
        trigger: RecoveryReevaluateTrigger
    ): Boolean {
        if (!record.phase.isFailedMediaRecovery()) return true
        val edgeKey = record.key
        val dedupKey = TerminalReevaluateKey(record.recoveryAttemptId, trigger)
        val prior = terminalReevaluateDedup.putIfAbsent(edgeKey, dedupKey)
        if (prior == null) return true
        if (prior == dedupKey) return false
        terminalReevaluateDedup[edgeKey] = dedupKey
        return true
    }

    private fun applyReattachDispatchOutcome(
        record: EdgeRecoveryRecord,
        outcome: ReattachDispatchOutcome,
        trigger: RecoveryReevaluateTrigger? = null
    ) {
        val key = record.key
        val triggerPart = trigger?.let { " trigger=$it" } ?: ""
        when (outcome) {
            ReattachDispatchOutcome.SENT -> {
                cancelDebounce(key)
                record.phase = EdgeRecoveryPhase.REATTACH_REQUESTED
                record.reattachDeliveryState = ReattachDeliveryState.TRANSPORT_SENT
                record.outboundDispatchAttemptId = record.recoveryAttemptId
                record.outboundDispatchObligationGeneration = record.obligationGeneration
                record.reattachNonce = pendingTransportNonce.remove(key) ?: record.reattachNonce
                assignMediaActionOwner(record, MediaActionOwner.HOST_RESTART)
                val noncePart = record.reattachNonce?.let { " nonce=$it" } ?: ""
                onLog(
                    "RECOVERY_REATTACH_SENT session=${key.sessionId} remote=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}$noncePart transportResult=SENT " +
                        "deliveryState=TRANSPORT_SENT controlPlaneStarted=${record.controlPlaneStarted()}"
                )
                onLog(
                    "RECOVERY_REATTACH_REQUESTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId} deliveryState=TRANSPORT_SENT " +
                        "controlPlaneStarted=${record.controlPlaneStarted()}"
                )
                onLog(
                    "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}$triggerPart " +
                        "decision=DISPATCH_REATTACH approved=true outcome=TRANSPORT_SENT"
                )
            }
            ReattachDispatchOutcome.DEFERRED -> {
                // Coordinator defers only on the action gate (transport/discovery/signaling),
                // never on the media route (ADR-0032 INV-REC-010).
                record.phase = EdgeRecoveryPhase.RECOVERY_PENDING
                recordMediaActionDeferred(
                    record = record,
                    owner = MediaActionOwner.PARTICIPANT_REATTACH,
                    reason = DeferredReason.MEDIA_NOT_READY,
                    wakeupBinding = WakeupBinding(
                        sourceType = WakeupSourceType.ROUTE_CONVERGED,
                        sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                    ),
                    trigger = trigger?.name ?: "DISPATCH_REATTACH"
                )
                onLog(
                    "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}$triggerPart " +
                        "decision=DISPATCH_REATTACH approved=true outcome=DEFERRED"
                )
            }
            ReattachDispatchOutcome.SESSION_CANCELLED -> {
                cancelEdge(key, "session_cancelled")
            }
            ReattachDispatchOutcome.PEER_UNREACHABLE,
            ReattachDispatchOutcome.SEND_FAILED -> {
                enterFailedMediaResidency(record, reason = "reattach_send_failed")
                onLog(
                    "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}$triggerPart " +
                        "decision=DISPATCH_REATTACH approved=false"
                )
            }
        }
    }

    private fun supersedeFailedResidencyAndAdmit(
        record: EdgeRecoveryRecord,
        trigger: RecoveryReevaluateTrigger,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature
    ) {
        supersedeAttempt(record, trigger = trigger.name)
        admitSupersededRecoveryAttempt(record, trigger, snapshot, signature)
    }

    /**
     * Appendix C-3.1: supersede from FAILED residency must enter ownership lifecycle (C-10).
     * Mirrors [beginRecovery] admission after attempt open — without incrementing attempt again.
     */
    private fun admitSupersededRecoveryAttempt(
        record: EdgeRecoveryRecord,
        trigger: RecoveryReevaluateTrigger,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature
    ) {
        val key = record.key
        val recoveryReason = RecoveryReason.NETWORK_RECOVERY
        onLog(
            "RECOVERY_EDGE_STARTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} initiatesReattach=${record.initiatesReattach} " +
                "immediate=false recoveryReason=$recoveryReason pathway=SUPERSEDE"
        )
        record.mediaActionOwner = MediaActionOwner.PENDING
        clearMediaActionDeferral(record)
        if (record.initiatesReattach) {
            if (
                RecoveryAction.DISPATCH_REATTACH in signature.permittedActions &&
                !record.controlPlaneStarted()
            ) {
                applyReattachDispatchOutcome(
                    record = record,
                    outcome = onRequestReattach(key.sessionId, record.channelId, key.remoteModuleId),
                    trigger = trigger
                )
            } else {
                // Action gate blocked (transport/discovery/signaling) — never the media
                // route (ADR-0032 INV-REC-010).
                recordMediaActionDeferred(
                    record = record,
                    owner = MediaActionOwner.PARTICIPANT_REATTACH,
                    reason = DeferredReason.MEDIA_NOT_READY,
                    wakeupBinding = WakeupBinding(
                        sourceType = WakeupSourceType.ROUTE_CONVERGED,
                        sourceKey = edgeWakeupKey(key.sessionId, key.remoteModuleId)
                    ),
                    trigger = "SUPERSEDE:$trigger"
                )
            }
        } else {
            resolveMediaActionOwner(
                record = record,
                recoveryReason = recoveryReason,
                immediate = false,
                trigger = "SUPERSEDE:$trigger"
            )
        }
    }

    private fun supersedeAttempt(
        record: EdgeRecoveryRecord,
        trigger: String,
        scheduleNewWatchdog: Boolean = true
    ) {
        val previousAttempt = record.recoveryAttemptId
        val previousPhase = record.phase
        val previousObligationOpen = record.obligationClosedAtMs == null &&
            record.obligationOpenedAtMs != null
        terminalReevaluateDedup.remove(record.key)
        // Drop prior failed-residency deadline; next FAILED stamps a fresh one (R28-H.1).
        // Also cancel the superseded attempt's watchdog so it cannot emit FAILED (#79).
        cancelDeadline(record.key)
        cancelWatchdog(record.key)
        record.obligationDeadlineAtMs = null
        record.phase = EdgeRecoveryPhase.RECOVERY_PENDING
        record.recoveryAttemptId = ++attemptSeq
        record.iceRestartIssued = false
        record.mediaRestored = false
        record.epochRefreshUsed = false
        record.recoveryViaInboundReattach = false
        record.reattachDeliveryState = ReattachDeliveryState.QUEUED
        record.reattachNonce = null
        record.recoveryStartedAtMs = clock()
        expireDeferredIceRestartIntent(record, "SUPERSEDE:$trigger")
        record.mediaActionOwner = MediaActionOwner.PENDING
        clearMediaActionDeferral(record)
        onLog(
            formatRecoveryAttemptOpenedLog(
                sessionId = record.key.sessionId,
                remoteModuleId = record.key.remoteModuleId,
                attemptId = record.recoveryAttemptId,
                initiator = resolveRecoveryInitiator(record.initiatesReattach),
                policy = resolveRecoveryPolicy(record.initiatesReattach),
                startedAt = record.recoveryStartedAtMs,
                supersededFromAttempt = previousAttempt,
                reason = trigger,
                previousAttempt = previousAttempt,
                previousPhase = previousPhase,
                obligationOpen = previousObligationOpen,
                obligationGeneration = record.obligationGeneration,
                pathway = "SUPERSEDE"
            )
        )
        onLog(
            "RECOVERY_ATTEMPT_SUPERSEDED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} oldAttempt=$previousAttempt " +
                "newAttempt=${record.recoveryAttemptId} reason=$trigger " +
                "supersededByModule=${if (trigger == "REATTACH_INBOUND") record.key.remoteModuleId else "NONE"} " +
                "parentAttempt=$previousAttempt"
        )
        if (scheduleNewWatchdog) {
            scheduleWatchdog(record)
        }
        notifyAttemptLineageObservation(record, "attempt_superseded", previousAttempt)
    }
}
