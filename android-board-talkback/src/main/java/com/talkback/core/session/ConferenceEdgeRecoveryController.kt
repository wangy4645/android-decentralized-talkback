package com.talkback.core.session

import com.talkback.core.qos.IceConnectivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Per-edge conference recovery policy and state (ADR-0021 R4–R18).
 * Control-plane reattach precedes bounded media ICE restart; termination cancels all edges.
 */
class ConferenceEdgeRecoveryController(
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
     * Probe current ICE connectedness after ACCEPTED / ICE restart (#83).
     * Coordinator wires qosMonitor; tests inject to cover already-CONNECTED soak gap.
     */
    private val isIceConnected: (sessionId: String, remoteModuleId: String) -> Boolean = { _, _ -> false },
    private val onRecoveryStateChanged: (sessionId: String) -> Unit = {}
) {
    private val edges = ConcurrentHashMap<ConferenceEdgeKey, EdgeRecoveryRecord>()
    private val debounceTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val watchdogTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val deadlineTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val cancelledSessions = ConcurrentHashMap<String, Long>()
    private val cancelledChannels = ConcurrentHashMap<String, Long>()
    private var attemptSeq = 0L

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
        return EdgeRecoveryFacts(
            recoveringRemoteModuleIds = recovering,
            anyRecovering = recovering.isNotEmpty(),
            failedRemoteModuleIds = failed,
            anyFailedMediaRecovery = failed.isNotEmpty(),
            mediaUnavailableRemoteModuleIds = failed
        )
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
        pathway: String
    ): String =
        "RECOVERY_ATTEMPT_OPENED session=$sessionId remote=$remoteModuleId " +
            "attemptId=$attemptId initiator=$initiator policy=$policy startedAt=$startedAt " +
            "supersededFromAttempt=${supersededFromAttempt ?: "NONE"} reason=$reason " +
            "newAttempt=$attemptId previousAttempt=${previousAttempt ?: "NONE"} " +
            "previousPhase=${previousPhase ?: "NONE"} previousObligationOpen=$obligationOpen " +
            "pathway=$pathway"

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
                "obligationOpen=${record.edgeObligationOpen()} " +
                "pendingCompletion=${record.hasPendingCompletionDecision}"
        )
    }

    private fun closeObligation(record: EdgeRecoveryRecord, reason: ObligationCloseReason) {
        if (record.obligationClosedAtMs != null) return
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
     * Single writer of [EdgeRecoveryRecord.obligationDeadlineAtMs] (ADR-0022 R28-H.1 / #77).
     */
    private fun enterFailedMediaResidency(record: EdgeRecoveryRecord, reason: String) {
        val oldPhase = record.phase
        record.phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY
        logPhaseTransition(record, oldPhase, record.phase, "FAILED_MEDIA:$reason")
        val terminalAt = clock()
        record.obligationDeadlineAtMs = terminalAt + observationWindowMs
        onLog(
            "FAILED_MEDIA_RECOVERY session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} reason=$reason deadlineAt=${record.obligationDeadlineAtMs}"
        )
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
            closeObligation(current, ObligationCloseReason.OBLIGATION_DEADLINE)
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
     */
    fun onRecoveryReachabilityChanged(
        sessionId: String,
        channelId: String,
        remoteModuleId: String,
        snapshot: EdgeReachabilitySnapshot,
        signature: RecoveryCapabilitySignature,
        capabilityBefore: RecoveryCapabilitySignature?,
        trigger: RecoveryReevaluateTrigger
    ) {
        val key = ConferenceEdgeKey(sessionId, remoteModuleId)
        val record = edges[key] ?: return
        if (!record.edgeObligationOpen()) return
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
        val debounce = scheduler.schedule({
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
        upsertEdge(
            key,
            channelId,
            EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING,
            initiatesReattach = initiatesReattach,
            attemptOpenTrigger = RecoveryDecisionTrigger.ICE_DISCONNECTED.name
        )
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
        // Duplicate only while this attempt is already accepted / ICE-restarting.
        // After FAILED residency, a later ACCEPTED must SUPERSEDE (#79 soak fddec479).
        if (existing?.phase == EdgeRecoveryPhase.REATTACH_ACCEPTED ||
            existing?.phase == EdgeRecoveryPhase.ICE_RESTARTING ||
            (existing?.iceRestartIssued == true && existing.phase.isActivelyRecovering())
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
        // No open recovery obligation: idle CONNECTED bookkeeping only.
        if (!record.edgeObligationOpen() && record.phase != EdgeRecoveryPhase.RECOVERED) {
            record.phase = EdgeRecoveryPhase.CONNECTED
            return
        }
        // ADR-0022 R28-E: record media fact, then completion evaluation — never direct RECOVERED.
        record.mediaRestored = true
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
        // R28-E: before control-plane, keep the fact; do not complete the edge.
        // WAITING is not terminal — schedule control-plane continuation (ADR-0022).
        if (!controlPlane) {
            continueControlPlaneRecoveryAfterMediaRestored(record)
            return
        }
        if (!record.phase.isActivelyRecovering()) {
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                    "decision=NO_ACTION approved=true"
            )
            return
        }
        markRecovered(record)
        onLog(
            "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "decision=RECOVERED approved=true"
        )
    }

    /**
     * Media path is restored but the attempt has not crossed the control-plane boundary.
     * MUST schedule a next action — never leave obligation OPEN with no owner (soak ea6466f1).
     */
    private fun continueControlPlaneRecoveryAfterMediaRestored(record: EdgeRecoveryRecord) {
        val key = record.key
        onLog(
            "RECOVERY_CONTROL_PLANE_REQUIRED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "initiatesReattach=${record.initiatesReattach}"
        )
        if (record.initiatesReattach) {
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
            record.phase = EdgeRecoveryPhase.ICE_RESTARTING
            onLog(
                "RECOVERY_CONTROL_PLANE_BOUNDARY session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} reason=media_path_active_without_restart"
            )
            runIceRestorationCompletionEvaluation(record)
            return
        }
        onLog(
            "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} trigger=${RecoveryReevaluateTrigger.ICE_RESTORED} " +
                "decision=WAIT_FOR_CONTROL_PLANE approved=true"
        )
        issueBoundedIceRestart(record, RecoveryReason.ICE_DISCONNECTED)
    }

    private fun markRecovered(record: EdgeRecoveryRecord) {
        val key = record.key
        cancelDebounce(key)
        cancelWatchdog(key)
        cancelDeadline(key)
        val oldPhase = record.phase
        record.phase = EdgeRecoveryPhase.RECOVERED
        logPhaseTransition(record, oldPhase, record.phase, "EDGE_RECOVERED")
        closeObligation(record, ObligationCloseReason.RECOVERED)
        val durationMs = clock() - record.recoveryStartedAtMs
        onLog(
            "RECOVERY_EDGE_RECOVERED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} durationMs=$durationMs"
        )
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
        val record = upsertEdge(
            key,
            channelId,
            if (initiatesReattach) EdgeRecoveryPhase.RECOVERY_PENDING else EdgeRecoveryPhase.RECOVERY_PENDING,
            initiatesReattach = initiatesReattach,
            newAttempt = true,
            attemptOpenTrigger = trigger.name
        )
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
        if (initiatesReattach) {
            applyReattachDispatchOutcome(
                record = record,
                outcome = onRequestReattach(key.sessionId, channelId, key.remoteModuleId)
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
        record.phase = EdgeRecoveryPhase.ICE_RESTARTING
        record.iceRestartIssued = true
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
        cancelWatchdog(key)
        val budgetMs = minOf(attemptBudgetMs, iceRestartTimeoutMs + debounceMs)
        val future = scheduler.schedule({
            val current = edges[key] ?: return@schedule
            // Attempt-scoped: a superseded attempt's timer must not fail the live attempt (#79).
            if (current.recoveryAttemptId != attemptId) return@schedule
            if (!current.phase.isActivelyRecovering()) return@schedule
            onLog(
                "RECOVERY_FINAL_EVALUATION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${current.recoveryAttemptId} reason=ATTEMPT_TIMEOUT " +
                    "controlPlaneStarted=${current.controlPlaneStarted()}"
            )
            onLog(
                "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                    "attempt=${current.recoveryAttemptId} decision=ATTEMPT_TIMEOUT approved=false"
            )
            // Re-check after logging: ACCEPTED may have SUPERSEDED mid-callback (TOCTOU).
            val still = edges[key] ?: return@schedule
            if (still.recoveryAttemptId != attemptId) return@schedule
            if (!still.phase.isActivelyRecovering()) return@schedule
            enterFailedMediaResidency(still, reason = "attempt_timeout")
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
        closeObligation(record, closeReason)
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
            EdgeRecoveryRecord(
                key = key,
                phase = phase,
                channelId = channelId,
                recoveryAttemptId = ++attemptSeq,
                recoveryStartedAtMs = now,
                initiatesReattach = initiatesReattach,
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
                if (obligationOpenedAtMs == null) obligationOpenedAtMs = now
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
            markRecovered(record)
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger decision=RECOVERED approved=true"
            )
            return
        }
        if (record.phase.isFailedMediaRecovery() && hasResurrectionEvidence(snapshot, trigger)) {
            val priorAttempt = record.recoveryAttemptId
            supersedeAttempt(record, trigger = trigger.name)
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} priorAttempt=$priorAttempt " +
                    "trigger=$trigger decision=SUPERSEDED approved=true"
            )
            return
        }
        var superseded = false
        if (record.phase.isFailedMediaRecovery() && signature.permittedActions.isNotEmpty()) {
            supersedeAttempt(record, trigger = trigger.name)
            superseded = true
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger decision=SUPERSEDED approved=true"
            )
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
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger " +
                    "decision=WAIT_FOR_INBOUND approved=true"
            )
            return
        }
        if (!superseded) {
            onLog(
                "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} trigger=$trigger decision=NO_ACTION approved=true"
            )
        }
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
            RecoveryReevaluateTrigger.PEER_DISCOVERED -> true
            else -> false
        }
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
                record.phase = EdgeRecoveryPhase.REATTACH_REQUESTED
                onLog(
                    "RECOVERY_REATTACH_REQUESTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}"
                )
                onLog(
                    "RECOVERY_DECISION session=${key.sessionId} edge=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}$triggerPart " +
                        "decision=DISPATCH_REATTACH approved=true"
                )
            }
            ReattachDispatchOutcome.DEFERRED -> {
                record.phase = EdgeRecoveryPhase.RECOVERY_PENDING
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

    private fun supersedeAttempt(
        record: EdgeRecoveryRecord,
        trigger: String,
        scheduleNewWatchdog: Boolean = true
    ) {
        val previousAttempt = record.recoveryAttemptId
        val previousPhase = record.phase
        val previousObligationOpen = record.obligationClosedAtMs == null &&
            record.obligationOpenedAtMs != null
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
        record.recoveryStartedAtMs = clock()
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
                pathway = "SUPERSEDE"
            )
        )
        if (scheduleNewWatchdog) {
            scheduleWatchdog(record)
        }
    }
}
