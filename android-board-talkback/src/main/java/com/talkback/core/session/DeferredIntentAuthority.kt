package com.talkback.core.session

import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0022 §E.16.1 J-X / Slice-1: sole owner of deferred-intent lifecycle transitions.
 *
 * P2 PR-B consolidation: compile-carried by PR-A; ownership and regression guards land here.
 * Does not expand capability — freezes R2/B3 release facts and terminals.
 *
 * Owns: supersede legality, SUPERSEDED terminal, releaseIntent facts, late-event disposition.
 * Does NOT own: media recovery, negotiation capability predicate, drain algorithm,
 * dispatch readiness, CompletionPolicy / RECOVERED.
 */
class DeferredIntentAuthority(
    private val onLog: (String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onReleaseFence: (
        sessionId: String,
        remoteModuleId: String,
        intentId: String,
        reason: String
    ) -> Unit = { _, _, _, _ -> },
    /**
     * Gate 3C-B / RNA-5.4: bridge supersede facts to negotiation terminal closure.
     * Authority MUST NOT emit [RECOVERY_NEGOTIATION_INTENT_TERMINAL] directly.
     */
    private val onNegotiationCloseRequest: (
        sessionId: String,
        remoteModuleId: String,
        intentId: String,
        terminalHint: String,
        source: String,
        cause: String
    ) -> Unit = { _, _, _, _, _, _ -> }
) {
    enum class ExecutionState {
        CREATED,
        HELD_DISPATCH,
        EXECUTED,
        SUPERSEDED
    }

    enum class RequestingDomain {
        MEDIA,
        NEGOTIATION,
        TRANSPORT,
        CONTROL,
        TEST
    }

    enum class LateEventDisposition {
        /** Intent is active — caller may proceed with normal handling. */
        PROCEED,
        /** Intent is SUPERSEDED — observation only; no mutation / drain / completion. */
        AUDIT_ONLY
    }

    sealed class SupersedeResult {
        data class Accepted(
            val intentId: String,
            val oldState: ExecutionState,
            val supersededAtMs: Long
        ) : SupersedeResult()

        data class Rejected(
            val intentId: String?,
            val reason: String,
            val currentState: ExecutionState?
        ) : SupersedeResult()
    }

    sealed class ReleaseResult {
        data class Accepted(
            val intentId: String,
            val terminalState: ExecutionState,
            val reason: String
        ) : ReleaseResult()

        data class Rejected(
            val intentId: String?,
            val reason: String
        ) : ReleaseResult()

        /** No authority record — slot may still be released if caller owns orphan cleanup. */
        data class NoAuthorityRecord(val intentId: String) : ReleaseResult()
    }

    enum class ReleaseKind {
        /** Transition to SUPERSEDED via [requestSupersede] if not already terminal. */
        SUPERSEDE,
        /** Terminal discard (obligation close / stale lineage) — authority supersede + audit. */
        TERMINAL_DISCARD,
        /** Slot release only — authority must already be EXECUTED. */
        SLOT_AFTER_EXECUTED
    }

    private data class IntentRecord(
        val intentId: String,
        val sessionId: String,
        val remoteModuleId: String,
        var state: ExecutionState,
        var fenceArmed: Boolean,
        val createdAtMs: Long,
        var heldAtMs: Long? = null,
        var executedAtMs: Long? = null,
        var supersededAtMs: Long? = null,
        var supersedeReason: String? = null,
        var replacementIntentId: String? = null,
        var requestingDomain: RequestingDomain? = null
    )

    private val intents = ConcurrentHashMap<String, IntentRecord>()

    fun clearAll() {
        intents.clear()
    }

    fun executionState(intentId: String): ExecutionState? = intents[intentId]?.state

    fun isSuperseded(intentId: String): Boolean =
        intents[intentId]?.state == ExecutionState.SUPERSEDED

    fun isExecutable(intentId: String): Boolean {
        val state = intents[intentId]?.state ?: return false
        return state == ExecutionState.CREATED || state == ExecutionState.HELD_DISPATCH
    }

    /**
     * J-X-5: replacement must not inherit dispatchReady / CAN_EXECUTE / probe / drain eligibility.
     * Slice-1: always false across distinct intents.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mayInheritDispatchEvidence(fromIntentId: String, toIntentId: String): Boolean = false

    fun registerCreated(
        intentId: String,
        sessionId: String,
        remoteModuleId: String,
        fenceArmed: Boolean = true
    ) {
        val existing = intents[intentId]
        if (existing != null) {
            if (existing.state == ExecutionState.SUPERSEDED || existing.state == ExecutionState.EXECUTED) {
                onLog(
                    "DEFERRED_INTENT_AUTHORITY_REJECT session=$sessionId remote=$remoteModuleId " +
                        "intentId=$intentId op=REGISTER_CREATED reason=terminal_state " +
                        "state=${existing.state}"
                )
                return
            }
            existing.fenceArmed = fenceArmed
            return
        }
        intents[intentId] = IntentRecord(
            intentId = intentId,
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            state = ExecutionState.CREATED,
            fenceArmed = fenceArmed,
            createdAtMs = clock()
        )
        onLog(
            "DEFERRED_INTENT_AUTHORITY_REGISTERED session=$sessionId remote=$remoteModuleId " +
                "intentId=$intentId state=CREATED fenceArmed=$fenceArmed"
        )
    }

    fun markHeldDispatch(intentId: String): Boolean {
        val record = intents[intentId] ?: return false
        when (record.state) {
            ExecutionState.CREATED, ExecutionState.HELD_DISPATCH -> {
                record.state = ExecutionState.HELD_DISPATCH
                record.heldAtMs = clock()
                onLog(
                    "DEFERRED_INTENT_AUTHORITY_HELD session=${record.sessionId} " +
                        "remote=${record.remoteModuleId} intentId=$intentId state=HELD_DISPATCH"
                )
                return true
            }
            ExecutionState.EXECUTED, ExecutionState.SUPERSEDED -> {
                onLog(
                    "DEFERRED_INTENT_AUTHORITY_REJECT session=${record.sessionId} " +
                        "remote=${record.remoteModuleId} intentId=$intentId op=MARK_HELD " +
                        "reason=illegal_from_${record.state}"
                )
                return false
            }
        }
    }

    fun markExecuted(intentId: String): Boolean {
        val record = intents[intentId] ?: return false
        when (record.state) {
            ExecutionState.CREATED, ExecutionState.HELD_DISPATCH -> {
                record.state = ExecutionState.EXECUTED
                record.executedAtMs = clock()
                record.fenceArmed = false
                onLog(
                    "DEFERRED_INTENT_AUTHORITY_EXECUTED session=${record.sessionId} " +
                        "remote=${record.remoteModuleId} intentId=$intentId state=EXECUTED"
                )
                return true
            }
            ExecutionState.EXECUTED -> return true
            ExecutionState.SUPERSEDED -> {
                onLog(
                    "DEFERRED_INTENT_AUTHORITY_REJECT session=${record.sessionId} " +
                        "remote=${record.remoteModuleId} intentId=$intentId op=MARK_EXECUTED " +
                        "reason=illegal_from_SUPERSEDED"
                )
                return false
            }
        }
    }

    /**
     * Domain requests supersede; authority alone may mutate ownership.
     */
    fun requestSupersede(
        intentId: String,
        reason: String,
        requestingDomain: RequestingDomain,
        replacementIntentId: String? = null
    ): SupersedeResult {
        val record = intents[intentId]
            ?: return SupersedeResult.Rejected(
                intentId = intentId,
                reason = "unknown_intent",
                currentState = null
            )

        when (record.state) {
            ExecutionState.EXECUTED -> {
                onLog(
                    "DEFERRED_INTENT_AUTHORITY_REJECT session=${record.sessionId} " +
                        "remote=${record.remoteModuleId} intentId=$intentId op=SUPERSEDE " +
                        "reason=illegal_from_EXECUTED"
                )
                return SupersedeResult.Rejected(intentId, "illegal_from_EXECUTED", record.state)
            }
            ExecutionState.SUPERSEDED -> {
                onLog(
                    "DEFERRED_INTENT_AUTHORITY_REJECT session=${record.sessionId} " +
                        "remote=${record.remoteModuleId} intentId=$intentId op=SUPERSEDE " +
                        "reason=idempotent_already_superseded"
                )
                return SupersedeResult.Rejected(
                    intentId,
                    "idempotent_already_superseded",
                    record.state
                )
            }
            ExecutionState.CREATED, ExecutionState.HELD_DISPATCH -> {
                val oldState = record.state
                val at = clock()
                record.state = ExecutionState.SUPERSEDED
                record.supersededAtMs = at
                record.supersedeReason = reason
                record.replacementIntentId = replacementIntentId
                record.requestingDomain = requestingDomain

                onLog(
                    "DEFERRED_INTENT_SUPERSEDED session=${record.sessionId} " +
                        "remote=${record.remoteModuleId} oldIntent=$intentId " +
                        "newIntent=${replacementIntentId ?: "NONE"} " +
                        "oldState=$oldState newState=SUPERSEDED " +
                        "authority=DeferredIntentAuthority " +
                        "requestingDomain=$requestingDomain reason=$reason " +
                        "supersededAtMs=$at"
                )

                if (record.fenceArmed) {
                    record.fenceArmed = false
                    onLog(
                        "DEFERRED_INTENT_VALIDATION_FENCE session=${record.sessionId} " +
                            "remote=${record.remoteModuleId} intentId=$intentId " +
                            "transition=ARMED_TO_RELEASED_BY_SUPERSEDE reason=SUPERSEDE"
                    )
                    onLog(
                        "FENCE_RELEASED session=${record.sessionId} " +
                            "remote=${record.remoteModuleId} intentId=$intentId " +
                            "reason=SUPERSEDE"
                    )
                    onReleaseFence(
                        record.sessionId,
                        record.remoteModuleId,
                        intentId,
                        "RELEASED_BY_SUPERSEDE"
                    )
                }

                onNegotiationCloseRequest(
                    record.sessionId,
                    record.remoteModuleId,
                    intentId,
                    "SUPERSEDED",
                    resolveNegotiationCloseSource(requestingDomain, reason),
                    reason
                )

                return SupersedeResult.Accepted(intentId, oldState, at)
            }
        }
    }

    /**
     * J-X-6: SUPERSEDED may observe late events; must not mutate / drain / complete.
     */
    fun observeLateEvent(intentId: String, eventType: String): LateEventDisposition {
        val record = intents[intentId] ?: return LateEventDisposition.PROCEED
        if (record.state != ExecutionState.SUPERSEDED) return LateEventDisposition.PROCEED
        onLog(
            "DEFERRED_INTENT_LATE_EVENT_OBSERVED session=${record.sessionId} " +
                "remote=${record.remoteModuleId} intentId=$intentId " +
                "eventType=$eventType disposition=AUDIT_ONLY " +
                "state=SUPERSEDED reason=${record.supersedeReason ?: "NONE"}"
        )
        return LateEventDisposition.AUDIT_ONLY
    }

    private fun logReleaseAccepted(
        record: IntentRecord,
        intentId: String,
        reason: String,
        terminal: ExecutionState
    ) {
        onLog(
            "DEFERRED_INTENT_RELEASED session=${record.sessionId} " +
                "remote=${record.remoteModuleId} intentId=$intentId " +
                "terminal=$terminal reason=$reason authority=DeferredIntentAuthority"
        )
    }

    /**
     * PR5-3 Grill R2 / INV-DI-001: sole path for committed intent terminal transition + slot release.
     * Controller MUST NOT set `record.iceRestartIntentId = null` without a successful release here.
     */
    fun releaseIntent(
        intentId: String,
        reason: String,
        requestingDomain: RequestingDomain,
        kind: ReleaseKind,
        expireCause: String? = null
    ): ReleaseResult {
        val record = intents[intentId]
        if (record == null) {
            onLog(
                "DEFERRED_INTENT_RELEASE_NO_AUTHORITY intentId=$intentId reason=$reason " +
                    "kind=$kind expireCause=${expireCause ?: "NONE"}"
            )
            return ReleaseResult.NoAuthorityRecord(intentId)
        }

        if (expireCause != null) {
            emitExpireAudit(record, expireCause)
        }

        when (kind) {
            ReleaseKind.SLOT_AFTER_EXECUTED -> {
                when (record.state) {
                    ExecutionState.EXECUTED -> {
                        logReleaseAccepted(record, intentId, reason, ExecutionState.EXECUTED)
                        return ReleaseResult.Accepted(intentId, ExecutionState.EXECUTED, reason)
                    }
                    ExecutionState.SUPERSEDED -> {
                        logReleaseAccepted(record, intentId, reason, ExecutionState.SUPERSEDED)
                        return ReleaseResult.Accepted(intentId, ExecutionState.SUPERSEDED, reason)
                    }
                    ExecutionState.CREATED, ExecutionState.HELD_DISPATCH -> {
                        onLog(
                            "DEFERRED_INTENT_RELEASE_REJECT session=${record.sessionId} " +
                                "remote=${record.remoteModuleId} intentId=$intentId " +
                                "kind=SLOT_AFTER_EXECUTED reason=not_executed state=${record.state}"
                        )
                        return ReleaseResult.Rejected(intentId, "not_executed")
                    }
                }
            }
            ReleaseKind.SUPERSEDE -> {
                if (record.state == ExecutionState.SUPERSEDED) {
                    logReleaseAccepted(record, intentId, reason, ExecutionState.SUPERSEDED)
                    return ReleaseResult.Accepted(intentId, ExecutionState.SUPERSEDED, reason)
                }
                if (record.state == ExecutionState.EXECUTED) {
                    return ReleaseResult.Rejected(intentId, "illegal_from_EXECUTED")
                }
                return when (
                    val supersede = requestSupersede(intentId, reason, requestingDomain)
                ) {
                    is SupersedeResult.Accepted -> {
                        logReleaseAccepted(record, intentId, reason, ExecutionState.SUPERSEDED)
                        ReleaseResult.Accepted(intentId, ExecutionState.SUPERSEDED, reason)
                    }
                    is SupersedeResult.Rejected ->
                        ReleaseResult.Rejected(supersede.intentId, supersede.reason)
                }
            }
            ReleaseKind.TERMINAL_DISCARD -> {
                if (record.state == ExecutionState.SUPERSEDED) {
                    logReleaseAccepted(record, intentId, reason, ExecutionState.SUPERSEDED)
                    return ReleaseResult.Accepted(intentId, ExecutionState.SUPERSEDED, reason)
                }
                if (record.state == ExecutionState.EXECUTED) {
                    return ReleaseResult.Rejected(intentId, "illegal_from_EXECUTED")
                }
                val supersede = requestSupersede(
                    intentId = intentId,
                    reason = reason,
                    requestingDomain = requestingDomain
                )
                return when (supersede) {
                    is SupersedeResult.Accepted -> {
                        logReleaseAccepted(record, intentId, reason, ExecutionState.SUPERSEDED)
                        ReleaseResult.Accepted(intentId, ExecutionState.SUPERSEDED, reason)
                    }
                    is SupersedeResult.Rejected ->
                        ReleaseResult.Rejected(supersede.intentId, supersede.reason)
                }
            }
        }
    }

    private fun resolveNegotiationCloseSource(
        requestingDomain: RequestingDomain,
        reason: String
    ): String = when {
        reason.startsWith("GLARE:") -> "GLARE_RESOLVER"
        requestingDomain == RequestingDomain.MEDIA -> "MEDIA_ACTION_SUPERSEDE"
        requestingDomain == RequestingDomain.NEGOTIATION -> "NEGOTIATION_DRAIN"
        requestingDomain == RequestingDomain.CONTROL -> "OBLIGATION_CLOSE"
        else -> "MEDIA_ACTION_SUPERSEDE"
    }

    private fun emitExpireAudit(record: IntentRecord, cause: String) {
        val intentId = record.intentId
        val terminalReason = when {
            cause.startsWith("SUPERSEDE") || cause.startsWith("ADMIT_SUCCESSOR") -> "SUPERSEDED"
            cause.startsWith("OBLIGATION_CLOSE") || cause.startsWith("DRAIN_OBLIGATION") ->
                "OBLIGATION_CLOSED"
            cause.startsWith("DRAIN_STALE") || cause.startsWith("DRAIN_ALREADY") -> "RELEASE_MISSING"
            else -> "RELEASE_MISSING"
        }
        onLog(
            "RECOVERY_ICE_RESTART_INTENT_TERMINAL session=${record.sessionId} " +
                "remote=${record.remoteModuleId} attempt=UNKNOWN " +
                "intentId=$intentId obligationGen=UNKNOWN " +
                "terminal=STALE_DISCARD reason=$terminalReason expireCause=$cause " +
                "authority=DeferredIntentAuthority"
        )
        onLog(
            "RECOVERY_ICE_RESTART_INTENT_EXPIRED session=${record.sessionId} " +
                "remote=${record.remoteModuleId} attempt=UNKNOWN " +
                "intentId=$intentId obligationGen=UNKNOWN disposition=EXPIRED " +
                "terminal=STALE_DISCARD cause=$cause authority=DeferredIntentAuthority"
        )
    }
}
