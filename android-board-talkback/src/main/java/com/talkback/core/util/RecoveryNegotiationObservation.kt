package com.talkback.core.util

/** ADR-0037 Phase 3.1 negotiation observability (shadow mode). */
object RecoveryNegotiationObservation {

    enum class IntentState {
        CREATED,
        DEFERRED,
        EXECUTED,
        BLOCKED,
        BLOCKED_BY_GLARE,
        EXPIRED
    }

    enum class OwnerRule {
        existing_owner,
        recovery_coordinator,
        module_tiebreaker
    }

    enum class GlareDecision {
        KEEP_LOCAL,
        ACCEPT_REMOTE,
        REJECT_STALE,
        DROP_DUPLICATE_LEGACY,
        DROP_ICE_RESTART_THROTTLED_LEGACY,
        ACCEPT_ICE_RESTART_LEGACY,
        ACCEPT_FIRST_MESH_LEGACY,
        OTHER_LEGACY
    }

    data class ShadowOwnerResolution(
        val candidateOwners: List<String>,
        val selectedOwner: String,
        val rule: OwnerRule
    )

    data class EdgeObservationContext(
        val sessionId: String,
        val edgeModuleId: String,
        val episodeId: Long,
        val obligationGen: Long,
        val intentId: String?,
        val mediaActionOwnerLabel: String,
        val deferredReason: String?,
        val existingTransactionOwnerModuleId: String?,
        val recoveryCoordinatorOwnerModuleId: String?
    )

    private var logSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        logSink = sink
    }

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) {
            sink(message)
            return
        }
        try {
            TalkbackLog.i(message)
        } catch (_: RuntimeException) {
            // JVM unit tests without android.util.Log mock.
        }
    }

    fun shadowResolveOwner(
        localModuleId: String,
        remoteModuleId: String,
        existingTransactionOwnerModuleId: String?,
        recoveryCoordinatorOwnerModuleId: String?
    ): ShadowOwnerResolution {
        val candidates = buildList {
            existingTransactionOwnerModuleId?.let { add(it) }
            recoveryCoordinatorOwnerModuleId?.let { if (!contains(it)) add(it) }
            if (!contains(localModuleId)) add(localModuleId)
            if (!contains(remoteModuleId)) add(remoteModuleId)
        }
        val (selected, rule) = when {
            existingTransactionOwnerModuleId != null ->
                existingTransactionOwnerModuleId to OwnerRule.existing_owner
            recoveryCoordinatorOwnerModuleId != null ->
                recoveryCoordinatorOwnerModuleId to OwnerRule.recovery_coordinator
            else -> {
                val tie = if (localModuleId > remoteModuleId) localModuleId else remoteModuleId
                tie to OwnerRule.module_tiebreaker
            }
        }
        return ShadowOwnerResolution(candidates, selected, rule)
    }

    fun emitOwnerResolved(
        sessionId: String,
        edgeModuleId: String,
        episodeId: Long,
        localModuleId: String,
        existingTransactionOwnerModuleId: String?,
        recoveryCoordinatorOwnerModuleId: String?,
        trigger: String
    ) {
        val resolution = shadowResolveOwner(
            localModuleId = localModuleId,
            remoteModuleId = edgeModuleId,
            existingTransactionOwnerModuleId = existingTransactionOwnerModuleId,
            recoveryCoordinatorOwnerModuleId = recoveryCoordinatorOwnerModuleId
        )
        val sb = StringBuilder("RECOVERY_NEGOTIATION_OWNER_RESOLVED")
        sb.append(" sessionId=").append(sessionId)
        sb.append(" edge=").append(edgeModuleId)
        sb.append(" episodeId=").append(episodeId)
        sb.append(" candidateOwners=").append(resolution.candidateOwners.joinToString(","))
        sb.append(" selectedOwner=").append(resolution.selectedOwner)
        sb.append(" rule=").append(resolution.rule.name)
        sb.append(" trigger=").append(trigger)
        existingTransactionOwnerModuleId?.let { sb.append(" existing_owner=").append(it) }
        recoveryCoordinatorOwnerModuleId?.let { sb.append(" recovery_coordinator=").append(it) }
        log(sb.toString())
    }

    fun emitOwnerResolvedFromContext(
        ctx: EdgeObservationContext,
        localModuleId: String,
        trigger: String
    ) {
        emitOwnerResolved(
            sessionId = ctx.sessionId,
            edgeModuleId = ctx.edgeModuleId,
            episodeId = ctx.episodeId,
            localModuleId = localModuleId,
            existingTransactionOwnerModuleId = ctx.existingTransactionOwnerModuleId,
            recoveryCoordinatorOwnerModuleId = ctx.recoveryCoordinatorOwnerModuleId,
            trigger = trigger
        )
    }

    fun emitIntent(
        sessionId: String,
        edgeModuleId: String,
        intentId: String,
        episodeId: Long,
        negotiationEpoch: Long = 0L,
        ownerModuleId: String,
        reason: String,
        state: IntentState
    ) {
        val sb = StringBuilder("RECOVERY_NEGOTIATION_INTENT")
        sb.append(" sessionId=").append(sessionId)
        sb.append(" edge=").append(edgeModuleId)
        sb.append(" intentId=").append(intentId)
        sb.append(" episodeId=").append(episodeId)
        sb.append(" negotiationEpoch=").append(negotiationEpoch)
        sb.append(" epochSource=SHADOW_UNWIRED")
        sb.append(" owner=").append(ownerModuleId)
        sb.append(" reason=").append(reason)
        sb.append(" state=").append(state.name)
        log(sb.toString())
    }

    fun emitIntentFromContext(
        ctx: EdgeObservationContext,
        localModuleId: String,
        state: IntentState,
        reason: String
    ) {
        val intentId = ctx.intentId ?: return
        emitIntent(
            sessionId = ctx.sessionId,
            edgeModuleId = ctx.edgeModuleId,
            intentId = intentId,
            episodeId = ctx.episodeId,
            ownerModuleId = localModuleId,
            reason = reason,
            state = state
        )
    }

    fun emitGlareDecision(
        sessionId: String,
        edgeModuleId: String,
        episodeId: Long?,
        localModuleId: String,
        localSignalingState: String?,
        localDescType: String?,
        remoteDescType: String?,
        localOwner: String,
        remoteOwner: String,
        decision: GlareDecision,
        reason: String,
        glareDetected: Boolean
    ) {
        val sb = StringBuilder("RECOVERY_GLARE_DECISION")
        sb.append(" sessionId=").append(sessionId)
        sb.append(" edge=").append(edgeModuleId)
        episodeId?.let { sb.append(" episodeId=").append(it) }
        sb.append(" localState=").append(localSignalingState ?: "UNKNOWN")
        sb.append(" localDesc=").append(localDescType ?: "NONE")
        sb.append(" remoteDesc=").append(remoteDescType ?: "OFFER")
        sb.append(" localOwner=").append(localOwner)
        sb.append(" remoteOwner=").append(remoteOwner)
        sb.append(" glareDetected=").append(glareDetected)
        sb.append(" decision=").append(decision.name)
        sb.append(" reason=").append(reason)
        log(sb.toString())
    }


    fun emitOwnerConflict(
        sessionId: String,
        edgeModuleId: String,
        episodeId: Long,
        canonicalOwner: String,
        wireOwner: String,
        trigger: String
    ) {
        val sb = StringBuilder("RECOVERY_NEGOTIATION_OWNER_CONFLICT")
        sb.append(" sessionId=").append(sessionId)
        sb.append(" edge=").append(edgeModuleId)
        sb.append(" episodeId=").append(episodeId)
        sb.append(" canonicalOwner=").append(canonicalOwner)
        sb.append(" wireOwner=").append(wireOwner)
        sb.append(" trigger=").append(trigger)
        log(sb.toString())
    }
    fun emitIntentTerminal(
        sessionId: String,
        edgeModuleId: String,
        intentId: String,
        terminalState: String,
        reason: String
    ) {
        val sb = StringBuilder("RECOVERY_NEGOTIATION_INTENT_TERMINAL")
        sb.append(" sessionId=").append(sessionId)
        sb.append(" edge=").append(edgeModuleId)
        sb.append(" intentId=").append(intentId)
        sb.append(" terminalState=").append(terminalState)
        sb.append(" reason=").append(reason)
        log(sb.toString())
    }

    fun emitIntentTerminalFromContext(
        ctx: EdgeObservationContext,
        terminalState: String,
        reason: String
    ) {
        emitIntentTerminal(
            sessionId = ctx.sessionId,
            edgeModuleId = ctx.edgeModuleId,
            intentId = ctx.intentId ?: "NONE",
            terminalState = terminalState,
            reason = reason
        )
    }
}