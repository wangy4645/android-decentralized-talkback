package com.talkback.core.session

/**
 * ADR-0057 IGIE — inbound accept obligation vs engine provision vs GROUP_ACCEPT separation.
 */
object GroupInviteInboundExecutionSupport {

    data class AcceptAttemptKey(
        val sessionId: String,
        val remoteModuleId: String,
    ) {
        fun storageKey(): String = "$sessionId|$remoteModuleId"
    }

    fun provisionKey(sessionId: String, remoteModuleId: String): AcceptAttemptKey =
        AcceptAttemptKey(sessionId, remoteModuleId)

    enum class AcceptPath {
        /** First inbound GROUP_INVITE — session already admitted. */
        Fresh,

        /** Same-session reconnect / ICE-restart invite. */
        Reconnect,
    }

    enum class AcceptGate {
        /** May queue engine provision and eventually complete accept. */
        Proceed,

        /** Accept already satisfied for this peer — suppress duplicate GROUP_ACCEPT. */
        SkipAcceptComplete,

        /** Engine provision already queued — retain obligation, no parallel queue. */
        SkipEngineProvisionInFlight,
    }

    sealed interface DispatchOutcome {
        /** Remote offer applied and GROUP_ACCEPT handed off (sync or async completion). */
        data object AcceptSent : DispatchOutcome

        /** Engine provision deferred; accept obligation retained. */
        data object EngineDeferred : DispatchOutcome

        /** Duplicate inbound invite suppressed (provision in flight or accept complete). */
        data object SkippedDuplicate : DispatchOutcome
    }

    fun evaluateAcceptAttempt(
        acceptComplete: Boolean,
        engineProvisionInFlight: Boolean,
    ): AcceptGate =
        when {
            acceptComplete -> AcceptGate.SkipAcceptComplete
            engineProvisionInFlight -> AcceptGate.SkipEngineProvisionInFlight
            else -> AcceptGate.Proceed
        }

    fun countsAsDispatched(outcome: DispatchOutcome): Boolean =
        outcome == DispatchOutcome.AcceptSent || outcome == DispatchOutcome.EngineDeferred
}
