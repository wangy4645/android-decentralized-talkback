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
    private val tombstoneTtlMs: Long = 120_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scheduler: ScheduledExecutorService,
    private val onLog: (String) -> Unit = {},
    private val onRequestReattach: (
        sessionId: String,
        channelId: String,
        remoteModuleId: String
    ) -> Boolean,
    private val onIceRestart: (sessionId: String, remoteModuleId: String) -> Boolean,
    private val onRecoveryStateChanged: (sessionId: String) -> Unit = {}
) {
    private val edges = ConcurrentHashMap<ConferenceEdgeKey, EdgeRecoveryRecord>()
    private val debounceTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val watchdogTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
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
            anyFailedMediaRecovery = failed.isNotEmpty()
        )
    }

    fun isAnyEdgeRecovering(sessionId: String): Boolean = factsForSession(sessionId).anyRecovering

    /** True while edge (sessionId, remoteModuleId) is in an active recovery ownership window (R26). */
    fun isEdgeRecovering(sessionId: String, remoteModuleId: String): Boolean {
        val record = edges[ConferenceEdgeKey(sessionId, remoteModuleId)] ?: return false
        return record.phase.isActivelyRecovering()
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
            onIceConnected(sessionId, remoteModuleId)
            return
        }
        if (iceState != "DISCONNECTED" && iceState != "FAILED") return

        val record = edges[key]
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
            initiatesReattach = initiatesReattach
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
        if (existing?.phase == EdgeRecoveryPhase.REATTACH_ACCEPTED ||
            existing?.phase == EdgeRecoveryPhase.ICE_RESTARTING ||
            existing?.iceRestartIssued == true
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
                initiatesReattach = false
            )
            edges[key]!!
        }
        if (isSessionCancelled(sessionId)) {
            onLog("RECOVERY_EVENT_DROPPED session=$sessionId remote=$remoteModuleId reason=session_cancelled")
            return
        }
        cancelDebounce(key)
        record.phase = EdgeRecoveryPhase.REATTACH_ACCEPTED
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
            else -> EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION
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
        if (!record.phase.isActivelyRecovering() && record.phase != EdgeRecoveryPhase.RECOVERED) {
            record.phase = EdgeRecoveryPhase.CONNECTED
            return
        }
        cancelDebounce(key)
        cancelWatchdog(key)
        record.phase = EdgeRecoveryPhase.RECOVERED
        onLog(
            "RECOVERY_EDGE_RECOVERED session=$sessionId remote=$remoteModuleId " +
                "attempt=${record.recoveryAttemptId}"
        )
        notifyChanged(sessionId)
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
            newAttempt = true
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
            val sent = onRequestReattach(
                key.sessionId,
                channelId,
                key.remoteModuleId
            )
            if (sent) {
                record.phase = EdgeRecoveryPhase.REATTACH_REQUESTED
                onLog(
                    "RECOVERY_REATTACH_REQUESTED session=${key.sessionId} remote=${key.remoteModuleId} " +
                        "attempt=${record.recoveryAttemptId}"
                )
            } else {
                record.phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY
                onLog(
                    "FAILED_MEDIA_RECOVERY session=${key.sessionId} remote=${key.remoteModuleId} " +
                        "reason=reattach_send_failed"
                )
            }
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
            record.phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY
            onLog(
                "FAILED_MEDIA_RECOVERY session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
                    "reason=ice_restart_failed"
            )
        }
        scheduleWatchdog(record)
        notifyChanged(record.key.sessionId)
    }

    private fun scheduleWatchdog(record: EdgeRecoveryRecord) {
        val key = record.key
        cancelWatchdog(key)
        val budgetMs = minOf(attemptBudgetMs, iceRestartTimeoutMs + debounceMs)
        val future = scheduler.schedule({
            val current = edges[key] ?: return@schedule
            if (current.phase.isActivelyRecovering()) {
                current.phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY
                onLog(
                    "FAILED_MEDIA_RECOVERY session=${key.sessionId} remote=${key.remoteModuleId} " +
                        "reason=attempt_timeout"
                )
                notifyChanged(key.sessionId)
            }
        }, budgetMs, TimeUnit.MILLISECONDS)
        watchdogTimers[key] = future
    }

    private fun cancelEdge(key: ConferenceEdgeKey, reason: String) {
        cancelDebounce(key)
        cancelWatchdog(key)
        val record = edges[key] ?: return
        record.phase = EdgeRecoveryPhase.CANCELLED
        onLog(
            "RECOVERY_EDGE_CANCELLED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "reason=$reason"
        )
        edges.remove(key)
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
        newAttempt: Boolean = false
    ): EdgeRecoveryRecord {
        val now = clock()
        val existing = edges[key]
        val record = if (existing == null || newAttempt) {
            EdgeRecoveryRecord(
                key = key,
                phase = phase,
                channelId = channelId,
                recoveryAttemptId = ++attemptSeq,
                recoveryStartedAtMs = now,
                initiatesReattach = initiatesReattach
            ).also { edges[key] = it }
        } else {
            existing.apply {
                this.phase = phase
                if (channelId.isNotBlank()) this.channelId = channelId
                this.initiatesReattach = initiatesReattach
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
}
