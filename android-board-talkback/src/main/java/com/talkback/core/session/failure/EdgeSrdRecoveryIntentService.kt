package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceNativeExecutionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B1: edge-scoped recovery intent state machine. Read-only domain facts; no native mutation.
 */
class EdgeSrdRecoveryIntentService(
    private val domainSnapshotProvider: () -> ConferenceNativeExecutionDomain.HolderSnapshot? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logLine: (String) -> Unit = {},
) {

    private val intentGenerationCounter = AtomicLong(0L)
    private val activeByScope = ConcurrentHashMap<EdgeSrdRecoveryScopeKey, EdgeSrdRecoveryIntent>()

    fun activeIntent(scopeKey: EdgeSrdRecoveryScopeKey): EdgeSrdRecoveryIntent? =
        activeByScope[scopeKey]

    fun intentsForSession(conferenceSessionId: String): List<EdgeSrdRecoveryIntent> =
        activeByScope.values.filter { it.scopeKey.conferenceSessionId == conferenceSessionId }

    fun onScenarioEdgeFailed(trigger: EdgeSrdRecoveryTrigger): EdgeSrdRecoveryIntent? {
        val scopeKey = EdgeSrdRecoveryScopeKey.fromScope(
            edgeKey = trigger.terminal.edgeKey,
            scope = trigger.terminal.generationScope,
        )
        activeByScope[scopeKey]?.let { existing ->
            if (existing.state != EdgeSrdRecoveryState.TERMINAL_FAILED) {
                logLine(
                    EdgeSrdRecoveryIntentObservability.formatSuppressed(
                        scopeKey = scopeKey,
                        remoteModuleId = trigger.remoteModuleId,
                        reason = "DUPLICATE_ACTIVE",
                        runtimeDomainRef = trigger.terminal.runtimeDomainRef,
                        causeFact = trigger.causeFact,
                    )
                )
            }
            return existing.takeUnless { it.state == EdgeSrdRecoveryState.TERMINAL_FAILED }
        }

        val requested = EdgeSrdRecoveryIntent(
            scopeKey = scopeKey,
            remoteModuleId = trigger.remoteModuleId,
            conferenceGeneration = trigger.conferenceGeneration,
            runtimeDomainRef = trigger.terminal.runtimeDomainRef,
            offerLineageId = trigger.offerLineageId,
            realizationAttemptId = trigger.realizationAttemptId,
            causeFact = trigger.causeFact,
            requestedAtMs = clock(),
            intentGeneration = intentGenerationCounter.incrementAndGet(),
            state = EdgeSrdRecoveryState.RECOVERY_REQUESTED,
        )
        activeByScope[scopeKey] = requested
        logLine(EdgeSrdRecoveryIntentObservability.formatRequested(requested))

        return if (shouldWaitForSafeAdmission(trigger.terminal.edgeKey)) {
            val waiting = requested.copy(state = EdgeSrdRecoveryState.WAITING_FOR_SAFE_ADMISSION)
            activeByScope[scopeKey] = waiting
            logLine(EdgeSrdRecoveryIntentObservability.formatWaitingForDomain(waiting))
            waiting
        } else {
            requested
        }
    }

    fun clearSession(conferenceSessionId: String) {
        val keys = activeByScope.keys.filter { it.conferenceSessionId == conferenceSessionId }
        keys.forEach { key ->
            terminalIntent(key, reason = "SESSION_END")
        }
    }

    private fun terminalIntent(scopeKey: EdgeSrdRecoveryScopeKey, reason: String) {
        val current = activeByScope[scopeKey] ?: return
        if (current.state == EdgeSrdRecoveryState.TERMINAL_FAILED) return
        val terminal = current.copy(
            state = EdgeSrdRecoveryState.TERMINAL_FAILED,
            terminalReason = reason,
        )
        activeByScope.remove(scopeKey)
        logLine(EdgeSrdRecoveryIntentObservability.formatTerminal(terminal))
    }

    /**
     * B1: domain/lease occupied on failure edge — safe admission not available (read-only facts).
     */
    internal fun shouldWaitForSafeAdmission(failureEdgeKey: String): Boolean {
        val snapshot = domainSnapshotProvider() ?: return false
        return snapshot.edgeKey == failureEdgeKey &&
            (
                snapshot.state == ConferenceNativeExecutionDomain.LeaseState.ACTIVE ||
                    snapshot.state == ConferenceNativeExecutionDomain.LeaseState.STUCK ||
                    snapshot.state == ConferenceNativeExecutionDomain.LeaseState.QUARANTINED
                )
    }
}
